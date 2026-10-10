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
    private val engine: SyncEngine
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
            if (owner.isBlank() || !account.equals(owner, ignoreCase = true)) return Result.success()
            val syncFolder = client.syncFolder(token)
            val known = prefs.knownReset() ?: return Result.success() // no round has run yet
            resetCheck = { (client.readResetMarker(token, syncFolder) ?: "") == known }
            if (!stillSameLibrary(force = true)) return Result.success()
            val folder = client.filesFolder(token, syncFolder)
            val listed = client.listStoredFiles(token, folder)
            val stored = listed.associateBy { it.name.substringBefore('.') }
            val thumbsOnly = inputData.getBoolean(KEY_THUMBS_ONLY, false)
            upload(token, folder, stored.keys, thumbsOnly)
            download(token, syncFolder, folder, stored, thumbsOnly)
            if (!thumbsOnly) cleanUp(token, syncFolder, folder, listed)
            prefs.setFilesProblem("")
            Result.success()
        } catch (e: DriveSyncException) {
            if (e.transient && runAttemptCount < 5) Result.retry() else {
                prefs.setFilesProblem("Some photos and files couldn't be sent or fetched. They'll be tried again on the next sync.")
                Result.success()
            }
        } catch (e: LibraryReset) {
            Result.success() // the next sync round turns sync off and says why
        }
    }

    private class LibraryReset : Exception()

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
    private suspend fun upload(token: String, folder: String, inDrive: Set<String>, thumbsOnly: Boolean) {
        val files = if (thumbsOnly) emptyList()
        else saveDao.allForExport().mapNotNull { it.localUri } + attachmentDao.allForExport().map { it.uri }
        val uris = (syncDao.localThumbnails() + files)
            .filter { it.startsWith("file:") }
            .distinct()
        val sent = HashSet(inDrive)
        for (uri in uris) {
            val sha = library.shaOf(uri) ?: continue
            if (!sent.add(sha)) continue
            val file = Uri.parse(uri).path?.let(::File)?.takeIf { it.exists() } ?: continue
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
        }
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
                        db.withTransaction {
                            syncDao.beginApplying() // swapping in the file is not an edit
                            syncDao.putFile(SyncFileEntity(uri, sha))
                            syncDao.fillAttachment(placeholder, uri)
                            syncDao.fillMainFile(placeholder, uri)
                            syncDao.fillThumbnail(placeholder, uri)
                            syncDao.endApplying()
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

        /** Card pictures now (any network); everything else on the network the user allowed. */
        fun enqueue(context: Context, wifiOnly: Boolean) {
            fun request(thumbsOnly: Boolean, network: NetworkType) = OneTimeWorkRequestBuilder<FileSyncWorker>()
                .setInputData(androidx.work.workDataOf(KEY_THUMBS_ONLY to thumbsOnly))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniqueWork(THUMBS, ExistingWorkPolicy.KEEP, request(true, NetworkType.CONNECTED))
            wm.enqueueUniqueWork(
                NAME, ExistingWorkPolicy.KEEP,
                request(false, if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            WorkManager.getInstance(context).cancelUniqueWork(THUMBS)
        }
    }
}
