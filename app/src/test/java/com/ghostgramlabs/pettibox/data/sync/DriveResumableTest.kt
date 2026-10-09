package com.ghostgramlabs.pettibox.data.sync

import com.ghostgramlabs.pettibox.data.drive.DriveFolders
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Big files against a local stand-in for Drive's upload and download
 * endpoints that can drop the connection part-way, as weak Wi-Fi does: the
 * next try must carry on, not start over, and end with the exact bytes.
 */
class DriveResumableTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: ServerSocket
    private lateinit var client: DriveSyncClient
    private val base get() = "http://127.0.0.1:${server.localPort}"

    // Upload side
    private val received = ByteArrayOutputStream()
    private var total = -1L
    private var sessions = 0
    private var bytesSent = 0L
    private val liveSessions = HashSet<String>()
    private var piecesSeen = 0
    /** Drop the connection 1000 bytes into this piece (1-based), once. */
    @Volatile private var dropOnPiece: Int? = null

    // Download side
    private var stored = ByteArray(0)
    @Volatile private var dropDownloadAfter: Int? = null
    private val ranges = ArrayList<String?>()

    @Before fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { sock.use { serve(it) } }
            }
        }
        client = DriveSyncClient(DriveFolders("$base/drive"), "$base/drive", "$base/upload", chunkSize = 256 * 1024, resumableFrom = 1)
    }

    @After fun stop() = server.close()

    private fun serve(s: java.net.Socket) {
        val input = s.getInputStream().buffered()
        fun line(): String {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == 10) break
                if (c != 13) sb.append(c.toChar())
            }
            return sb.toString()
        }
        val (method, target) = line().split(" ").let { it[0] to it[1] }
        val headers = HashMap<String, String>()
        while (true) {
            val h = line()
            if (h.isEmpty()) break
            headers[h.substringBefore(":").trim().lowercase()] = h.substringAfter(":").trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val out = s.getOutputStream()
        fun reply(code: Int, extra: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)) {
            val head = StringBuilder("HTTP/1.1 $code X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
            extra.forEach { (k, v) -> head.append("$k: $v\r\n") }
            out.write((head.toString() + "\r\n").toByteArray())
            out.write(body)
            out.flush()
        }
        when {
            method == "POST" && target.startsWith("/upload/files?uploadType=resumable") -> {
                input.readNBytes(length)
                total = headers.getValue("x-upload-content-length").toLong()
                sessions++
                received.reset()
                liveSessions += "/session/$sessions"
                reply(200, mapOf("Location" to "$base/session/$sessions"))
            }
            method == "PUT" && target.startsWith("/session/") -> {
                if (target !in liveSessions) { input.readNBytes(length); reply(404); return }
                val range = headers.getValue("content-range")
                val done = { received.size().toLong() == total }
                val sofar = { if (received.size() == 0) emptyMap() else mapOf("Range" to "bytes=0-${received.size() - 1}") }
                if (range.startsWith("bytes */")) {
                    if (done()) reply(200, body = """{"id":"f1"}""".toByteArray()) else reply(308, sofar())
                    return
                }
                val start = range.removePrefix("bytes ").substringBefore('-').toLong()
                piecesSeen++
                if (dropOnPiece == piecesSeen) {
                    dropOnPiece = null
                    bytesSent += input.readNBytes(1000).size
                    return // the connection just goes
                }
                val piece = input.readNBytes(length)
                bytesSent += piece.size
                if (start == received.size().toLong()) received.write(piece)
                if (done()) reply(200, body = """{"id":"f1"}""".toByteArray()) else reply(308, sofar())
            }
            method == "GET" && target.startsWith("/drive/files/") -> {
                val range = headers["range"]
                synchronized(ranges) { ranges += range }
                val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                if (from >= stored.size && range != null) { reply(416); return }
                val body = stored.copyOfRange(from, stored.size)
                val drop = dropDownloadAfter
                val code = if (range != null) 206 else 200
                if (drop != null) {
                    dropDownloadAfter = null
                    out.write("HTTP/1.1 $code X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body, 0, drop)
                    out.flush()
                    return // cut off mid-file
                }
                reply(code, body = body)
            }
            else -> reply(500)
        }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun a_big_upload_cut_off_part_way_carries_on_from_the_last_piece() = runBlocking {
        val bytes = Random(7).nextBytes(1_000_000) // four pieces of 256 KiB
        val file = tmp.newFile("big.pdf").apply { writeBytes(bytes) }
        var session: String? = null
        suspend fun upload() = client.uploadFile("t", "folder", "x.pdf", file, "application/pdf", session) { session = it }

        dropOnPiece = 3 // two pieces arrive, then the connection drops during the third
        val first = runCatching { upload() }
        assertTrue((first.exceptionOrNull() as DriveSyncException).transient)
        assertTrue(session != null) // kept for the next try
        assertEquals(2 * 256 * 1024, received.size())

        upload() // carries on at the third piece, same session
        assertNull(session) // done: forgotten
        assertEquals(1, sessions)
        assertArrayEquals(bytes, received.toByteArray())
        // Nothing that had arrived was sent again (only the 1000 bytes cut off).
        assertEquals(bytes.size.toLong() + 1000, bytesSent)
    }

    @Test fun an_expired_session_starts_a_fresh_upload() = runBlocking {
        val bytes = Random(8).nextBytes(600_000)
        val file = tmp.newFile("big.bin").apply { writeBytes(bytes) }
        var session: String? = "$base/session/old" // Drive no longer knows it
        client.uploadFile("t", "folder", "y.bin", file, "application/octet-stream", session) { session = it }
        assertEquals(1, sessions)
        assertArrayEquals(bytes, received.toByteArray())
        assertNull(session)
    }

    @Test fun a_small_file_goes_up_in_one_request() = runBlocking {
        val small = DriveSyncClient(DriveFolders("$base/drive"), "$base/drive", "$base/upload")
        val file = tmp.newFile("small.jpg").apply { writeBytes(Random(1).nextBytes(10_000)) }
        runCatching { small.uploadFile("t", "folder", "z.jpg", file, "image/jpeg") }
        assertEquals(0, sessions) // no resumable session for a small file
    }

    @Test fun a_download_cut_off_part_way_asks_only_for_the_rest() = runBlocking {
        stored = Random(9).nextBytes(800_000)
        val dir = tmp.newFolder("attachments")
        val target = File(dir, "out.pdf")
        dropDownloadAfter = 300_000
        val first = runCatching { client.downloadFile("t", "id1", sha(stored), target) }
        assertTrue(first.isFailure)
        assertFalse(target.exists())
        val part = File(dir, "${sha(stored)}.part")
        assertEquals(300_000L, part.length()) // kept

        client.downloadFile("t", "id1", sha(stored), target)
        assertArrayEquals(stored, target.readBytes())
        assertEquals(listOf(null, "bytes=300000-"), ranges)
        assertFalse(part.exists())
    }

    @Test fun a_damaged_partial_download_is_thrown_away() = runBlocking {
        stored = Random(10).nextBytes(100_000)
        val dir = tmp.newFolder("attachments")
        val part = File(dir, "${sha(stored)}.part").apply { writeBytes(ByteArray(5_000) { 1 }) } // not the real start
        val r = runCatching { client.downloadFile("t", "id1", sha(stored), File(dir, "out.bin")) }
        assertTrue(r.isFailure)
        assertFalse(part.exists())
        client.downloadFile("t", "id1", sha(stored), File(dir, "out.bin"))
        assertArrayEquals(stored, File(dir, "out.bin").readBytes())
    }
}
