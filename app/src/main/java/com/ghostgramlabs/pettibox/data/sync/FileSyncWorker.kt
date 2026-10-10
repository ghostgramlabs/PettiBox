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
    private val prefs: SyncPreferences
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
            download(token, folder, stored, thumbsOnly)
            if (!thumbsOnly) cleanUp(token, syncFolder, listed)
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
    private suspend fun download(token: String, folder: String, inDrive: Map<String, DriveSyncFile>, thumbsOnly: Boolean) = coroutineScope {
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
                        // Put in Drive's trash by a clean-up although a save needs it: take it back out.
                        val remote = inDrive[sha] ?: client.restoreTrashed(token, folder, sha)
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
    private suspend fun cleanUp(token: String, syncFolder: String, listed: List<DriveSyncFile>) {
        val now = System.currentTimeMillis()
        val clean = prefs.cleanRoundAt()
        if (clean == 0L || now - clean > CLEAN_ROUND_MAX_AGE_MS) return
        if (now - prefs.fileCleanupAt() < CLEANUP_EVERY_MS) return
        if (syncDao.inboxCount() > 0 || syncDao.outboxCount() > 0) return
        // The library this phone knows must still be everyone's latest: if any
        // other device's file changed since that round (a restore, a new
        // photo), wait for the next round to read it first.
        val me = prefs.deviceId()
        val seen = prefs.seenMd5()
        val now2 = client.listDeviceFiles(token, syncFolder)
        if (now2.any { DriveSyncClient.deviceOf(it.name)?.let { d -> d != me } == true && seen[it.name] != it.md5 }) return
        val records = syncDao.metaOfKind(SyncRecord.KIND_ITEM).map {
            // A copy that can't be read could use any file: then remove nothing.
            runCatching { SyncCodec.decodeString(it.recordJson) }.getOrNull() ?: return
        }
        val used = HashSet(DriveFileCleanup.referenced(records.asSequence()))
        // Whatever this phone holds is kept too, even if not synced yet.
        val local = saveDao.allForExport().flatMap { listOfNotNull(it.localUri, it.thumbnailUri) } +
            attachmentDao.allForExport().map { it.uri }
        for (uri in local.distinct()) {
            if (uri.startsWith("http")) continue
            library.shaOf(uri)?.let { used += it } // a file missing here is covered by the records above
        }
        val plan = DriveFileCleanup.plan(listed.associate { it.id to it.name }, used, prefs.unusedFiles(), now)
        // To Drive's trash, not deleted: recoverable for Drive's 30 days, and a
        // phone that finds a save needing one takes it back out (see download).
        for (id in plan.remove) {
            try {
                client.trash(token, id)
            } catch (e: DriveSyncException) {
                if (e.status != 404) throw e // already gone (another phone) is fine
            }
        }
        prefs.setUnusedFiles(plan.unusedSince)
        prefs.setFileCleanupAt(now)
    }

    companion object {
        private const val CLEAN_ROUND_MAX_AGE_MS = 6L * 60 * 60 * 1000
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
