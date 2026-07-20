package com.ghostgramlabs.pettibox.ui.screens.settings

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.app.PendingIntent
import com.ghostgramlabs.pettibox.data.backup.LocalBackupWorker
import com.ghostgramlabs.pettibox.data.bookmarks.BookmarkFileParser
import com.ghostgramlabs.pettibox.data.drive.DriveAuth
import com.ghostgramlabs.pettibox.data.drive.DriveBackupFile
import com.ghostgramlabs.pettibox.data.drive.DriveBackupManager
import com.ghostgramlabs.pettibox.data.local.CategoryEntity
import com.ghostgramlabs.pettibox.data.ocr.OcrWorker
import com.ghostgramlabs.pettibox.data.ocr.PdfTextWorker
import com.ghostgramlabs.pettibox.data.preferences.BackupPreferences
import com.ghostgramlabs.pettibox.data.preferences.DriveBackupStatus
import com.ghostgramlabs.pettibox.data.preferences.LocalBackupStatus
import com.ghostgramlabs.pettibox.data.preferences.OcrPreferences
import com.ghostgramlabs.pettibox.data.preferences.ReminderPreferences
import com.ghostgramlabs.pettibox.data.preferences.ReminderTime
import com.ghostgramlabs.pettibox.data.reminders.ReminderScheduler
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import com.ghostgramlabs.pettibox.data.util.LocalBackupStore
import com.ghostgramlabs.pettibox.ui.components.NewCollection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SaveRepository,
    private val ocrPreferences: OcrPreferences,
    private val backupPreferences: BackupPreferences,
    private val reminderPreferences: ReminderPreferences,
    private val localBackupStore: LocalBackupStore,
    private val driveAuth: DriveAuth,
    private val driveBackupManager: DriveBackupManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {
    val autoScanOcr: Flow<Boolean> = ocrPreferences.autoScan
    val pdfPageLimit: Flow<Int> = ocrPreferences.pdfPageLimit
    val localBackupStatus: Flow<LocalBackupStatus> = backupPreferences.status
    val driveBackupStatus: Flow<DriveBackupStatus> = backupPreferences.driveStatus
    /** Anchors the quick-reminder presets snap to (see ReminderPreferences). */
    val morningReminderTime: Flow<ReminderTime> = reminderPreferences.morningTime
    val eveningReminderTime: Flow<ReminderTime> = reminderPreferences.eveningTime
    /** Live category list — drives both the count label and conflict checks. */
    val collections: Flow<List<CategoryEntity>> = repo.observeCategories()
    /** Live save count — gates the "restore on top of existing saves?" confirmation. */
    val totalSaves: Flow<Int> = repo.observeTotal()

    fun hasExactAlarmPermission(): Boolean = ReminderScheduler.hasExactAlarmPermission(appContext)

    fun openExactAlarmSettings(): Boolean = ReminderScheduler.openExactAlarmSettings(appContext)

    fun setMorningReminderTime(hour: Int, minute: Int) = viewModelScope.launch {
        reminderPreferences.setMorningTime(hour, minute)
    }

    fun setEveningReminderTime(hour: Int, minute: Int) = viewModelScope.launch {
        reminderPreferences.setEveningTime(hour, minute)
    }

    /**
     * Create a collection from Settings. Mirrors [SaveSheetViewModel]'s
     * createCollection but without the auto-select-and-save side-effect —
     * the user is in Settings, not mid-save.
     *
     * The callback returns the new id so the UI can flash a confirmation
     * containing the collection's name and emoji.
     */
    fun createCollection(nc: NewCollection, onCreated: ((CategoryEntity) -> Unit)? = null) =
        viewModelScope.launch {
            val existing = repo.observeCategories().first()
            val id = "user_" + UUID.randomUUID().toString().take(8)
            val maxOrder = (existing.maxOfOrNull { it.sortOrder } ?: 0) + 1
            val entity = CategoryEntity(
                id = id,
                name = nc.name,
                emoji = nc.emoji,
                colorHex = nc.colorHex,
                sortOrder = maxOrder,
                userCreated = true
            )
            repo.upsertCategory(entity)
            onCreated?.invoke(entity)
        }

    /**
     * Update a collection — starter or user-created; starters became
     * fully editable once seeding moved to once-per-install (re-seeding
     * no longer clobbers edits).
     */
    fun updateCollection(
        category: CategoryEntity,
        name: String,
        emoji: String,
        colorHex: Long,
        onDone: ((CategoryEntity) -> Unit)? = null
    ) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        val updated = category.copy(
            name = name.trim().take(28),
            emoji = emoji,
            colorHex = colorHex
        )
        repo.upsertCategory(updated)
        onDone?.invoke(updated)
    }

    /**
     * Delete any collection, starter or user-created. Saves inside it
     * stay in the library (their category_id becomes null) — same
     * behaviour as the Browse-side delete dialog. Deleted starters stay
     * gone because seeding runs once per install.
     */
    fun deleteCollection(category: CategoryEntity, onDone: (() -> Unit)? = null) =
        viewModelScope.launch {
            repo.deleteCategory(category.id)
            onDone?.invoke()
        }

    /** One-tap starter cleanup — see [SaveRepository.removeEmptyStarterCollections]. */
    suspend fun removeEmptyStarterCollections(): SaveRepository.StarterSweepResult =
        repo.removeEmptyStarterCollections()

    /** Portable bookmark CSV of every link, written to a shareable cache file. */
    suspend fun exportCsvFile(): Pair<File, Int> {
        val result = repo.exportBookmarksCsv()
        val file = File(appContext.cacheDir, "pettibox-links-${System.currentTimeMillis()}.csv")
        file.writeText(result.csv)
        return file to result.links
    }

    suspend fun exportBackupJson(): String = repo.exportBackupJson()

    suspend fun exportBackupZipFile(): Pair<File, SaveRepository.BackupExportResult> {
        val file = File(appContext.cacheDir, "pettibox-backup-${System.currentTimeMillis()}.zip")
        val result = repo.exportBackupZip(file)
        return file to result
    }

    suspend fun importBackupJson(json: String): SaveRepository.BackupImportResult =
        repo.importBackupJson(json)

    suspend fun createLocalBackupNow(): Pair<File, SaveRepository.BackupExportResult> {
        val file = localBackupStore.createBackupFile()
        val result = repo.exportBackupZip(file)
        val status = backupPreferences.status.first()
        if (status.folderUri.isNotBlank()) {
            val copied = localBackupStore.copyToPickedFolder(file, status.folderUri)
            if (copied) backupPreferences.clearCopyFailure()
            else backupPreferences.recordCopyFailure()
        }
        localBackupStore.pruneOldBackups()
        backupPreferences.recordLocalBackup(file.name, file.lastModified())
        return file to result
    }

    suspend fun setLocalBackupFolder(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        backupPreferences.setBackupFolder(uri.toString())
    }

    suspend fun setAutoLocalBackup(enabled: Boolean) {
        backupPreferences.setAutoLocalBackup(enabled)
        if (enabled) {
            LocalBackupWorker.schedule(appContext)
        } else {
            LocalBackupWorker.cancel(appContext)
        }
    }

    fun localBackupLocationLabel(): String = localBackupStore.backupLocationLabel()

    fun localBackupPath(): String = localBackupStore.backupPath()

    suspend fun importBackupUri(uri: Uri): SaveRepository.BackupImportResult =
        withContext(Dispatchers.IO) {
            val name = displayNameOf(uri)
            val mimeType = appContext.contentResolver.getType(uri).orEmpty()
            val isZip = name.endsWith(".zip", ignoreCase = true) ||
                mimeType.equals("application/zip", ignoreCase = true) ||
                mimeType.equals("application/x-zip-compressed", ignoreCase = true)
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                if (isZip) {
                    repo.importBackupZip(input)
                } else {
                    repo.importBackupJson(input.bufferedReader().use { it.readText() })
                }
            } ?: error("Could not open backup")
        }

    /**
     * Import a bookmarks export from another app (browser/Raindrop HTML,
     * Raindrop or Pocket CSV, plain URL list). Parsing a multi-megabyte
     * export off the main thread keeps the Settings screen responsive.
     */
    suspend fun importBookmarksUri(uri: Uri): SaveRepository.BookmarkImportResult =
        withContext(Dispatchers.Default) {
            val name = displayNameOf(uri)
            val content = appContext.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("Could not open file")
            val bookmarks = BookmarkFileParser.parse(content, name)
            if (bookmarks.isEmpty()) error("No links found in file")
            repo.importBookmarks(bookmarks)
        }

    // ── Google Drive backup ───────────────────────────────────────────────

    sealed interface DriveConnectStep {
        /** Consent already granted (e.g. reconnect after reinstall) — Drive is live. */
        data object Connected : DriveConnectStep
        /** The screen must launch this to show Google's consent sheet. */
        data class NeedsConsent(val pendingIntent: PendingIntent) : DriveConnectStep
    }

    suspend fun beginDriveConnect(account: Account? = null): DriveConnectStep {
        val result = driveAuth.authorize(account)
        val pendingIntent = result.pendingIntent
        return if (result.hasResolution() && pendingIntent != null) {
            DriveConnectStep.NeedsConsent(pendingIntent)
        } else {
            enableDriveBackup()
            DriveConnectStep.Connected
        }
    }

    /**
     * Authorize against a specific on-device Google account rather than
     * whichever one Play Services silently remembers — the only way to
     * change accounts, since AuthorizationClient has no revoke/forget
     * call. [accountName] comes from the system account chooser Settings
     * launches (android.accounts.AccountManager.newChooseAccountIntent).
     */
    suspend fun switchDriveAccount(accountName: String): DriveConnectStep =
        beginDriveConnect(Account(accountName, "com.google"))

    /** Called with the consent sheet's result intent. True when the user granted access. */
    suspend fun completeDriveConnect(data: Intent?): Boolean =
        runCatching {
            com.google.android.gms.auth.api.identity.Identity
                .getAuthorizationClient(appContext)
                .getAuthorizationResultFromIntent(data)
            enableDriveBackup()
            true
        }.getOrDefault(false)

    /**
     * Connecting Drive implies wanting automatic backups, and the Drive
     * upload rides on the nightly local worker — so switch that on too if
     * the user hadn't already.
     */
    private suspend fun enableDriveBackup() {
        backupPreferences.setDriveBackupEnabled(true)
        if (!backupPreferences.status.first().enabled) {
            backupPreferences.setAutoLocalBackup(true)
            LocalBackupWorker.schedule(appContext)
        }
        // Best-effort: shows "Connected as <email>" in Settings. A failed
        // lookup self-heals on the next successful upload.
        driveBackupManager.refreshAccountEmail()
    }

    suspend fun disconnectDrive() {
        backupPreferences.setDriveBackupEnabled(false)
    }

    /** Fresh zip → local shelf copy (usual bookkeeping) → Drive upload. */
    suspend fun backupToDriveNow(): DriveBackupManager.UploadOutcome {
        val (file, _) = createLocalBackupNow()
        return driveBackupManager.upload(file)
    }

    /** Null means Drive consent lapsed and the user must reconnect. */
    suspend fun listDriveBackups(): List<DriveBackupFile>? =
        driveBackupManager.listBackups()

    suspend fun restoreFromDrive(fileId: String): SaveRepository.BackupImportResult =
        // The zip is unpacked straight off the network stream — reading it on
        // the main dispatcher trips NetworkOnMainThreadException and the
        // restore dies with a generic "couldn't restore" snackbar.
        withContext(Dispatchers.IO) {
            val stream = driveBackupManager.openBackupStream(fileId)
                ?: error("Drive access needs reconnecting")
            stream.use { repo.importBackupZip(it) }
        }

    private fun displayNameOf(uri: Uri): String =
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }.orEmpty()

    suspend fun setAutoScanOcr(enabled: Boolean) {
        ocrPreferences.setAutoScan(enabled)
    }

    suspend fun setPdfPageLimit(limit: Int) {
        ocrPreferences.setPdfPageLimit(limit)
    }

    suspend fun scanExistingSaves(): Int {
        var count = 0
        repo.imageItemsNeedingOcr().forEach { item ->
            val uri = item.localUri ?: return@forEach
            OcrWorker.enqueueForItem(appContext, item.id, uri)
            count++
        }
        repo.imageAttachmentsNeedingOcr().forEach { attachment ->
            OcrWorker.enqueueForAttachment(appContext, attachment.itemId, attachment.id, attachment.uri)
            count++
        }
        repo.pdfItemsNeedingOcr().forEach { item ->
            val uri = item.localUri ?: return@forEach
            PdfTextWorker.enqueue(appContext, item.id, uri)
            count++
        }
        repo.pdfAttachmentsNeedingOcr().forEach { attachment ->
            PdfTextWorker.enqueue(appContext, attachment.itemId, attachment.uri)
            count++
        }
        return count
    }
}
