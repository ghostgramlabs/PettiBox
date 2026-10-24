package com.ghostgramlabs.pettibox.data.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class DriveBackupFile(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val sizeBytes: Long
)

/**
 * Google Drive v3 REST calls over HttpURLConnection. The official Drive
 * Java client drags in megabytes of google-http/api-client just to do
 * four requests, so we speak the JSON API directly:
 *
 * - find-or-create My Drive/PettiBox/Backups (via DriveFolders)
 * - multipart-upload a backup zip into it
 * - list / delete zips (rolling prune, same policy as local backups)
 * - stream a zip back down for restore
 *
 * Everything takes a bearer token from [DriveAuth]; nothing here shows UI.
 */
@Singleton
class DriveBackupClient @Inject constructor(private val folders: DriveFolders) {

    suspend fun uploadBackup(token: String, file: File): String = withContext(Dispatchers.IO) {
        val folderId = ensureBackupFolder(token)
        val metadata = JSONObject()
            .put("name", file.name)
            .put("parents", org.json.JSONArray().put(folderId))
            .toString()

        val boundary = "pettibox-${System.currentTimeMillis()}"
        val conn = open(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name",
            "POST",
            token
        )
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        conn.doOutput = true
        // Stream the zip rather than buffering it — attachment-heavy
        // libraries produce backups far bigger than we want on the heap.
        conn.setChunkedStreamingMode(0)
        conn.outputStream.buffered().use { out ->
            fun writeLine(s: String) = out.write((s + "\r\n").toByteArray(Charsets.UTF_8))
            writeLine("--$boundary")
            writeLine("Content-Type: application/json; charset=UTF-8")
            writeLine("")
            writeLine(metadata)
            writeLine("--$boundary")
            writeLine("Content-Type: application/zip")
            writeLine("")
            file.inputStream().use { it.copyTo(out) }
            writeLine("")
            writeLine("--$boundary--")
        }
        val response = readResponse(conn)
        JSONObject(response).optString("name", file.name)
    }

    suspend fun listBackups(token: String): List<DriveBackupFile> = withContext(Dispatchers.IO) {
        val folderId = findBackupFolder(token) ?: return@withContext emptyList()
        val q = encode("'$folderId' in parents and trashed = false and name contains 'pettibox-'")
        val fields = encode("files(id,name,createdTime,size)")
        val response = readResponse(
            open(
                "https://www.googleapis.com/drive/v3/files?q=$q&orderBy=createdTime%20desc&fields=$fields&pageSize=25",
                "GET",
                token
            )
        )
        val files = JSONObject(response).optJSONArray("files") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                add(
                    DriveBackupFile(
                        id = f.getString("id"),
                        name = f.optString("name"),
                        createdAtMillis = parseRfc3339(f.optString("createdTime")),
                        sizeBytes = f.optString("size").toLongOrNull() ?: 0L
                    )
                )
            }
        }
    }

    /** Same rolling-copies policy as [com.ghostgramlabs.pettibox.data.util.LocalBackupStore.pruneOldBackups]. */
    suspend fun pruneOldBackups(token: String, keep: Int = 3) {
        val stale = listBackups(token).sortedByDescending { it.createdAtMillis }.drop(keep)
        withContext(Dispatchers.IO) {
            stale.forEach { file ->
                // Best-effort: a failed delete just leaves an extra copy.
                runCatching {
                    val conn = open("https://www.googleapis.com/drive/v3/files/${file.id}", "DELETE", token)
                    readResponse(conn)
                }
            }
        }
    }

    /**
     * Open the backup's content stream. Caller must close it; the
     * connection is tied to the stream and closes with it.
     */
    suspend fun openBackupStream(token: String, fileId: String): InputStream =
        withContext(Dispatchers.IO) {
            val conn = open("https://www.googleapis.com/drive/v3/files/$fileId?alt=media", "GET", token)
            val code = conn.responseCode
            if (code !in 200..299) {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                throw IOException("Drive download failed ($code): ${error.take(200)}")
            }
            conn.inputStream
        }

    /**
     * Email of the Google account the token belongs to, via the Drive
     * `about` endpoint (allowed under drive.file — no extra scope). Null
     * on any failure; identity display is best-effort, never blocking.
     */
    suspend fun accountEmail(token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val response = readResponse(
                open(
                    "https://www.googleapis.com/drive/v3/about?fields=${encode("user(emailAddress)")}",
                    "GET",
                    token
                )
            )
            JSONObject(response).optJSONObject("user")
                ?.optString("emailAddress")
                ?.ifBlank { null }
        }.getOrNull()
    }

    // ── Folder plumbing ───────────────────────────────────────────────────

    // Backups live in My Drive/PettiBox/Backups (an older top-level
    // "PettiBox Backups" is moved there the first time; see DriveFolders).
    private suspend fun findBackupFolder(token: String): String? = folders.backups(token, create = false)

    private suspend fun ensureBackupFolder(token: String): String = folders.backups(token, create = true)!!

    // ── HTTP plumbing ─────────────────────────────────────────────────────

    private fun open(url: String, method: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 30_000
            readTimeout = 120_000
        }

    private fun readResponse(conn: HttpURLConnection): String = try {
        val code = conn.responseCode
        if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IOException("Drive request failed ($code): ${error.take(200)}")
        }
    } finally {
        conn.disconnect()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Drive timestamps are RFC 3339 (`2026-07-06T02:00:00.000Z`). */
    private fun parseRfc3339(value: String): Long {
        if (value.isBlank()) return 0L
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        for (pattern in patterns) {
            runCatching {
                java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(value)
            }.getOrNull()?.let { return it.time }
        }
        return 0L
    }

    companion object {
    }
}
