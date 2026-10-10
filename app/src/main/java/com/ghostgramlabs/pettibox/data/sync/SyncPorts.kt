package com.ghostgramlabs.pettibox.data.sync

import android.content.Context
import android.util.Log
import com.ghostgramlabs.pettibox.data.article.ArticleRepository
import com.ghostgramlabs.pettibox.data.drive.DriveAuth
import com.ghostgramlabs.pettibox.data.metadata.PageSnapshotWorker
import com.ghostgramlabs.pettibox.data.reminders.ReminderScheduler
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import com.ghostgramlabs.pettibox.data.util.LocalBackupStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where device files live. Google Drive in the app; an in-memory fake in the
 * two-device tests. A session is one round's worth of calls with one token.
 */
interface SyncTransport {
    /** Null when access has to be granted again (the user reconnects in Settings). */
    suspend fun open(): Session?

    interface Session {
        suspend fun list(): List<DriveSyncFile>
        suspend fun read(file: DriveSyncFile): String
        /** Creates or replaces this device's file; [existingId] from [list] when it exists. */
        suspend fun write(name: String, json: String, existingId: String?)
        suspend fun accountEmail(): String?
        /** The reset marker's content, or null if sync was never reset. */
        suspend fun resetMarker(): String? = null
    }
}

/** What a round does besides syncing data: safety copies, alarms, background fetches, files. */
interface SyncHooks {
    /** A full local copy of the library; false if it couldn't be made. */
    suspend fun safetyBackup(): Boolean
    suspend fun afterApply(applied: List<LocalLibrary.Applied>, now: Long)
    suspend fun syncFiles(wifiOnly: Boolean)
    /** Another device deleted the synced data: stop syncing here (the library stays). */
    suspend fun stopSync()
}

@Singleton
class DriveTransport @Inject constructor(
    private val auth: DriveAuth,
    private val client: DriveSyncClient
) : SyncTransport {
    override suspend fun open(): SyncTransport.Session? {
        val token = auth.silentAccessToken() ?: return null
        return object : SyncTransport.Session {
            // Found (and duplicate folders merged) only when first needed: the
            // engine checks the account before anything touches Drive.
            private var folderId: String? = null
            private suspend fun folder() = folderId ?: client.syncFolder(token).also { folderId = it }

            override suspend fun list() = client.listDeviceFiles(token, folder())
            override suspend fun read(file: DriveSyncFile) = client.downloadShard(token, file)
            override suspend fun write(name: String, json: String, existingId: String?) {
                client.uploadShard(token, folder(), name, json, existingId)
            }
            override suspend fun accountEmail() = client.accountEmail(token)
            override suspend fun resetMarker() = client.readResetMarker(token, folder())
        }
    }
}

@Singleton
class AppSyncHooks @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SaveRepository,
    private val articles: ArticleRepository,
    private val backupStore: LocalBackupStore,
    private val setup: SyncSetup
) : SyncHooks {

    override suspend fun safetyBackup(): Boolean = runCatching {
        val file = backupStore.createSafetyBackupFile()
        repository.exportBackupZip(file)
        backupStore.pruneSafetyBackups()
    }.onFailure { Log.w("PettiBoxSync", "safety backup failed", it) }.isSuccess

    override suspend fun afterApply(applied: List<LocalLibrary.Applied>, now: Long) {
        for (a in applied) {
            val id = a.itemId ?: continue
            if (a.remindChanged) {
                val item = repository.getById(id)
                val at = item?.remindAt
                if (item != null && at != null && !item.isPendingDelete && at > now) ReminderScheduler.schedule(context, id, at)
                else ReminderScheduler.cancel(context, id)
            }
            if (a.isNewLink) {
                val item = repository.getById(id) ?: continue
                if (item.thumbnailUri == null) PageSnapshotWorker.enqueue(context, id)
                articles.onLinkSaved(id, item.url)
            }
        }
    }

    override suspend fun syncFiles(wifiOnly: Boolean) = FileSyncWorker.enqueue(context, wifiOnly)

    override suspend fun stopSync() {
        setup.disable()
        SyncScheduler.apply(context, false, SyncInterval.MANUAL)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds abstract fun transport(impl: DriveTransport): SyncTransport
    @Binds abstract fun hooks(impl: AppSyncHooks): SyncHooks
}
