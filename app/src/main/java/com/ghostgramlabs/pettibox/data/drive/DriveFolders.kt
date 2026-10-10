package com.ghostgramlabs.pettibox.data.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything PettiBox keeps in Google Drive lives under one folder:
 *
 *   My Drive/PettiBox/Backups/   nightly backup zips
 *   My Drive/PettiBox/Sync/      sync files (and Sync/files/ for photos and files)
 *
 * drive.file only shows PettiBox folders it made itself, so a folder the user
 * happens to call "PettiBox" is never mixed in, and the location can't be
 * user-chosen (a picked folder would need a much broader permission).
 *
 * Older versions made "PettiBox Backups" and "PettiBox Sync" at the top of
 * My Drive; they're moved in (renamed, contents intact) the first time.
 * When two devices create a folder at the same moment, everyone settles on
 * the oldest and moves the other's contents into it, so nothing is split.
 * The Chrome extension (extension/drive.js) follows the same rules.
 */
@Singleton
class DriveFolders(private val api: String) {

    @Inject constructor() : this("https://www.googleapis.com/drive/v3")

    private val lock = Mutex()
    private val cache = HashMap<String, String>() // name -> id, per token's account

    suspend fun backups(token: String, create: Boolean): String? = resolve(token, BACKUPS, LEGACY_BACKUPS, create)

    suspend fun sync(token: String): String = resolve(token, SYNC, LEGACY_SYNC, create = true)!!

