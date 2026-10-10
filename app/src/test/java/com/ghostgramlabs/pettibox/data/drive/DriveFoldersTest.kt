package com.ghostgramlabs.pettibox.data.drive

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * DriveFolders against a local stand-in for the Drive files API. This is the
 * code that moves every existing user's "PettiBox Backups" folder into
 * PettiBox/Backups on update, so it's checked against real HTTP calls.
 */
class DriveFoldersTest {

    private data class F(val id: String, var name: String, val folder: Boolean, var parents: MutableList<String>, val created: Int, var trashed: Boolean = false, var mergedInto: String? = null)

    private val files = LinkedHashMap<String, F>()
    private var seq = 0
    private lateinit var server: ServerSocket
    private lateinit var folders: DriveFolders
    private var lateIn: String? = null
    private var trashHook: ((String) -> Unit)? = null
    private var lateLooks = 0

    private fun make(name: String, parent: String = "root", folder: Boolean = true): String {
        val id = "f${++seq}"
        files[id] = F(id, name, folder, mutableListOf(parent), seq)
        return id
    }

    private fun path(f: F): String = (files[f.parents.first()]?.let { path(it) + "/" } ?: "") + f.name
    /** Anything in a trashed folder counts as trashed, as in Drive. */
    private fun inTrash(f: F): Boolean = f.trashed || f.parents.any { p -> files[p]?.let(::inTrash) == true }
    private fun tree() = files.values.filterNot(::inTrash).map(::path).sorted()

    private fun matches(f: F, q: String) = q.split(" and ").all { term ->
        Regex("^name = '(.*)'$").find(term)?.let { return@all f.name == it.groupValues[1] }
        Regex("^'(.*)' in parents$").find(term)?.let { return@all it.groupValues[1] in f.parents }
        if (term == "mimeType = 'application/vnd.google-apps.folder'") return@all f.folder
        if (term == "trashed = false") return@all !inTrash(f)
        if (term == "trashed = true") return@all inTrash(f)
        error("unsupported query term: $term")
    }

    /** Handles one request; returns (status, body). */
    private fun handle(method: String, target: String, body: String): Pair<Int, String> {
        val uri = URI(target)
        val query = uri.rawQuery.orEmpty().split("&").filter { it.contains("=") }
            .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        val path = uri.path
        return when {
            path == "/files" && method == "GET" -> {
                val q = query["q"].orEmpty()
                lateIn?.let { b ->
                    if (q == "'$b' in parents and trashed = false") {
                        lateLooks++
                        if (lateLooks == 2) make("late.zip", b, folder = false)
                    }
                }
                val list = files.values.filter { matches(it, query["q"].orEmpty()) }.sortedBy { it.created }
                200 to JSONObject().put("files", JSONArray().apply {
                    list.forEach {
                        put(JSONObject().put("id", it.id).put("parents", JSONArray(it.parents)).put("name", it.name)
                            .put("mimeType", if (it.folder) "application/vnd.google-apps.folder" else "application/octet-stream")
                            .put("explicitlyTrashed", it.trashed)
                            .apply { it.mergedInto?.let { m -> put("appProperties", JSONObject().put("pettiboxMergedInto", m)) } })
                    }
                }).toString()
            }
            path == "/files" && method == "POST" -> {
                val meta = JSONObject(body)
                200 to JSONObject().put("id", make(meta.getString("name"), meta.getJSONArray("parents").getString(0))).toString()
            }
            path.startsWith("/files/") && method == "PATCH" -> {
                val f = files.getValue(path.removePrefix("/files/"))
                val remove = query["removeParents"].orEmpty().split(",").filter { it.isNotBlank() }
                f.parents = (f.parents.filterNot { it in remove } + listOfNotNull(query["addParents"])).toMutableList()
                val meta = JSONObject(body.ifBlank { "{}" })
                meta.optString("name").takeIf { it.isNotBlank() }?.let { f.name = it }
                if (meta.has("trashed")) {
                    f.trashed = meta.getBoolean("trashed")
                    if (f.trashed) trashHook?.invoke(f.id)
                }
                meta.optJSONObject("appProperties")?.optString("pettiboxMergedInto")?.takeIf { it.isNotBlank() }?.let { f.mergedInto = it }
                200 to JSONObject().put("id", f.id).toString()
            }
            path.startsWith("/files/") && method == "GET" -> {
                val f = files[path.removePrefix("/files/")] ?: return 404 to "{}"
                200 to JSONObject().put("id", f.id).put("trashed", inTrash(f)).toString()
            }
            path.startsWith("/files/") && method == "DELETE" -> {
                fun drop(id: String) {
                    files.values.filter { id in it.parents }.map { it.id }.forEach(::drop)
                    files.remove(id)
                }
                drop(path.removePrefix("/files/"))
                200 to "{}"
            }
            else -> 500 to "unhandled $method $path"
        }
    }

