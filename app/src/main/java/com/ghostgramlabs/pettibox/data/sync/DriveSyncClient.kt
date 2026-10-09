package com.ghostgramlabs.pettibox.data.sync

import com.ghostgramlabs.pettibox.data.drive.DriveFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A Drive request failed. [status] is the HTTP code, or 0 for no connection. */
class DriveSyncException(val status: Int, message: String) : IOException(message) {
    /** Access was revoked or expired: the user has to reconnect. */
    val needsReconnect: Boolean get() = status == 401
    /** Worth retrying later: rate limits, server trouble, no network. */
    val transient: Boolean get() = status == 0 || status == 403 || status == 429 || status >= 500
}

/** A file in the sync folder. */
data class DriveSyncFile(val id: String, val name: String, val md5: String, val modifiedAt: Long)

/**
 * Drive REST calls for "Sync between devices", under the same drive.file
 * scope as backups (it only sees files PettiBox made). Kept apart from
 * DriveBackupClient on purpose, so sync work can never break backups.
 *
 * Layout: "PettiBox/Sync/" holds each device's d-<device>-<shard>.json.gz;
 * "PettiBox/Sync/files/" holds photos and files named by their sha256.
 */
@Singleton
class DriveSyncClient(
    private val folders: DriveFolders,
    private val api: String,
    private val upload: String,
    /** Bytes per piece of a resumable upload (a multiple of 256 KiB, as Drive asks). */
    private val chunkSize: Int = 8 * 1024 * 1024,
    /** Files at least this big go up resumably; smaller ones in one request. */
    private val resumableFrom: Long = 5L * 1024 * 1024
) {
    @Inject constructor(folders: DriveFolders) : this(folders, API, UPLOAD)

    /** My Drive/PettiBox/Sync, made if missing (see DriveFolders for the layout and moving old folders). */
    suspend fun syncFolder(token: String): String = folders.sync(token)

    suspend fun filesFolder(token: String, syncFolder: String): String = withContext(Dispatchers.IO) {
        findFolder(token, FILES_FOLDER, syncFolder) ?: createFolder(token, FILES_FOLDER, syncFolder)
            .let { findFolder(token, FILES_FOLDER, syncFolder) ?: it }
    }

    /** Every device file in the folder, with checksums, across all pages. */
    suspend fun listDeviceFiles(token: String, folder: String): List<DriveSyncFile> =
        list(token, "'$folder' in parents and trashed = false and name contains 'd-'")

    /** Every synced file (photos, PDFs…) by name. */
    suspend fun listStoredFiles(token: String, filesFolder: String): List<DriveSyncFile> =
        list(token, "'$filesFolder' in parents and trashed = false")

    /**
     * Downloads a device file, checks its md5, and un-gzips it. A damaged file
     * throws [SyncFormatException] (skip it); a network or Drive problem throws
     * [DriveSyncException] (try again later). Nothing is half-read.
     */
    suspend fun downloadShard(token: String, file: DriveSyncFile): String = withContext(Dispatchers.IO) {
        val bytes = download(token, file.id)
        if (file.md5.isNotBlank()) {
            val actual = md5(bytes)
            if (!actual.equals(file.md5, ignoreCase = true)) {
                throw SyncFormatException("${file.name} arrived damaged (checksum mismatch)")
            }
        }
        try {
            GZIPInputStream(bytes.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            throw SyncFormatException("${file.name} isn't a valid sync file")
        }
    }

    /** Writes this device's shard: replaces the file in place if it exists, else creates it. Returns its md5. */
    suspend fun uploadShard(token: String, folder: String, name: String, json: String, existingId: String?): String =
        withContext(Dispatchers.IO) {
            val bytes = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }.toByteArray()
            val response = if (existingId != null) {
                val conn = open(
                    "$upload/files/$existingId?uploadType=media&fields=id,md5Checksum", "PATCH", token
                )
                conn.setRequestProperty("Content-Type", "application/gzip")
                conn.doOutput = true
                conn.outputStream.use { it.write(bytes) }
                readResponse(conn)
            } else {
                multipartCreate(token, folder, name, "application/gzip", bytes.inputStream(), bytes.size.toLong())
            }
            JSONObject(response).optString("md5Checksum", md5(bytes))
        }

    /**
     * Uploads a photo or file as [name]. Big ones go up in pieces through a
     * resumable session: [session] is one an earlier, interrupted try left
     * (null if none), and [onSession] is told each new one (null once done),
     * so the next try carries on from the last piece Drive has instead of
     * starting over.
     */
    suspend fun uploadFile(
        token: String,
        filesFolder: String,
        name: String,
        file: File,
        mime: String,
        session: String? = null,
        onSession: suspend (String?) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val total = file.length()
        if (total < resumableFrom) {
            file.inputStream().use { multipartCreate(token, filesFolder, name, mime, it, total) }
            return@withContext
        }
        var uri = session
        var offset = 0L
        if (uri != null) {
            when (val at = uploadedSoFar(token, uri, total)) {
                null -> uri = null // expired (sessions last about a week): start again
                DONE -> { onSession(null); return@withContext }
                else -> offset = at
            }
        }
        if (uri == null) {
            uri = startSession(token, filesFolder, name, mime, total)
            onSession(uri)
        }
        while (true) {
            val end = minOf(offset + chunkSize, total)
            val conn = open(uri, "PUT", token)
            conn.instanceFollowRedirects = false // 308 here means "keep going", not a redirect
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(end - offset)
            conn.setRequestProperty("Content-Range", "bytes $offset-${end - 1}/$total")
            try {
                conn.outputStream.use { out ->
                    file.inputStream().use { input ->
                        input.skipFully(offset)
                        input.copyExactly(out, end - offset)
                    }
                }
                when (val code = conn.responseCode) {
                    200, 201 -> { onSession(null); return@withContext }
                    308 -> offset = nextByte(conn.getHeaderField("Range"))
                    404, 410 -> {
                        // The session ended on Drive's side: a fresh one next time.
                        onSession(null)
                        throw DriveSyncException(503, "upload session expired")
                    }
                    else -> {
                        val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        throw DriveSyncException(code, "Drive upload failed ($code): ${error.take(200)}")
                    }
                }
            } catch (e: DriveSyncException) {
                throw e
            } catch (e: IOException) {
                // Dropped mid-piece: the session stays, so the next try resumes.
                throw DriveSyncException(0, e.message ?: "network error")
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun startSession(token: String, folder: String, name: String, mime: String, total: Long): String {
        val conn = open("$upload/files?uploadType=resumable&fields=id", "POST", token)
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.setRequestProperty("X-Upload-Content-Type", mime)
        conn.setRequestProperty("X-Upload-Content-Length", total.toString())
        conn.doOutput = true
        try {
            conn.outputStream.use {
                it.write(JSONObject().put("name", name).put("parents", JSONArray().put(folder)).toString().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw DriveSyncException(code, "Drive upload failed ($code): ${error.take(200)}")
            }
            return conn.getHeaderField("Location") ?: throw DriveSyncException(500, "Drive gave no upload session")
        } catch (e: DriveSyncException) {
            throw e
        } catch (e: IOException) {
            throw DriveSyncException(0, e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    /** How far an upload session got: the next byte to send, [DONE], or null if it expired. */
    private fun uploadedSoFar(token: String, uri: String, total: Long): Long? {
        val conn = open(uri, "PUT", token)
        conn.instanceFollowRedirects = false
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(0)
        conn.setRequestProperty("Content-Range", "bytes */$total")
        try {
            conn.outputStream.close()
            return when (val code = conn.responseCode) {
                200, 201 -> DONE
                308 -> nextByte(conn.getHeaderField("Range"))
                404, 410 -> null
                else -> throw DriveSyncException(code, "Drive upload check failed ($code)")
            }
        } catch (e: DriveSyncException) {
            throw e
        } catch (e: IOException) {
            throw DriveSyncException(0, e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    /** "bytes=0-1234" means bytes up to 1234 arrived; no header means none did. */
    private fun nextByte(range: String?): Long =
        range?.substringAfterLast('-')?.trim()?.toLongOrNull()?.plus(1) ?: 0L

    private fun InputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) throw IOException("file shorter than expected")
                left--
            } else {
                left -= skipped
            }
        }
    }

    private fun InputStream.copyExactly(out: java.io.OutputStream, n: Long) {
        val buf = ByteArray(64 * 1024)
        var left = n
        while (left > 0) {
            val read = read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (read < 0) throw IOException("file shorter than expected")
            out.write(buf, 0, read)
            left -= read
        }
    }

    /**
     * Streams a stored file to [target], checking it hashes to [sha] before
     * keeping it. What arrived before a dropped connection is kept as
     * "<sha>.part", and the next try asks Drive only for the rest.
     */
    suspend fun downloadFile(token: String, fileId: String, sha: String, target: File) = withContext(Dispatchers.IO) {
        val tmp = File(target.parentFile, "$sha.part")
        val have = if (tmp.exists()) tmp.length() else 0L
        val conn = open("$api/files/$fileId?alt=media", "GET", token)
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            val digest = MessageDigest.getInstance("SHA-256")
            // 206: the rest follows; 416: nothing is left to send; 200: the whole file again.
            val append = have > 0 && (code == 206 || code == 416)
            if (code !in 200..299 && !(append && code == 416)) throw DriveSyncException(code, "download failed ($code)")
            if (append) tmp.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            if (code != 416) {
                val promised = conn.getHeaderField("Content-Length")?.toLongOrNull()
                var got = 0L
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(tmp, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            got += n
                        }
                    }
                }
                // Cut off early: not damaged, just unfinished. Keep it for next time.
                if (promised != null && got < promised) throw DriveSyncException(0, "download cut off")
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != sha) {
                tmp.delete()
                throw DriveSyncException(0, "a synced file arrived damaged")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: DriveSyncException) {
            throw e
        } catch (e: IOException) {
            // Keep what arrived: the next try carries on from there.
            throw DriveSyncException(0, e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    /** Content of the "sync was reset" marker, or null if sync was never reset. */
    suspend fun readResetMarker(token: String, folder: String): String? = withContext(Dispatchers.IO) {
        val f = list(token, "'$folder' in parents and trashed = false and name = '$RESET_MARKER'").firstOrNull()
            ?: return@withContext null
        String(download(token, f.id), Charsets.UTF_8)
    }

    /**
     * Deletes everything sync put in Drive (every device's files, the shared
     * photos and files) and leaves a marker, so other devices stop syncing
     * instead of re-uploading. Libraries on the devices are not touched.
     */
    suspend fun resetEverything(token: String, folder: String, marker: String) = withContext(Dispatchers.IO) {
        // Marker first: a device syncing mid-reset sees it and stops.
        val existing = list(token, "'$folder' in parents and trashed = false and name = '$RESET_MARKER'").firstOrNull()
        if (existing != null) {
            val conn = open("$upload/files/${existing.id}?uploadType=media", "PATCH", token)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(marker.toByteArray(Charsets.UTF_8)) }
            readResponse(conn)
        } else {
            val bytes = marker.toByteArray(Charsets.UTF_8)
            multipartCreate(token, folder, RESET_MARKER, "application/json", bytes.inputStream(), bytes.size.toLong())
        }
        for (f in list(token, "'$folder' in parents and trashed = false and name != '$RESET_MARKER'")) {
            readResponse(open("$api/files/${f.id}", "DELETE", token)) // the files/ folder goes with its contents
        }
    }

    suspend fun delete(token: String, fileId: String) = withContext(Dispatchers.IO) {
        readResponse(open("$api/files/$fileId", "DELETE", token))
        Unit
    }

    /** Email of the Google account behind the token (Drive `about`, allowed under drive.file). */
    suspend fun accountEmail(token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            JSONObject(readResponse(open("$api/about?fields=${enc("user(emailAddress)")}", "GET", token)))
                .optJSONObject("user")?.optString("emailAddress")?.ifBlank { null }
        }.getOrNull()
    }

    // ── Plumbing ─────────────────────────────────────────────────────────

    private suspend fun list(token: String, query: String): List<DriveSyncFile> = withContext(Dispatchers.IO) {
        val out = ArrayList<DriveSyncFile>()
        var page: String? = null
        do {
            val url = "$api/files?q=${enc(query)}&pageSize=1000" +
                "&fields=${enc("nextPageToken,files(id,name,md5Checksum,modifiedTime)")}" +
                (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val root = JSONObject(readResponse(open(url, "GET", token)))
            val files = root.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                out += DriveSyncFile(
                    id = f.getString("id"),
                    name = f.optString("name"),
                    md5 = f.optString("md5Checksum"),
                    modifiedAt = parseRfc3339(f.optString("modifiedTime"))
                )
            }
            page = root.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        out
    }

    private fun findFolder(token: String, name: String, parent: String?): String? {
        val q = "name = '$name' and mimeType = '$FOLDER_MIME' and trashed = false" +
            (parent?.let { " and '$it' in parents" } ?: "")
        val response = readResponse(
            open("$api/files?q=${enc(q)}&orderBy=createdTime&fields=${enc("files(id)")}", "GET", token)
        )
        val files = JSONObject(response).optJSONArray("files")
        return if (files != null && files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun createFolder(token: String, name: String, parent: String?): String {
        val conn = open("$api/files?fields=id", "POST", token)
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.doOutput = true
        val body = JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
        if (parent != null) body.put("parents", JSONArray().put(parent))
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return JSONObject(readResponse(conn)).getString("id")
    }

    private fun multipartCreate(token: String, folder: String, name: String, mime: String, data: InputStream, length: Long): String {
        val boundary = "pettibox-sync-${System.nanoTime()}"
        val conn = open("$upload/files?uploadType=multipart&fields=id,md5Checksum", "POST", token)
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        conn.doOutput = true
        if (length > 1_000_000) conn.setChunkedStreamingMode(0)
        conn.outputStream.buffered().use { out ->
            fun line(s: String) = out.write((s + "\r\n").toByteArray(Charsets.UTF_8))
            line("--$boundary")
            line("Content-Type: application/json; charset=UTF-8")
            line("")
            line(JSONObject().put("name", name).put("parents", JSONArray().put(folder)).toString())
            line("--$boundary")
            line("Content-Type: $mime")
            line("")
            data.copyTo(out)
            line("")
            line("--$boundary--")
        }
        return readResponse(conn)
    }

    private fun download(token: String, fileId: String): ByteArray {
        val conn = open("$api/files/$fileId?alt=media", "GET", token)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw DriveSyncException(code, "Drive download failed ($code): ${error.take(200)}")
            }
            return conn.inputStream.use { it.readBytes() }
        } catch (e: DriveSyncException) {
            throw e
        } catch (e: IOException) {
            throw DriveSyncException(0, e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, method: String, token: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (method == "PATCH") {
            // HttpURLConnection has no PATCH; Drive accepts the override header.
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        } else {
            conn.requestMethod = method
        }
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        return conn
    }

    private fun readResponse(conn: HttpURLConnection): String = try {
        val code = conn.responseCode
        if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw DriveSyncException(code, "Drive request failed ($code): ${error.take(200)}")
        }
    } catch (e: DriveSyncException) {
        throw e
    } catch (e: IOException) {
        throw DriveSyncException(0, e.message ?: "network error")
    } finally {
        conn.disconnect()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun parseRfc3339(value: String): Long {
        if (value.isBlank()) return 0L
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            runCatching {
                java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(value)
            }.getOrNull()?.let { return it.time }
        }
        return 0L
    }

    companion object {
        const val FILES_FOLDER = "files"
        /** Present once someone chose "Delete synced data from Google Drive". */
        const val RESET_MARKER = "pettibox-sync-reset.json"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val DONE = -1L

        fun shardName(device: String, shard: Int) = "d-$device-${shard.toString(16)}.json.gz"

        /** Device id from a shard file name, or null if it isn't one. */
        fun deviceOf(name: String): String? =
            Regex("^d-([0-9a-f]{8,32})-([0-9a-f])\\.json\\.gz$").find(name)?.groupValues?.get(1)
    }
}