    /**
     * PettiBox/Sync/files, made if missing. Two devices may each make one at
     * the same moment: they're merged into the oldest, so every device looks
     * in the same place for every photo and file.
     */
    suspend fun files(token: String, syncFolder: String): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = "$token|$syncFolder/$FILES"
            cache[key]?.takeIf { inUse(token, it) }?.let { return@withLock it }
            val id = settle(token, FILES, syncFolder)
                ?: createFolder(token, FILES, syncFolder).let { settle(token, FILES, syncFolder) ?: it }
            id.also { cache[key] = it }
        }
    }

    /** False once a folder was trashed (merged into another by some device) or is gone. */
    private fun inUse(token: String, id: String): Boolean = try {
        !JSONObject(readResponse(open("$api/files/$id?fields=trashed", "GET", token))).optBoolean("trashed", false)
    } catch (e: IOException) {
        if ((e as? DriveHttpException)?.status == 404) false else throw e
    }

    private suspend fun resolve(token: String, name: String, legacy: String, create: Boolean): String? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                // Still the folder in use? Another device may have merged it away.
                cache["$token|$name"]?.takeIf { inUse(token, it) }?.let { return@withLock it }
                // Listing (create = false) must still find an older top-level
                // folder: a user restoring before their first new backup has
                // all their backups there. Then the PettiBox folder is made to
                // move it into.
                val root = root(token, create || legacyFolders(token, legacy).isNotEmpty()) ?: return@withLock null
                val current = settle(token, name, root)
                val child = if (current != null) {
                    // An older app version on another device may have made the
                    // old top-level folder again: fold its contents in.
                    for ((oldId, _) in legacyFolders(token, legacy)) mergeInto(token, from = oldId, into = current)
                    current
                } else {
                    moveLegacy(token, legacy, name, root)
                        ?: if (create) createFolder(token, name, root).let { settle(token, name, root) ?: it } else null
                }
                child?.also { cache["$token|$name"] = it }
            }
        }

    private fun root(token: String, create: Boolean): String? {
        cache["$token|$ROOT"]?.let { return it }
        val found = settle(token, ROOT, "root")
            ?: if (create) createFolder(token, ROOT, "root").let { settle(token, ROOT, "root") ?: it } else null
        return found?.also { cache["$token|$ROOT"] = it }
    }

    /** An old top-level folder ("PettiBox Sync"…) moved into PettiBox and renamed. */
    private fun legacyFolders(token: String, legacy: String) =
        folders(token, "name = '$legacy' and mimeType = '$FOLDER_MIME' and trashed = false")

    private fun moveLegacy(token: String, legacy: String, name: String, root: String): String? {
        val old = legacyFolders(token, legacy)
        if (old.isEmpty()) return null
        val keep = old.first()
        patch(token, keep.first, JSONObject().put("name", name), addParent = root, removeParents = keep.second)
        // More than one old folder (a past race): fold the rest in.
        for (extra in old.drop(1)) mergeInto(token, from = extra.first, into = keep.first)
        return keep.first
    }

    /**
     * The oldest folder called [name] in [parent]; any others have their
     * contents moved into it, and anything that reached a retired one late is
     * brought back (see [drain]).
     */
    private fun settle(token: String, name: String, parent: String): String? {
        val found = children(token, name, parent)
        if (found.isEmpty()) return null
        val keep = found.first().first
        for (extra in found.drop(1)) mergeInto(token, from = extra.first, into = keep)
        drain(token, keep, name, parent)
        return keep
    }

    /**
     * Moves into [into] anything put in a folder PettiBox merged into it after
     * that folder was trashed (a device still using it at that moment). Only
     * folders PettiBox retired itself are drained, never ones a person trashed.
     */
    private fun drain(token: String, into: String, name: String, parent: String) {
        val retired = entries(token, "name = '$name' and mimeType = '$FOLDER_MIME' and trashed = true and '$parent' in parents")
            .filter { it.mergedInto == into }
        for (r in retired) {
            val existing = entries(token, "'$into' in parents and mimeType = '$FOLDER_MIME' and trashed = false")
            for (c in entries(token, "'${r.id}' in parents")) {
                if (c.explicitlyTrashed) continue
                val same = if (c.folder) existing.firstOrNull { it.name == c.name } else null
                if (same != null) {
                    drain(token, same.id, c.name, r.id)
                    mergeInto(token, from = c.id, into = same.id)
                } else {
                    patch(token, c.id, JSONObject(), addParent = into, removeParents = c.parents)
                }
            }
        }
    }

    /**
     * Moves everything in [from] into [into]. A sub-folder [into] already has
     * (two "files" folders, say) is merged the same way rather than moved in
     * beside it. [from] is never deleted: once a fresh look finds it empty it
     * goes to Drive's trash, so anything a device put there at that moment can
     * still be recovered, and devices stop using it (see [inUse]).
     */
    private fun mergeInto(token: String, from: String, into: String) {
        val existing = entries(token, "'$into' in parents and mimeType = '$FOLDER_MIME' and trashed = false")
        for (f in entries(token, "'$from' in parents and trashed = false")) {
            val same = if (f.folder) existing.firstOrNull { it.name == f.name } else null
            if (same != null) mergeInto(token, from = f.id, into = same.id)
            else patch(token, f.id, JSONObject(), addParent = into, removeParents = f.parents)
        }
        if (entries(token, "'$from' in parents and trashed = false").isNotEmpty()) return // more arrived: next time
        // Labelled, so anything a device puts in it from now on is drained back.
        runCatching {
            patch(token, from, JSONObject().put("trashed", true).put("appProperties", JSONObject().put(MERGED_INTO, into)), addParent = null, removeParents = "")
        }
    }

    private class Entry(
        val id: String, val parents: String, val name: String, val folder: Boolean,
        val explicitlyTrashed: Boolean = false, val mergedInto: String? = null
    )

    /** Files and folders matching [query], oldest first. */
    private fun entries(token: String, query: String): List<Entry> {
        val out = ArrayList<Entry>()
        var page: String? = null
        do {
            val url = "$api/files?q=${enc(query)}&orderBy=createdTime&pageSize=1000" +
                "&fields=${enc("nextPageToken,files(id,parents,name,mimeType,explicitlyTrashed,appProperties)")}" + (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val root = JSONObject(readResponse(open(url, "GET", token)))
            val files = root.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val parents = f.optJSONArray("parents")?.let { p -> (0 until p.length()).joinToString(",") { p.getString(it) } }.orEmpty()
                out += Entry(
                    f.getString("id"), parents, f.optString("name"), f.optString("mimeType") == FOLDER_MIME,
                    explicitlyTrashed = f.optBoolean("explicitlyTrashed", false),
                    mergedInto = f.optJSONObject("appProperties")?.optString(MERGED_INTO)?.ifBlank { null }
                )
            }
            page = root.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return out
    }

    private fun children(token: String, name: String, parent: String) =
        folders(token, "name = '$name' and mimeType = '$FOLDER_MIME' and trashed = false and '$parent' in parents")

    /** (id, comma-separated parents), oldest first. */
    private fun folders(token: String, query: String, foldersOnly: Boolean = true): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var page: String? = null
        do {
            val url = "$api/files?q=${enc(query)}&orderBy=createdTime&pageSize=1000" +
                "&fields=${enc("nextPageToken,files(id,parents)")}" + (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val root = JSONObject(readResponse(open(url, "GET", token)))
            val files = root.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val parents = f.optJSONArray("parents")?.let { p -> (0 until p.length()).joinToString(",") { p.getString(it) } }.orEmpty()
                out += f.getString("id") to parents
            }
            page = root.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return out
    }

    private fun createFolder(token: String, name: String, parent: String): String {
        val conn = open("$api/files?fields=id", "POST", token)
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.doOutput = true
        val body = JSONObject().put("name", name).put("mimeType", FOLDER_MIME).put("parents", JSONArray().put(parent))
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return JSONObject(readResponse(conn)).getString("id")
    }

    private fun patch(token: String, id: String, body: JSONObject, addParent: String?, removeParents: String) {
        val params = listOfNotNull(
            addParent?.let { "addParents=${enc(it)}" },
            removeParents.takeIf { it.isNotBlank() }?.let { "removeParents=${enc(it)}" },
            "fields=id"
        ).joinToString("&")
        val conn = open("$api/files/$id?$params", "PATCH", token)
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        readResponse(conn)
    }

    private fun open(url: String, method: String, token: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (method == "PATCH") {
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        } else {
            conn.requestMethod = method
        }
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        return conn
    }

    private fun readResponse(conn: HttpURLConnection): String = try {
        val code = conn.responseCode
        if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
        else throw DriveHttpException(code, conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200))
    } finally {
        conn.disconnect()
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    /** A failed Drive request; [status] is the HTTP code. */
    class DriveHttpException(val status: Int, detail: String) : IOException("Drive request failed ($status): $detail")

    companion object {
        const val ROOT = "PettiBox"
        const val BACKUPS = "Backups"
        const val SYNC = "Sync"
        const val FILES = "files"
        /** Set on a folder PettiBox trashed after merging it: the folder it went into. */
        private const val MERGED_INTO = "pettiboxMergedInto"
        const val LEGACY_BACKUPS = "PettiBox Backups"
        const val LEGACY_SYNC = "PettiBox Sync"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
