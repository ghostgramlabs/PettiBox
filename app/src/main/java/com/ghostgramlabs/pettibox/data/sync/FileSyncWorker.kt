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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Moves photos, PDFs and other files between this phone and
 * "PettiBox Sync/files/", separately from the text sync so text never waits
 * for them. Files are named by their sha256 (plus extension), uploaded once,
 * and checked against that hash when they come down. Nothing here deletes a
 * file anywhere.
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
        val token = auth.silentAccessToken() ?: return Result.success()
        return try {
            val folder = client.filesFolder(token, client.syncFolder(token))
            val stored = client.listStoredFiles(token, folder).associateBy { it.name.substringBefore('.') }
            upload(token, folder, stored.keys)
            download(token, stored)
            Result.success()
        } catch (e: DriveSyncException) {
            if (e.transient && runAttemptCount < 5) Result.retry() else Result.success()
        }
    }

    /** Every file this phone's saves use that Drive doesn't have yet. */
    private suspend fun upload(token: String, folder: String, inDrive: Set<String>) {
        val uris = (saveDao.allForExport().mapNotNull { it.localUri } + attachmentDao.allForExport().map { it.uri })
            .filter { it.startsWith("file:") }
            .distinct()
        val sent = HashSet(inDrive)
        for (uri in uris) {
            val sha = library.shaOf(uri) ?: continue
            if (!sent.add(sha)) continue
            val file = Uri.parse(uri).path?.let(::File)?.takeIf { it.exists() } ?: continue
            val ext = file.extension.lowercase().ifBlank { "bin" }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            client.uploadFile(token, folder, "$sha.$ext", file, mime)
        }
    }

    /** Fills placeholders for files other devices added, three at a time. */
    private suspend fun download(token: String, inDrive: Map<String, DriveSyncFile>) = coroutineScope {
        val pending = syncDao.pendingFiles().distinct()
        if (pending.isEmpty()) return@coroutineScope
        val dir = File(applicationContext.filesDir, "attachments").apply { mkdirs() }
        val gate = Semaphore(3)
        pending.map { placeholder ->
            async {
                gate.withPermit {
                    val sha = placeholder.removePrefix(PENDING_FILE_SCHEME)
                    val remote = inDrive[sha] ?: return@withPermit // not uploaded yet; next time
                    val ext = remote.name.substringAfter('.', "bin")
                    val target = File(dir, "${UUID.randomUUID()}.$ext")
                    client.downloadFile(token, remote.id, sha, target)
                    val uri = target.toUri().toString()
                    db.withTransaction {
                        syncDao.beginApplying() // swapping in the file is not an edit
                        syncDao.putFile(SyncFileEntity(uri, sha))
                        syncDao.fillAttachment(placeholder, uri)
                        syncDao.fillMainFile(placeholder, uri)
                        syncDao.endApplying()
                    }
                }
            }
        }.awaitAll()
    }

    companion object {
        private const val NAME = "sync_files"

        fun enqueue(context: Context, wifiOnly: Boolean) {
            val request = OneTimeWorkRequestBuilder<FileSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