    /** A minimal HTTP/1.1 server on a socket: one request per connection. */
    @Before fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { s ->
                    val input = s.getInputStream().buffered()
                    fun line(): String {
                        val sb = StringBuilder()
                        while (true) {
                            val c = input.read()
                            if (c < 0 || c == 10) break // newline
                            if (c != 13) sb.append(c.toChar()) // skip carriage return
                        }
                        return sb.toString()
                    }
                    val (method0, target) = line().split(" ").let { it[0] to it[1] }
                    val headers = HashMap<String, String>()
                    while (true) {
                        val h = line()
                        if (h.isEmpty()) break
                        headers[h.substringBefore(":").trim().lowercase()] = h.substringAfter(":").trim()
                    }
                    val length = headers["content-length"]?.toInt() ?: 0
                    val body = ByteArray(length).also { var n = 0; while (n < length) n += input.read(it, n, length - n) }
                    val method = headers["x-http-method-override"] ?: method0
                    val (code, out) = synchronized(files) { handle(method, target, body.toString(Charsets.UTF_8)) }
                    val bytes = out.toByteArray()
                    s.getOutputStream().apply {
                        val crlf = "\r\n"
                        write(
                            ("HTTP/1.1 $code X" + crlf + "Content-Type: application/json" + crlf +
                                "Content-Length: ${bytes.size}" + crlf + "Connection: close" + crlf + crlf).toByteArray()
                        )
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        folders = DriveFolders("http://127.0.0.1:${server.localPort}")
    }

    @After fun stop() = server.close()


    @Test fun `a fresh Drive gets PettiBox with Backups and Sync inside`() = runBlocking {
        folders.backups("t", create = true)
        folders.sync("t")
        assertEquals(listOf("PettiBox", "PettiBox/Backups", "PettiBox/Sync"), tree())
    }

    @Test fun `listing backups on a fresh Drive makes nothing`() = runBlocking {
        assertEquals(null, folders.backups("t", create = false))
        assertEquals(emptyList<String>(), tree())
    }

    @Test fun `an existing PettiBox Backups folder moves in with every backup`() = runBlocking {
        val old = make("PettiBox Backups")
        make("pettibox-auto-backup-1.zip", old, folder = false)
        make("pettibox-auto-backup-2.zip", old, folder = false)
        val id = folders.backups("t", create = false)
        assertEquals(old, id) // same folder, moved and renamed: nothing re-uploaded
        assertEquals(
            listOf("PettiBox", "PettiBox/Backups", "PettiBox/Backups/pettibox-auto-backup-1.zip", "PettiBox/Backups/pettibox-auto-backup-2.zip"),
            tree()
        )
    }

    @Test fun `an old folder an older app version made again is folded in`() = runBlocking {
        folders.backups("t1", create = true)
        val again = make("PettiBox Backups")
        make("pettibox-auto-backup-3.zip", again, folder = false)
        folders.backups("t2", create = true) // a later token: looks again
        assertEquals(listOf("PettiBox", "PettiBox/Backups", "PettiBox/Backups/pettibox-auto-backup-3.zip"), tree())
    }

    @Test fun `two PettiBox folders made at once are merged into the oldest`() = runBlocking {
        val a = make("PettiBox")
        val b = make("PettiBox")
        make("pettibox-auto-backup-a.zip", make("Backups", a), folder = false)
        make("pettibox-auto-backup-b.zip", make("Backups", b), folder = false)
        folders.backups("t", create = true)
        assertEquals(
            listOf("PettiBox", "PettiBox/Backups", "PettiBox/Backups/pettibox-auto-backup-a.zip", "PettiBox/Backups/pettibox-auto-backup-b.zip"),
            tree()
        )
        assertTrue(files.getValue(b).trashed) // trashed once empty (recoverable), never deleted
    }

    @Test fun `two files folders under Sync are merged so every photo is found`() = runBlocking {
        val sync = folders.sync("t")
        val first = make("files", sync)
        val second = make("files", sync)
        make("${"ab".repeat(32)}.jpg", second, folder = false)
        val id = folders.files("t", sync)
        assertEquals(first, id)
        assertTrue(tree().contains("PettiBox/Sync/files/${"ab".repeat(32)}.jpg"))
    }

    @Test fun `a photo put in a duplicate files folder as it is trashed is moved back`() = runBlocking {
        val sync = folders.sync("t")
        val first = make("files", sync)
        val second = make("files", sync)
        val sha = "cd".repeat(32)
        trashHook = { id -> if (id == second) { trashHook = null; make("$sha.jpg", second, folder = false) } }
        val id = folders.files("t", sync)
        assertEquals(first, id)
        assertTrue(tree().contains("PettiBox/Sync/files/$sha.jpg"))
    }

    @Test fun `a folder that gets something new while being merged is kept`() = runBlocking {
        val a = make("PettiBox")
        val b = make("PettiBox")
        make("one.zip", b, folder = false)
        // Another device adds a file to b after it was emptied but before it's trashed.
        lateIn = b
        folders.backups("t", create = true)
        assertFalse(files.getValue(b).trashed) // not empty on the fresh look: left for next time
        assertTrue(files.values.any { it.name == "late.zip" })
    }
}
