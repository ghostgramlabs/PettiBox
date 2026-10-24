package com.ghostgramlabs.pettibox.data.sync

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ghostgramlabs.pettibox.data.drive.DriveAuth
import com.ghostgramlabs.pettibox.data.local.AppDatabase
import com.ghostgramlabs.pettibox.data.local.AttachmentDao
import com.ghostgramlabs.pettibox.data.local.SaveDao
import com.ghostgramlabs.pettibox.data.local.SyncDao
import com.ghostgramlabs.pettibox.data.local.SyncFileEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.ghostgramlabs.pettibox.data.local.OcrStatusEntity
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Moves photos, PDFs and other files between this phone and
 * "PettiBox/Sync/files/", separately from the text sync so text never waits
 * for them. Files are named by their sha256 (plus extension), uploaded once,
 * and checked against that hash when they come down. The only removal is
 * [cleanUp]: files in Drive that no save anywhere has used for 30 days.
 *
 * Two jobs: card pictures only (tens of KB each, on any connection), and
 * everything (photos, PDFs, files) on the network the user allowed.
 */
@HiltWorker
class FileSyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase,
    private val syncDao: SyncDao,
    private val saveDao: SaveDao,
    private val attachmentDao: AttachmentDao,
    private val library: LocalLibrary,
    private val client: DriveSyncClient,
    private val auth: DriveAuth,
    private val prefs: SyncPreferences,
    private val engine: SyncEngine,
    private val attachmentStore: com.ghostgramlabs.pettibox.data.util.AttachmentStore,
    private val ocrPreferences: com.ghostgramlabs.pettibox.data.preferences.OcrPreferences
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!prefs.isEnabled()) return Result.success()
        val token = auth.silentAccessToken() ?: run {
            prefs.setFilesProblem("Photos and files are waiting: reconnect Google Drive in Settings.")
            return Result.success()
        }
        return try {
            // Only the Google account this library syncs with (the sync round
            // explains a mismatch); and nothing goes into a library someone
            // just deleted from Drive (the next round turns sync off).
            val owner = prefs.status.first().accountEmail
            val account = client.accountEmail(token) // throws if it can't be told: tried again later
            if (owner.isBlank()) {
                prefs.setFilesProblem("Photos and files are sent once the first sync has finished.")
                return Result.success()
            }
            if (!account.equals(owner, ignoreCase = true)) {
                prefs.setFilesProblem("Photos and files aren't sent: Google Drive is signed in as $account, not $owner.")
                return Result.success()
            }
            val syncFolder = client.syncFolder(token)
            val known = prefs.knownReset() ?: run {
                prefs.setFilesProblem("Photos and files are sent once the first sync has finished.")
                return Result.success()
            }
            resetCheck = { (client.readResetMarker(token, syncFolder) ?: "") == known }
            if (!stillSameLibrary(force = true)) return Result.success()
            val folder = client.filesFolder(token, syncFolder)
            val listed = client.listStoredFiles(token, folder)
            val stored = listed.associateBy { it.name.substringBefore('.') }
            val thumbsOnly = inputData.getBoolean(KEY_THUMBS_ONLY, false)
            val unreadable = upload(token, folder, stored.keys, thumbsOnly)
            download(token, syncFolder, folder, stored, thumbsOnly)
            if (!thumbsOnly) {
                cleanUp(token, syncFolder, folder, listed)
                prefs.setFilesWaiting("")
                // Said as it is: done only when nothing is left either way.
                val missing = missingHere
                if (unreadable == 0 && missing == 0) prefs.setFilesProblem("")
                if (missing > 0) {
                    prefs.setFilesProblem(
                        "$missing file${if (missing == 1) " is" else "s are"} missing on this phone, so " +
                            "${if (missing == 1) "it" else "they"} can't be sent. Open the save and add the file again."
                    )
                }
                val waiting = syncDao.pendingFiles().size
                prefs.setFilesStatus(
                    if (waiting > 0) "Library synced · $waiting file${if (waiting == 1) "" else "s"} waiting for another device to send ${if (waiting == 1) "it" else "them"} · checked " + clockTime(System.currentTimeMillis())
                    else if (unreadable == 0 && missing == 0) "All photos and files are in Google Drive · checked " + clockTime(System.currentTimeMillis())
                    else "Photos and files checked " + clockTime(System.currentTimeMillis())
                )
            }
            Result.success()
        } catch (e: DriveSyncException) {
            android.util.Log.w("PettiBoxSync", "file sync failed", e)
            if (e.transient && runAttemptCount < 5) {
                prefs.setFilesProblem("Couldn't reach Google Drive while sending photos and files (${e.status.takeIf { it > 0 } ?: "no connection"}). Trying again by itself shortly.")
                Result.retry()
            } else {
                prefs.setFilesProblem("Photos and files couldn't be sent: ${e.message}. Tap Sync now to try again.")
                Result.success()
            }
        } catch (e: LibraryReset) {
            Result.success() // the next sync round turns sync off and says why
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never stop quietly: say what happened, and try again.
            android.util.Log.w("PettiBoxSync", "file sync stopped", e)
            prefs.setFilesProblem("Photos and files stopped sending: ${e.message ?: e.javaClass.simpleName}. Trying again shortly.")
            if (runAttemptCount < 5) Result.retry() else Result.success()
        }
    }

    private fun clockTime(at: Long): String =
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))

    private class LibraryReset : Exception()

    /** Files saves here point at that aren't on this phone (counted by [upload]). */
    private var missingHere = 0

    /** Whether the synced library is still the one this job started with (no "Delete synced data" since). */
    private var resetCheck: suspend () -> Boolean = { true }
    private var checkedAt = 0L

    /** Checked at the start and again every half minute during a long batch. */
    private suspend fun stillSameLibrary(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - checkedAt < RESET_RECHECK_MS) return true
        checkedAt = now
        return resetCheck()
    }

    /** Every file this phone's saves use that Drive doesn't have yet. */
    /** Returns how many files couldn't be read (and so weren't sent). */
    private suspend fun upload(token: String, folder: String, inDrive: Set<String>, thumbsOnly: Boolean): Int {
        val files = if (thumbsOnly) emptyList()
        else saveDao.allForExport().mapNotNull { it.localUri } + attachmentDao.allForExport().map { it.uri }
        // A file shared in from another app may still be that app's (content://)
        // if copying it in failed when it was saved: take it in now, so it's
        // kept on this phone and can be sent. One that can't be read any more
        // is reported, not skipped quietly.
        var unreadable = 0
        val uris = (syncDao.localThumbnails() + files).distinct().mapNotNull { uri ->
            when {
                uri.startsWith("file:") -> uri
                uri.startsWith("content:") -> adopt(uri) ?: run { unreadable++; null }
                else -> null
            }
        }
        if (!thumbsOnly && unreadable > 0) {
            prefs.setFilesProblem("$unreadable file${if (unreadable == 1) "" else "s"} can't be read on this phone any more (the app it came from no longer allows it), so it can't be sent.")
        }
        val sent = HashSet(inDrive)
        // How many still need sending, said in Settings while it happens.
        val toSend = uris.count { uri -> library.shaOf(uri)?.let { it !in inDrive } == true }
        var done = 0
        if (!thumbsOnly && toSend > 0) prefs.setFilesStatus("Sending $toSend photo${if (toSend == 1) "" else "s"} and files to Google Drive…")
        missingHere = 0
        val fileSet = files.toSet()
        for (uri in uris) {
            // A file a save points at that isn't on this phone (deleted by
            // another app, say) can't be sent: counted, and said, not skipped quietly.
            val file = Uri.parse(uri).path?.let(::File)?.takeIf { it.exists() }
            val sha = file?.let { library.shaOf(uri) }
            if (file == null || sha == null) {
                if (uri in fileSet) missingHere++ // a card picture is just made again
                continue
            }
            if (!sent.add(sha)) continue
            val ext = file.extension.lowercase().ifBlank { "bin" }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            if (!stillSameLibrary()) throw LibraryReset()
            client.exclusiveFile(sha) {
                // The other job may have sent it while this one waited.
                if (!client.recentlySent(folder, sha)) {
                    client.uploadFile(token, folder, "$sha.$ext", file, mime, prefs.uploadSession(sha)) { prefs.setUploadSession(sha, it) }
                    client.markSent(folder, sha)
                }
            }
            done++
            if (!thumbsOnly && toSend > 1) prefs.setFilesStatus("Sending photos and files to Google Drive… $done of $toSend")
        }
        return unreadable
    }

    /** Fills placeholders for files other devices added, three at a time. */
    private suspend fun download(token: String, syncFolder: String, folder: String, inDrive: Map<String, DriveSyncFile>, thumbsOnly: Boolean) = coroutineScope {
        val pending = (syncDao.pendingThumbnails() + if (thumbsOnly) emptyList() else syncDao.pendingFiles()).distinct()
        if (pending.isEmpty()) return@coroutineScope
        val dir = File(applicationContext.filesDir, "attachments").apply { mkdirs() }
        // A part-downloaded file nobody came back for in a week.
        val stale = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        dir.listFiles { f -> f.name.endsWith(".part") && f.lastModified() < stale }?.forEach { it.delete() }
        val gate = Semaphore(3)
        pending.map { placeholder ->
            async {
                gate.withPermit {
                    val sha = placeholder.removePrefix(PENDING_FILE_SCHEME)
                    client.exclusiveFile(sha) {
                        // The other job may have brought it down while this one waited.
                        if (!syncDao.placeholderInUse(placeholder)) return@exclusiveFile
                        // In files-removed (or Drive's trash, from an older version) although
                        // a save needs it: take it straight back out.
                        val remote = inDrive[sha] ?: restoreRemoved(token, syncFolder, folder, sha)
                            ?: client.restoreTrashed(token, folder, sha)
                            ?: return@exclusiveFile // not uploaded yet; next time
                        val ext = remote.name.substringAfter('.', "bin")
                        val target = File(dir, "${UUID.randomUUID()}.$ext")
                        client.downloadFile(token, remote.id, sha, target)
                        val uri = target.toUri().toString()
                        val readText = ocrPreferences.autoScan.first()
                        val toRead = db.withTransaction {
                            syncDao.beginApplying() // swapping in the file is not an edit
                            syncDao.putFile(SyncFileEntity(uri, sha))
                            syncDao.fillAttachment(placeholder, uri)
                            syncDao.fillMainFile(placeholder, uri)
                            syncDao.fillThumbnail(placeholder, uri)
                            syncDao.endApplying()
                            // Read its text here too, noted in this same transaction: if the
                            // app stops before the job is queued, app start queues it.
                            if (readText) textToRead(uri) else emptyList()
                        }
                        for (s in toRead) {
                            com.ghostgramlabs.pettibox.data.ocr.OcrWorker.enqueue(applicationContext, s.itemId, s.uri, s.attachmentId, s.pdf)
                        }
                    }
                }
            }
        }.awaitAll()
    }

    /**
     * About once a day, removes shared files nothing has used for 30 days
     * (rules in [DriveFileCleanup]). Skipped unless the last sync round read
     * every device's copy cleanly, recently, with nothing left waiting.
     */
    /**
     * Copies a file another app shared (content://) into this app's own
     * storage and points the saves at the copy. Not an edit: it's the same
     * file. Null if it can't be read.
     */
    private suspend fun adopt(uri: String): String? {
        val copy = attachmentStore.ingest(Uri.parse(uri)) ?: return null
        val sha = library.shaOf(copy) ?: return null
        db.withTransaction {
            syncDao.beginApplying()
            syncDao.putFile(SyncFileEntity(copy, sha))
            syncDao.fillAttachment(uri, copy)
            syncDao.fillMainFile(uri, copy)
            syncDao.fillThumbnail(uri, copy)
            syncDao.endApplying()
        }
        return copy
    }

    /**
     * The images and PDFs that are the file just arrived at [uri], recorded
     * as waiting to be read (call inside the transaction that swaps the file
     * in). Read in this phone's languages; the other phone's text stays
     * unless this reading is better
     * ([com.ghostgramlabs.pettibox.data.ocr.TextReader.improves]).
     */
    private suspend fun textToRead(uri: String): List<OcrStatusEntity> {
        val rows = syncDao.textSourceItems(uri).map {
            OcrStatusEntity(OcrStatusEntity.sourceOf(it.id, null), it.id, null, uri, it.contentType == "PDF", OcrStatusEntity.PENDING)
        } + syncDao.textSourceAttachments(uri).map {
            OcrStatusEntity(OcrStatusEntity.sourceOf(it.itemId, it.id), it.itemId, it.id, uri, it.kind == "PDF", OcrStatusEntity.PENDING)
        }
        if (rows.isNotEmpty()) db.ocrStatusDao().put(rows)
        return rows
    }

    /** A file in files-removed that a save here needs: moved back into files/. */
    private suspend fun restoreRemoved(token: String, syncFolder: String, folder: String, sha: String): DriveSyncFile? {
        val removedFolder = client.removedFolder(token, syncFolder)
        val f = client.listStoredFiles(token, removedFolder).firstOrNull { it.name.substringBefore('.') == sha } ?: return null
        client.moveFile(token, f.id, to = folder, from = removedFolder)
        return f
    }

    /**
     * About once a day: files no device's copy uses move to files-removed, and
     * after 30 more days there are deleted; long-settled deletions are
     * stripped to their markers (rules in [DriveFileCleanup]). Every device's
     * own copy in Drive is read for this, so a device that is behind or
     * offline keeps what it still uses. Nothing happens unless every copy
     * could be read, and nothing waits to be sent or applied here.
     */
    private suspend fun cleanUp(token: String, syncFolder: String, filesFolder: String, listed: List<DriveSyncFile>) {
        val now = System.currentTimeMillis()
        if (now - prefs.fileCleanupAt() < CLEANUP_EVERY_MS) return
        if (syncDao.inboxCount() > 0 || syncDao.outboxCount() > 0) return
        val epoch = prefs.knownReset() ?: return
        val copies = publishedCopies(token, syncFolder, epoch) ?: return
        val records = syncDao.metaOfKind(SyncRecord.KIND_ITEM).map {
            // A copy here that can't be read could use any file: then remove nothing.
            runCatching { SyncCodec.decodeString(it.recordJson) }.getOrNull() ?: return
        }
        val used = HashSet<String>()
        for (p in copies.values) used += p.refs
        used += DriveFileCleanup.referenced(records.asSequence())
        // Whatever this phone holds is kept too, even if not synced yet.
        val local = saveDao.allForExport().flatMap { listOfNotNull(it.localUri, it.thumbnailUri) } +
            attachmentDao.allForExport().map { it.uri }
        for (uri in local.distinct()) {
            if (uri.startsWith("http")) continue
            library.shaOf(uri)?.let { used += it }
        }
        val removedFolder = client.removedFolder(token, syncFolder)
        val removed = client.listStoredFiles(token, removedFolder)
        val plan = DriveFileCleanup.plan(
            stored = listed.associate { it.id to it.name },
            removed = removed.associate { it.id to it.name },
            used = used,
            unusedSince = prefs.unusedFiles(),
            quarantinedAt = prefs.quarantinedFiles(),
            now = now
        )
        for (id in plan.restore) client.moveFile(token, id, to = filesFolder, from = removedFolder)
        for (id in plan.quarantine) client.moveFile(token, id, to = removedFolder, from = filesFolder)
        for (id in plan.purge) {
            try {
                client.delete(token, id)
            } catch (e: DriveSyncException) {
                if (e.status != 404) throw e // already gone (another phone) is fine
            }
        }
        prefs.setUnusedFiles(plan.unusedSince)
        prefs.setQuarantinedFiles(plan.quarantinedAt)
        prefs.setFileCleanupAt(now)
        engine.compact(copies, now)
    }

    /**
     * What every device file in Drive says (this phone's too), by file name.
     * Remembered between runs by checksum, so only files that changed are
     * read again. Null if any of this library's files can't be read (damaged,
     * or a newer format): then nobody can tell what it still uses.
     */
    private suspend fun publishedCopies(token: String, syncFolder: String, epoch: String): Map<String, DriveFileCleanup.Published>? {
        val cache = PublishedCopies(applicationContext)
        val known = cache.load()
        val out = HashMap<String, DriveFileCleanup.Published>()
        for (f in client.listDeviceFiles(token, syncFolder)) {
            if (DriveSyncClient.deviceOf(f.name) == null) continue
            val hit = known[f.name]?.takeIf { f.md5.isNotBlank() && it.md5 == f.md5 }
            if (hit != null) {
                out[f.name] = hit
                continue
            }
            val shard = try {
                SyncCodec.decodeShard(client.downloadShard(token, f))
            } catch (e: SyncFormatException) {
                return null
            } ?: return null
            if (shard.epoch != epoch) continue // from before a reset: not part of this library
            out[f.name] = DriveFileCleanup.publishedOf(f.md5, shard.records)
        }
        cache.save(out)
        return out
    }

    companion object {
        private const val RESET_RECHECK_MS = 30_000L
        private const val CLEANUP_EVERY_MS = 20L * 60 * 60 * 1000
        private const val NAME = "sync_files"
        private const val THUMBS = "sync_thumbs"
        private const val KEY_THUMBS_ONLY = "thumbs_only"

        /**
         * Card pictures now (any network); everything else on the network the
         * user allowed. A job already running when this is asked for has its
         * list of files from when it started, so one more run is queued after
         * it (files saved since aren't left for the next time the app opens);
         * one already waiting to start will see everything, so it's left alone.
         */
        suspend fun enqueue(context: Context, wifiOnly: Boolean) {
            fun request(thumbsOnly: Boolean, network: NetworkType) = OneTimeWorkRequestBuilder<FileSyncWorker>()
                .setInputData(androidx.work.workDataOf(KEY_THUMBS_ONLY to thumbsOnly))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            val wm = WorkManager.getInstance(context)
            suspend fun queue(name: String, work: androidx.work.OneTimeWorkRequest) {
                val states = wm.getWorkInfosForUniqueWorkFlow(name).first().map { it.state }
                if (states.any { it == androidx.work.WorkInfo.State.ENQUEUED || it == androidx.work.WorkInfo.State.BLOCKED }) return
                wm.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
            }
            queue(THUMBS, request(true, NetworkType.CONNECTED))
            queue(NAME, request(false, if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED))
        }

        /** "Send on mobile data" / "Send anyway": everything, at once, on any connection (the person asked). */
        fun sendNow(context: Context) {
            val work = OneTimeWorkRequestBuilder<FileSyncWorker>()
                .setInputData(androidx.work.workDataOf(KEY_THUMBS_ONLY to false))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }

        /** Low battery and not charging: the background job waits (Android's rule for it). */
        fun batteryLow(context: Context): Boolean {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0..15 && !bm.isCharging
        }

        /** Whether the phone is on a metered connection (mobile data, a hotspot) right now. */
        fun onMeteredNetwork(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            return cm.activeNetwork != null && cm.isActiveNetworkMetered
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            WorkManager.getInstance(context).cancelUniqueWork(THUMBS)
        }
    }
}
