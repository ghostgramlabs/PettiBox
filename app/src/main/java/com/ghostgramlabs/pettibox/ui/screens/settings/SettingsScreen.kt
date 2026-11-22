package com.ghostgramlabs.pettibox.ui.screens.settings

import com.ghostgramlabs.pettibox.domain.model.displayName

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.ghostgramlabs.pettibox.R
import com.ghostgramlabs.pettibox.util.AppLocale

import android.accounts.AccountManager
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostgramlabs.pettibox.BuildConfig
import com.ghostgramlabs.pettibox.data.drive.DriveBackupFile
import com.ghostgramlabs.pettibox.data.drive.DriveBackupManager
import com.ghostgramlabs.pettibox.data.local.CategoryEntity
import com.ghostgramlabs.pettibox.data.preferences.AppLockPreferences
import com.ghostgramlabs.pettibox.data.preferences.DriveBackupStatus
import com.ghostgramlabs.pettibox.data.preferences.LocalBackupStatus
import com.ghostgramlabs.pettibox.data.preferences.OcrPreferences
import com.ghostgramlabs.pettibox.data.preferences.ReminderTime
import com.ghostgramlabs.pettibox.data.preferences.ThemeMode
import com.ghostgramlabs.pettibox.data.local.ArticleStats
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import com.ghostgramlabs.pettibox.data.sync.SyncInterval
import com.ghostgramlabs.pettibox.data.sync.SyncStatus
import com.ghostgramlabs.pettibox.ui.components.CreateCollectionDialog
import com.ghostgramlabs.pettibox.ui.components.EditCollectionDialog
import com.ghostgramlabs.pettibox.ui.components.HelpLinks
import com.ghostgramlabs.pettibox.ui.components.ReviewPrompt
import com.ghostgramlabs.pettibox.ui.components.rememberNotificationPermissionRequester
import com.ghostgramlabs.pettibox.ui.lock.AppLockSession
import com.ghostgramlabs.pettibox.data.preferences.RatingPreferences
import com.ghostgramlabs.pettibox.ui.components.KeeperMascot
import com.ghostgramlabs.pettibox.ui.components.KeeperPose
import com.ghostgramlabs.pettibox.ui.components.ScreenHeading
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenBrowse: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val autoScanOcr by viewModel.autoScanOcr.collectAsStateWithLifecycle(initialValue = true)
    val ocrLanguages by viewModel.ocrLanguages.collectAsStateWithLifecycle(initialValue = emptySet())
    val ocrRereading by viewModel.ocrRereading.collectAsStateWithLifecycle(initialValue = false)
    val ocrFailed by viewModel.ocrFailedLanguages.collectAsStateWithLifecycle(initialValue = emptySet())
    var showOcrLanguages by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val pdfPageLimit by viewModel.pdfPageLimit.collectAsStateWithLifecycle(
        initialValue = OcrPreferences.DEFAULT_PDF_PAGES
    )
    val localBackupStatus by viewModel.localBackupStatus.collectAsStateWithLifecycle(
        initialValue = LocalBackupStatus(
            enabled = false,
            lastBackupAt = 0L,
            lastBackupName = "",
            folderUri = "",
            lastCopyFailedAt = 0L
        )
    )
    val driveBackupStatus by viewModel.driveBackupStatus.collectAsStateWithLifecycle(
        initialValue = DriveBackupStatus(
            enabled = false,
            accountEmail = "",
            lastBackupAt = 0L,
            lastBackupName = "",
            needsReconnectAt = 0L,
            lastFailedAt = 0L
        )
    )
    val collections by viewModel.collections.collectAsStateWithLifecycle(initialValue = emptyList())
    val totalSaves by viewModel.totalSaves.collectAsStateWithLifecycle(initialValue = 0)
    val keepOfflineCopies by viewModel.keepOfflineCopies.collectAsStateWithLifecycle(initialValue = true)
    val offlineCopyStats by viewModel.offlineCopyStats.collectAsStateWithLifecycle(initialValue = ArticleStats(0, 0))
    val linksWithoutCopy by viewModel.linksWithoutCopy.collectAsStateWithLifecycle(initialValue = 0)
    var showRemoveOfflineCopies by remember { mutableStateOf(false) }
    val weeklyNudge by viewModel.weeklyNudge.collectAsStateWithLifecycle(initialValue = true)
    val appLockOn by viewModel.appLock.collectAsStateWithLifecycle(initialValue = false)
    val showLockedTile by viewModel.showLockedTile.collectAsStateWithLifecycle(initialValue = true)
    val collectionsUnlocked by AppLockSession.collectionsUnlocked.collectAsStateWithLifecycle()
    val appLockAfterMs by viewModel.appLockAfterMs.collectAsStateWithLifecycle(
        initialValue = AppLockPreferences.DEFAULT_LOCK_AFTER_MS
    )
    // Re-checked on every resume: the user may have just removed (or added)
    // the phone's screen lock in Android settings.
    var appLockAvailable by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        appLockAvailable = AppLockSession.isAvailable(ctx)
        onPauseOrDispose { }
    }
    val requestNotificationPermission = rememberNotificationPermissionRequester()
    // Play's review sheet after an "it just worked" moment (see RatingPreferences).
    val askForReview: suspend (String) -> Unit = { moment ->
        val activity = ctx as? android.app.Activity
        if (activity != null && viewModel.claimHappyMoment(moment)) ReviewPrompt.launch(activity)
    }
    val morningReminderTime by viewModel.morningReminderTime
        .collectAsStateWithLifecycle(initialValue = ReminderTime(9, 0))
    val eveningReminderTime by viewModel.eveningReminderTime
        .collectAsStateWithLifecycle(initialValue = ReminderTime(21, 0))
    var showCreateCollection by remember { mutableStateOf(false) }
    var editingCollection by remember { mutableStateOf<CategoryEntity?>(null) }
    var deletingCollection by remember { mutableStateOf<CategoryEntity?>(null) }
    var showRemoveStarters by remember { mutableStateOf(false) }
    var showRestoreChooser by remember { mutableStateOf(false) }
    var editingReminderTime by remember { mutableStateOf<ReminderTimeTarget?>(null) }
    var showHelpDetails by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var hasExactAlarmPermission by remember { mutableStateOf(viewModel.hasExactAlarmPermission()) }

    editingCollection?.let { target ->
        EditCollectionDialog(
            category = target,
            onDismiss = { editingCollection = null },
            onSave = { name, emoji, colorHex ->
                editingCollection = null
                viewModel.updateCollection(target, name, emoji, colorHex) { updated ->
                    scope.launch {
                        snackbarHostState.showSnackbar(ctx.getString(R.string.settings_updated, updated.emoji, updated.displayName))
                    }
                }
            },
            // Delete from inside the edit dialog is the conventional
            // "settings → edit thing → trash" path. We confirm via a
            // separate AlertDialog so an accidental tap doesn't nuke a
            // collection (and its saves' category links) silently.
            onDelete = {
                editingCollection = null
                deletingCollection = target
            }
        )
    }

    deletingCollection?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingCollection = null },
            title = { Text(stringResource(R.string.settings_delete, target.name)) },
            text = {
                Text(
                    stringResource(R.string.settings_saves_collection_will_stay_pettibox)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = target.name
                    val emoji = target.emoji
                    deletingCollection = null
                    viewModel.deleteCollection(target) {
                        scope.launch {
                            snackbarHostState.showSnackbar(ctx.getString(R.string.settings_deleted, emoji, name))
                        }
                    }
                }) { Text(stringResource(R.string.settings_delete_2), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingCollection = null }) { Text(stringResource(R.string.settings_cancel)) }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showRemoveOfflineCopies) {
        AlertDialog(
            onDismissRequest = { showRemoveOfflineCopies = false },
            title = { Text(stringResource(R.string.settings_remove_offline_copies)) },
            text = {
                Text(
                    stringResource(R.string.settings_saved_links_stay_exactly_they)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveOfflineCopies = false
                    scope.launch {
                        viewModel.removeAllOfflineCopies()
                        snackbarHostState.showSnackbar(ctx.getString(R.string.settings_offline_copies_removed))
                    }
                }) { Text(stringResource(R.string.settings_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveOfflineCopies = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showRemoveStarters) {
        AlertDialog(
            onDismissRequest = { showRemoveStarters = false },
            title = { Text(stringResource(R.string.settings_remove_starter_collections)) },
            text = {
                Text(
                    stringResource(R.string.settings_empty_starter_collections_will_removed)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveStarters = false
                    scope.launch {
                        val result = viewModel.removeEmptyStarterCollections()
                        snackbarHostState.showSnackbar(
                            when {
                                result.removed == 0 && result.keptWithSaves > 0 ->
                                    ctx.resources.getQuantityString(R.plurals.settings_starters_none_removed, result.keptWithSaves, result.keptWithSaves)
                                result.keptWithSaves > 0 ->
                                    ctx.resources.getQuantityString(R.plurals.settings_starters_removed, result.removed, result.removed) + " · " +
                                        ctx.resources.getQuantityString(R.plurals.settings_starters_kept, result.keptWithSaves, result.keptWithSaves)
                                else ->
                                    ctx.resources.getQuantityString(R.plurals.settings_starters_removed, result.removed, result.removed)
                            }
                        )
                    }
                }) { Text(stringResource(R.string.settings_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveStarters = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    editingReminderTime?.let { target ->
        val current = if (target == ReminderTimeTarget.MORNING) morningReminderTime else eveningReminderTime
        ReminderTimeDialog(
            title = if (target == ReminderTimeTarget.MORNING) stringResource(R.string.settings_set_morning_time) else stringResource(R.string.settings_set_evening_time),
            initialHour = current.hour,
            initialMinute = current.minute,
            onDismiss = { editingReminderTime = null },
            onConfirm = { hour, minute ->
                editingReminderTime = null
                val message = if (target == ReminderTimeTarget.MORNING) {
                    viewModel.setMorningReminderTime(hour, minute); R.string.settings_morning_time_set
                } else {
                    viewModel.setEveningReminderTime(hour, minute); R.string.settings_evening_time_set
                }
                scope.launch {
                    snackbarHostState.showSnackbar(ctx.getString(message, formatClock(hour, minute)))
                }
            }
        )
    }

    if (showCreateCollection) {
        CreateCollectionDialog(
            onCreate = { newCollection ->
                showCreateCollection = false
                // Reject case-insensitive duplicates inline rather than
                // silently writing two collections with the same name.
                val duplicate = collections.any {
                    it.name.equals(newCollection.name.trim(), ignoreCase = true)
                }
                if (duplicate) {
                    scope.launch {
                        snackbarHostState.showSnackbar(ctx.getString(R.string.settings_collection_named_already_exists, newCollection.name))
                    }
                    return@CreateCollectionDialog
                }
                viewModel.createCollection(newCollection) { created ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            ctx.getString(R.string.settings_created, created.emoji, created.name)
                        )
                    }
                }
            },
            onDismiss = { showCreateCollection = false }
        )
    }
    // Restores are additive (duplicates are skipped, but the merge is
    // still worth a heads-up). Both restore paths funnel through this
    // gate: empty shelf restores immediately (the new-phone case), a
    // non-empty shelf asks first.
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }

    val runFileRestore: (Uri) -> Unit = { uri ->
        scope.launch {
            busyLabel = ctx.getString(R.string.settings_importing_backup)
            runCatching { viewModel.importBackupUri(uri) }
                .onSuccess { result ->
                    snackbarHostState.showSnackbar(restoreResultMessage(ctx.resources, result))
                    if (result.saves > 0) askForReview(RatingPreferences.MOMENT_RESTORE)
                }
                .onFailure {
                    snackbarHostState.showSnackbar(ctx.getString(R.string.settings_backup_file_couldnt_imported))
                }
            busyLabel = null
        }
    }

    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            if (totalSaves > 0) {
                pendingRestore = PendingRestore.FromFile(uri)
            } else {
                runFileRestore(uri)
            }
        }
    }

    // ── This phone's automatic copies ─────────────────────────────────────
    var localRestoreChoices by remember { mutableStateOf<List<File>?>(null) }

    val runLocalRestore: (File) -> Unit = { file ->
        scope.launch {
            busyLabel = ctx.getString(R.string.settings_restoring_backup)
            runCatching { viewModel.restoreFromLocal(file) }
                .onSuccess { result ->
                    snackbarHostState.showSnackbar(restoreResultMessage(ctx.resources, result))
                    if (result.saves > 0) askForReview(RatingPreferences.MOMENT_RESTORE)
                }
                .onFailure {
                    snackbarHostState.showSnackbar(ctx.getString(R.string.settings_backup_couldnt_restored))
                }
            busyLabel = null
        }
    }

    val openLocalRestore: () -> Unit = {
        scope.launch {
            val copies = viewModel.listLocalBackups()
            if (copies.isEmpty()) {
                snackbarHostState.showSnackbar(ctx.getString(R.string.settings_no_copies_phone_yet_tap))
            } else {
                localRestoreChoices = copies
            }
        }
    }

    // ── Google Drive: connect, upload-now, restore ────────────────────────
    var driveRestoreChoices by remember { mutableStateOf<List<DriveBackupFile>?>(null) }

    val runDriveRestore: (DriveBackupFile) -> Unit = { backup ->
        scope.launch {
            busyLabel = ctx.getString(R.string.settings_restoring_google_drive)
            runCatching { viewModel.restoreFromDrive(backup.id) }
                .onSuccess { result ->
                    snackbarHostState.showSnackbar(restoreResultMessage(ctx.resources, result))
                    if (result.saves > 0) askForReview(RatingPreferences.MOMENT_RESTORE)
                }
                .onFailure {
                    snackbarHostState.showSnackbar(ctx.getString(R.string.settings_couldnt_restore_backup_try_again))
                }
            busyLabel = null
        }
    }

    val uploadToDriveNow: suspend () -> Unit = {
        busyLabel = ctx.getString(R.string.settings_backing_up)
        val outcome = runCatching { viewModel.backupToDriveNow() }
            .getOrElse { DriveBackupManager.UploadOutcome.Failed(it) }
        when (outcome) {
            is DriveBackupManager.UploadOutcome.Uploaded ->
                snackbarHostState.showSnackbar(ctx.getString(R.string.settings_backed_up_copy_saved_phone))
            DriveBackupManager.UploadOutcome.NeedsReconnect ->
                snackbarHostState.showSnackbar(ctx.getString(R.string.settings_saved_phone_google_drive_needs))
            is DriveBackupManager.UploadOutcome.Failed ->
                snackbarHostState.showSnackbar(ctx.getString(R.string.settings_saved_phone_but_drive_upload))
        }
        busyLabel = null
    }

    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle(
        initialValue = SyncStatus(
            enabled = false, interval = SyncInterval.HOURS_3, filesOnWifiOnly = false, deviceName = "",
            accountEmail = "", lastSyncAt = 0L, lastError = "", lastErrorAt = 0L, needsReconnect = false,
            pendingReview = 0, progress = ""
        )
    )
    val syncDevices by viewModel.syncDevices.collectAsStateWithLifecycle(initialValue = emptyMap())
    val syncConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        scope.launch {
            if (result.resultCode == Activity.RESULT_OK && viewModel.completeSyncConnect(result.data)) {
                snackbarHostState.showSnackbar(ctx.getString(R.string.settings_sync_library_syncing_now))
            } else {
                snackbarHostState.showSnackbar(ctx.getString(R.string.settings_sync_wasnt_turned))
            }
        }
    }
    val connectSync: () -> Unit = {
        scope.launch {
            runCatching { viewModel.beginSyncConnect() }
                .onSuccess { step ->
                    when (step) {
                        SettingsViewModel.DriveConnectStep.Connected ->
                            snackbarHostState.showSnackbar(ctx.getString(R.string.settings_sync_library_syncing_now))
                        is SettingsViewModel.DriveConnectStep.NeedsConsent ->
                            syncConsent.launch(IntentSenderRequest.Builder(step.pendingIntent.intentSender).build())
                    }
                }
                .onFailure {
                    snackbarHostState.showSnackbar(ctx.getString(R.string.settings_couldnt_reach_google_play_services))
                }
        }
    }

    val driveConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        scope.launch {
            if (result.resultCode == Activity.RESULT_OK && viewModel.completeDriveConnect(result.data)) {
                snackbarHostState.showSnackbar(ctx.getString(R.string.settings_google_drive_connected))
                uploadToDriveNow()
            } else {
                snackbarHostState.showSnackbar(ctx.getString(R.string.settings_google_drive_connection_was_cancelled))
            }
        }
    }

    val connectDrive: () -> Unit = {
        scope.launch {
            busyLabel = ctx.getString(R.string.settings_connecting_google_drive)
            runCatching { viewModel.beginDriveConnect() }
                .onSuccess { step ->
                    busyLabel = null
                    when (step) {
                        SettingsViewModel.DriveConnectStep.Connected -> {
                            snackbarHostState.showSnackbar(ctx.getString(R.string.settings_google_drive_connected))
                            uploadToDriveNow()
                        }
                        is SettingsViewModel.DriveConnectStep.NeedsConsent ->
                            driveConsent.launch(
                                IntentSenderRequest.Builder(step.pendingIntent.intentSender).build()
                            )
                    }
                }
                .onFailure {
                    busyLabel = null
                    snackbarHostState.showSnackbar(
                        ctx.getString(R.string.settings_couldnt_reach_google_play_services_2)
                    )
                }
        }
    }

    // AuthorizationClient has no "forget this account" call — Play
    // Services always silently reuses whichever account last granted
    // drive.file. The only way to switch is to name a different account
    // explicitly, so this opens Android's own account chooser (all
    // Google accounts on the device) and authorizes against whatever the
    // user picks there.
    val chooseDriveAccount = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode != Activity.RESULT_OK || accountName == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyLabel = ctx.getString(R.string.settings_switching_google_account)
            runCatching { viewModel.switchDriveAccount(accountName) }
                .onSuccess { step ->
                    busyLabel = null
                    when (step) {
                        SettingsViewModel.DriveConnectStep.Connected -> {
                            snackbarHostState.showSnackbar(ctx.getString(R.string.settings_google_drive_connected_2, accountName))
                            uploadToDriveNow()
                        }
                        is SettingsViewModel.DriveConnectStep.NeedsConsent ->
                            driveConsent.launch(
                                IntentSenderRequest.Builder(step.pendingIntent.intentSender).build()
                            )
                    }
                }
                .onFailure {
                    busyLabel = null
                    snackbarHostState.showSnackbar(ctx.getString(R.string.settings_couldnt_switch_google_account))
                }
        }
    }

    val switchDriveAccount: () -> Unit = {
        runCatching {
            chooseDriveAccount.launch(
                AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
            )
        }.onFailure {
            scope.launch {
                snackbarHostState.showSnackbar(ctx.getString(R.string.settings_no_account_chooser_available_device))
            }
        }
    }

    val openDriveRestore: () -> Unit = {
        scope.launch {
            busyLabel = ctx.getString(R.string.settings_checking_google_drive)
            val backups = runCatching { viewModel.listDriveBackups() }.getOrNull()
            busyLabel = null
            when {
                backups == null -> snackbarHostState.showSnackbar(
                    ctx.getString(R.string.settings_couldnt_reach_google_drive_reconnect)
                )
                backups.isEmpty() -> snackbarHostState.showSnackbar(
                    ctx.getString(R.string.settings_no_drive_backups_yet_tap)
                )
                else -> driveRestoreChoices = backups
            }
        }
    }

    if (showRestoreChooser) {
        AlertDialog(
            onDismissRequest = { showRestoreChooser = false },
            title = { Text(stringResource(R.string.settings_restore_backup)) },
            text = {
                Column {
                    RestoreSourceRow(
                        icon = Icons.Rounded.Cloud,
                        title = stringResource(R.string.settings_google_drive),
                        caption = if (driveBackupStatus.enabled) {
                            stringResource(R.string.settings_pick_one_cloud_copies)
                        } else {
                            stringResource(R.string.settings_connect_google_drive_first_new)
                        },
                        onClick = {
                            showRestoreChooser = false
                            if (driveBackupStatus.enabled) openDriveRestore() else connectDrive()
                        }
                    )
                    RestoreSourceRow(
                        icon = Icons.Rounded.PhoneAndroid,
                        title = stringResource(R.string.settings_phone),
                        caption = stringResource(R.string.settings_one_automatic_copies_pettibox_keeps),
                        onClick = {
                            showRestoreChooser = false
                            openLocalRestore()
                        }
                    )
                    RestoreSourceRow(
                        icon = Icons.Rounded.FolderOpen,
                        title = stringResource(R.string.settings_file),
                        caption = stringResource(R.string.settings_pick_pettibox_backup_zip_phone),
                        onClick = {
                            showRestoreChooser = false
                            importBackup.launch(arrayOf("application/zip", "application/json", "text/*", "*/*"))
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showRestoreChooser = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    pendingRestore?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.settings_restore_top_shelf)) },
            text = {
                Text(pluralStringResource(R.plurals.settings_restore_on_top_body, totalSaves, totalSaves))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    when (pending) {
                        is PendingRestore.FromFile -> runFileRestore(pending.uri)
                        is PendingRestore.FromDrive -> runDriveRestore(pending.backup)
                        is PendingRestore.FromLocal -> runLocalRestore(pending.file)
                    }
                }) { Text(stringResource(R.string.settings_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text(stringResource(R.string.settings_cancel)) }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    localRestoreChoices?.let { copies ->
        AlertDialog(
            onDismissRequest = { localRestoreChoices = null },
            title = { Text(stringResource(R.string.settings_restore_phone)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.settings_pick_copy_current_saves_stay),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    copies.forEach { file ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    localRestoreChoices = null
                                    if (totalSaves > 0) {
                                        pendingRestore = PendingRestore.FromLocal(file)
                                    } else {
                                        runLocalRestore(file)
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Text(
                                formatBackupTime(file.lastModified()),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                formatStorage(file.length()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { localRestoreChoices = null }) { Text(stringResource(R.string.settings_cancel)) }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    driveRestoreChoices?.let { backups ->
        AlertDialog(
            onDismissRequest = { driveRestoreChoices = null },
            title = { Text(stringResource(R.string.settings_restore_google_drive)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.settings_pick_backup_bring_onto_device),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    backups.take(5).forEach { backup ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    driveRestoreChoices = null
                                    if (totalSaves > 0) {
                                        pendingRestore = PendingRestore.FromDrive(backup)
                                    } else {
                                        runDriveRestore(backup)
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Text(
                                backup.name,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                formatBackupTime(backup.createdAtMillis),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { driveRestoreChoices = null }) { Text(stringResource(R.string.settings_cancel)) }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    val importBookmarks = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                busyLabel = ctx.getString(R.string.settings_importing_bookmarks)
                runCatching { viewModel.importBookmarksUri(uri) }
                    .onSuccess { result ->
                        snackbarHostState.showSnackbar(bookmarkImportMessage(ctx.resources, result))
                        if (result.imported > 0) askForReview(RatingPreferences.MOMENT_IMPORT)
                    }
                    .onFailure {
                        snackbarHostState.showSnackbar(
                            ctx.getString(R.string.settings_couldnt_read_file_export_bookmark)
                        )
                    }
                busyLabel = null
            }
        }
    }
    val chooseBackupFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching { viewModel.setLocalBackupFolder(uri) }
                    .onSuccess { snackbarHostState.showSnackbar(ctx.getString(R.string.settings_backup_folder_selected)) }
                    .onFailure { snackbarHostState.showSnackbar(ctx.getString(R.string.settings_couldnt_use_folder)) }
            }
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ScreenHeading(
                title = stringResource(R.string.settings_make_yours),
                subtitle = stringResource(R.string.settings_shelf_settings_safety_copies_quick)
            )

            Spacer(Modifier.height(8.dp))
            // Inset the rest of the page so it lines up with section gutters
            // while still letting ScreenHeading own its own padding above.
            Column(Modifier.padding(horizontal = 20.dp)) {
            if (busyLabel != null) {
                WorkInProgressBanner(label = busyLabel!!)
                Spacer(Modifier.height(12.dp))
            }
            BackupConfidenceBanner(localBackupStatus)
            // The two things people come here to check most: whether sync is fine, and which languages are read.
            Spacer(Modifier.height(16.dp))
            SyncSettingsCard(
                status = syncStatus,
                devices = syncDevices,
                onTurnOn = connectSync,
                onTurnOff = { removeMine ->
                    scope.launch {
                        val removed = viewModel.turnOffSync(removeMine)
                        snackbarHostState.showSnackbar(
                            when {
                                !removed -> ctx.getString(R.string.settings_sync_off_couldnt_reach_google)
                                removeMine -> ctx.getString(R.string.settings_sync_off_phones_copy_was)
                                else -> ctx.getString(R.string.settings_sync_off_device)
                            }
                        )
                    }
                },
                onDeleteSynced = {
                    scope.launch {
                        val ok = viewModel.deleteSyncedData()
                        snackbarHostState.showSnackbar(
                            if (ok) ctx.getString(R.string.settings_synced_data_deleted_google_drive)
                            else ctx.getString(R.string.settings_couldnt_finish_deleting_google_drive)
                        )
                    }
                },
                onSyncNow = {
                    viewModel.syncNow()
                    scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.settings_syncing)) }
                },
                onSendFiles = {
                    scope.launch {
                        viewModel.sendFilesNow()
                        snackbarHostState.showSnackbar(ctx.getString(R.string.settings_sending_photos_files_any_connection))
                    }
                },
                onReconnect = connectSync,
                onInterval = { scope.launch { viewModel.setSyncInterval(it) } },
                onWifiOnly = { scope.launch { viewModel.setSyncFilesOnWifiOnly(it) } },
                onRename = { scope.launch { viewModel.setSyncDeviceName(it) } },
                onRemoveDevice = { id ->
                    scope.launch {
                        val ok = viewModel.removeSyncDevice(id)
                        snackbarHostState.showSnackbar(if (ok) ctx.getString(R.string.settings_removed) else ctx.getString(R.string.settings_couldnt_reach_google_drive))
                    }
                },
                onReview = { keep ->
                    scope.launch {
                        viewModel.answerSyncReview(keep)
                        snackbarHostState.showSnackbar(if (keep) ctx.getString(R.string.settings_keeping_saves) else ctx.getString(R.string.settings_applying_changes))
                    }
                }
            )

            Spacer(Modifier.height(16.dp))
            // Near the top: someone who can't read the current language looks for this first.
            SettingsSection(title = stringResource(R.string.settings_language)) {
                Text(
                    stringResource(R.string.settings_language_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                AppLanguageChoices(
                    selected = AppLocale.currentTag(ctx),
                    onSelect = { tag -> AppLocale.activityFrom(ctx)?.let { AppLocale.set(it, tag) } }
                )
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = stringResource(R.string.settings_text_recognition)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.TextSnippet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_auto_scan_images_pdfs_search),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.settings_applies_new_saves_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoScanOcr,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                viewModel.setAutoScanOcr(enabled)
                                snackbarHostState.showSnackbar(
                                    if (enabled) ctx.getString(R.string.settings_auto_scan_new_saves)
                                    else ctx.getString(R.string.settings_auto_scan_off_new_saves)
                                )
                            }
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showOcrLanguages = true }
                        .padding(vertical = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_languages_images),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            ocrLanguagesSummary(ctx.resources, ocrLanguages),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(stringResource(R.string.settings_change), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                if (showOcrLanguages) {
                    OcrLanguagesSheet(
                        on = ocrLanguages,
                        rereading = ocrRereading,
                        readyNow = viewModel::readyOcrLanguages,
                        failed = ocrFailed,
                        isOnline = viewModel::isOnline,
                        onRetry = viewModel::retryOcrLanguages,
                        onToggle = { code, on -> viewModel.setOcrLanguage(code, on) },
                        onDismiss = { showOcrLanguages = false }
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busyLabel = ctx.getString(R.string.settings_queueing_text_scan)
                            runCatching { viewModel.scanExistingSaves() }
                                .onSuccess { count ->
                                    snackbarHostState.showSnackbar(
                                        if (count == 0) ctx.getString(R.string.settings_no_older_images_pdfs_need)
                                        else ctx.resources.getQuantityString(R.plurals.settings_scan_queued, count, count)
                                    )
                                }
                                .onFailure { snackbarHostState.showSnackbar(ctx.getString(R.string.settings_couldnt_queue_text_scan)) }
                            busyLabel = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(stringResource(R.string.settings_scan_existing_saves), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.settings_pdf_scan_limit),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.settings_higher_limits_make_more_pages),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                PageLimitChoices(
                    selected = pdfPageLimit,
                    onSelect = { limit ->
                        scope.launch {
                            val before = pdfPageLimit
                            viewModel.setPdfPageLimit(limit)
                            val all = limit == OcrPreferences.ALL_PDF_PAGES
                            snackbarHostState.showSnackbar(
                                when {
                                    limit > before && all -> ctx.getString(R.string.settings_pdf_limit_raised_all)
                                    limit > before -> ctx.getString(R.string.settings_pdf_limit_raised, limit)
                                    all -> ctx.getString(R.string.settings_pdf_limit_lowered_all)
                                    else -> ctx.getString(R.string.settings_pdf_limit_lowered, limit)
                                }
                            )
                        }
                    }
                )
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = stringResource(R.string.settings_appearance)) {
                ThemeChoice(
                    mode = ThemeMode.SYSTEM,
                    icon = Icons.Rounded.PhoneAndroid,
                    selected = themeMode == ThemeMode.SYSTEM,
                    onClick = { onThemeModeChange(ThemeMode.SYSTEM) }
                )
                ThemeChoice(
                    mode = ThemeMode.LIGHT,
                    icon = Icons.Rounded.LightMode,
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = { onThemeModeChange(ThemeMode.LIGHT) }
                )
                ThemeChoice(
                    mode = ThemeMode.DARK,
                    icon = Icons.Rounded.DarkMode,
                    selected = themeMode == ThemeMode.DARK,
                    onClick = { onThemeModeChange(ThemeMode.DARK) }
                )
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = stringResource(R.string.settings_collections)) {
                CollectionsManager(
                    collections = collections,
                    onCreateClick = { showCreateCollection = true },
                    onEditCollection = { editingCollection = it }
                )
                // One-tap cleanup for people who'd rather start from a
                // blank grid than the prefilled starters. Only offered
                // while starter collections still exist.
                if (collections.any { !it.userCreated }) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { showRemoveStarters = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.settings_remove_starter_collections_2), fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = stringResource(R.string.settings_offline_reading)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_save_articles_read_offline),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.settings_when_save_link_keep_copy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = keepOfflineCopies,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                viewModel.setKeepOfflineCopies(enabled)
                                snackbarHostState.showSnackbar(
                                    if (enabled) ctx.getString(R.string.settings_new_links_will_saved_offline)
                                    else ctx.getString(R.string.settings_new_links_wont_saved_offline)
                                )
                            }
                        }
                    )
                }
                if (linksWithoutCopy > 0) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val queued = viewModel.saveCopiesForExistingLinks()
                                snackbarHostState.showSnackbar(
                                    if (queued == 0) ctx.getString(R.string.settings_none_older_links_can_saved)
                                    else ctx.resources.getQuantityString(R.plurals.settings_offline_queued, queued, queued)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(stringResource(R.string.settings_save_older_links_too, linksWithoutCopy), fontWeight = FontWeight.Bold)
                    }
                }
                if (offlineCopyStats.count > 0) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            pluralStringResource(R.plurals.settings_offline_articles_saved, offlineCopyStats.count, offlineCopyStats.count, formatStorage(offlineCopyStats.bytes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showRemoveOfflineCopies = true }) {
                            Text(stringResource(R.string.settings_remove_all), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = stringResource(R.string.settings_reminders)) {
                Text(
                    stringResource(R.string.settings_default_reminder_times),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.settings_when_tap_quick_reminder_time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                ReminderTimeRow(
                    icon = Icons.Rounded.LightMode,
                    label = stringResource(R.string.settings_morning_time),
                    sub = stringResource(R.string.settings_used_tomorrow_weekend_next_week),
                    time = formatClock(morningReminderTime.hour, morningReminderTime.minute),
                    onClick = { editingReminderTime = ReminderTimeTarget.MORNING }
                )
                Spacer(Modifier.height(8.dp))
                ReminderTimeRow(
                    icon = Icons.Rounded.DarkMode,
                    label = stringResource(R.string.settings_evening_time),
                    sub = stringResource(R.string.settings_used_tonight),
                    time = formatClock(eveningReminderTime.hour, eveningReminderTime.minute),
                    onClick = { editingReminderTime = ReminderTimeTarget.EVENING }
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.AutoStories,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_weekly_shelf_nudge),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.settings_saturday_morning_one_thing_saved),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = weeklyNudge,
                        onCheckedChange = { enabled ->
                            val apply = {
                                scope.launch {
                                    viewModel.setWeeklyNudge(enabled)
                                    snackbarHostState.showSnackbar(
                                        if (enabled) ctx.getString(R.string.settings_youll_get_one_nudge_saturday)
                                        else ctx.getString(R.string.settings_weekly_nudge_turned_off)
                                    )
                                }
                                Unit
                            }
                            if (enabled) requestNotificationPermission { apply() } else apply()
                        }
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.AccessTime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_time_reminders),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (hasExactAlarmPermission) {
                                stringResource(R.string.settings_reminders_fire_exact_time_set)
                            } else {
                                stringResource(R.string.settings_android_may_delay_reminders_few)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!hasExactAlarmPermission) {
                    Spacer(Modifier.height(12.dp))
                    NoticeBanner(
                        text = stringResource(R.string.settings_dot_reminders_allow_alarms_reminders),
                        action = stringResource(R.string.settings_open_settings),
                        pose = KeeperPose.Reminder,
                        onAction = {
                            if (!viewModel.openExactAlarmSettings()) {
                                scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.settings_couldnt_open_alarm_settings)) }
                            }
                            hasExactAlarmPermission = viewModel.hasExactAlarmPermission()
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = stringResource(R.string.settings_help)) {
                HelpFlow()
                Spacer(Modifier.height(12.dp))
                // The share-sheet flow is the app's core gesture and the
                // hardest to explain in words — lead with the video.
                OutlinedButton(
                    onClick = {
                        if (!HelpLinks.openShareDemo(ctx)) {
                            scope.launch {
                                snackbarHostState.showSnackbar(ctx.getString(R.string.settings_couldnt_open_video_no_browser))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_watch_save_any_app), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showHelpDetails = !showHelpDetails },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (showHelpDetails) stringResource(R.string.settings_hide_detailed_guide) else stringResource(R.string.settings_show_detailed_guide),
                        fontWeight = FontWeight.Bold
                    )
                }
                if (showHelpDetails) {

                // Grouped instead of a flat 10-row list — earlier the Help
                // section just stacked HelpItem rows until they read as a
                // FAQ dump. Three buckets keep each subsection short
                // enough to scan, and let us add the new features
                // (Favorites, Archive, Tags, Settings → New collection)
                // without making the wall taller.
                Spacer(Modifier.height(14.dp))
                HelpGroupTitle(stringResource(R.string.settings_basics))
                HelpItem(
                    title = stringResource(R.string.settings_save_any_app),
                    body = stringResource(R.string.settings_tap_share_another_app_choose),
                    icon = Icons.Rounded.Share
                )
                HelpItem(
                    title = stringResource(R.string.settings_add_pettibox),
                    body = stringResource(R.string.settings_tap_button_home_add_note),
                    icon = Icons.Rounded.PhoneAndroid
                )
                HelpItem(
                    title = stringResource(R.string.settings_find_later),
                    body = stringResource(R.string.settings_search_words_source_app_file),
                    icon = Icons.Rounded.Search
                )
                HelpItem(
                    title = stringResource(R.string.settings_make_new_collection),
                    body = stringResource(R.string.settings_use_collections_above_create_rename),
                    icon = Icons.Rounded.GridView
                )
                HelpItem(
                    title = stringResource(R.string.settings_lots_collections_search_them),
                    body = stringResource(R.string.settings_once_have_more_than_8),
                    icon = Icons.Rounded.Search
                )

                Spacer(Modifier.height(14.dp))
                HelpGroupTitle(stringResource(R.string.settings_get_more_out))
                HelpItem(
                    title = stringResource(R.string.settings_long_press_quick_actions),
                    body = stringResource(R.string.settings_press_hold_any_save_pin),
                    icon = Icons.Rounded.TouchApp
                )
                HelpItem(
                    title = stringResource(R.string.settings_love_see_all_favorites),
                    body = stringResource(R.string.settings_tap_heart_save_long_press),
                    icon = Icons.Rounded.Favorite
                )
                HelpItem(
                    title = stringResource(R.string.settings_tags_cut_across_collections),
                    body = stringResource(R.string.settings_save_lives_one_collection_but),
                    icon = Icons.Rounded.LocalOffer
                )
                HelpItem(
                    title = stringResource(R.string.settings_edit_anytime_saves_itself),
                    body = stringResource(R.string.settings_open_any_save_change_its),
                    icon = Icons.Rounded.Edit
                )
                HelpItem(
                    title = stringResource(R.string.settings_unread_read_later_list),
                    body = stringResource(R.string.settings_anything_save_stays_browse_unread),
                    icon = Icons.Rounded.AutoStories
                )
                HelpItem(
                    title = stringResource(R.string.settings_widget_app_icon_shortcuts),
                    body = stringResource(R.string.settings_long_press_pettibox_icon_new),
                    icon = Icons.Rounded.Widgets
                )
                HelpItem(
                    title = stringResource(R.string.settings_archive_when_youre_done),
                    body = stringResource(R.string.settings_archived_saves_disappear_home_but),
                    icon = Icons.Rounded.Archive
                )
                HelpItem(
                    title = stringResource(R.string.settings_remind_me_later),
                    body = stringResource(R.string.settings_tap_clock_any_save_save),
                    icon = Icons.Rounded.AccessTime
                )
                HelpItem(
                    title = stringResource(R.string.settings_read_links_offline),
                    body = stringResource(R.string.settings_when_save_link_pettibox_keeps),
                    icon = Icons.AutoMirrored.Rounded.MenuBook
                )
                HelpItem(
                    title = stringResource(R.string.settings_pictures_every_link),
                    body = stringResource(R.string.settings_links_show_picture_website_provides),
                    icon = Icons.Rounded.Wallpaper
                )
                HelpItem(
                    title = stringResource(R.string.settings_no_offline_copy),
                    body = stringResource(R.string.settings_some_pages_cant_copied_paywalled),
                    icon = Icons.AutoMirrored.Rounded.MenuBook
                )
                HelpItem(
                    title = stringResource(R.string.settings_zoom_pictures_read_pdfs),
                    body = stringResource(R.string.settings_tap_picture_open_full_screen),
                    icon = Icons.Rounded.ZoomIn
                )
                HelpItem(
                    title = stringResource(R.string.settings_text_recognition_optional),
                    body = stringResource(R.string.settings_auto_scan_makes_text_screenshots),
                    icon = Icons.AutoMirrored.Rounded.TextSnippet
                )

                Spacer(Modifier.height(14.dp))
                HelpGroupTitle(stringResource(R.string.settings_backups_safety))
                HelpItem(
                    title = stringResource(R.string.settings_share_remove_safely),
                    body = stringResource(R.string.settings_open_saved_item_share_one),
                    icon = Icons.Rounded.Share
                )
                HelpItem(
                    title = stringResource(R.string.settings_deleted_something_mistake),
                    body = stringResource(R.string.settings_deleted_saves_wait_browse_recently),
                    icon = Icons.Rounded.RestoreFromTrash
                )
                HelpItem(
                    title = stringResource(R.string.settings_lock_pettibox),
                    body = stringResource(R.string.settings_turn_app_lock_under_privacy),
                    icon = Icons.Rounded.Lock
                )
                HelpItem(
                    title = stringResource(R.string.settings_lock_collection),
                    body = stringResource(R.string.settings_open_collection_tap_edit_turn),
                    icon = Icons.Rounded.Lock
                )
                HelpItem(
                    title = stringResource(R.string.settings_sync_between_devices),
                    body = stringResource(R.string.settings_turn_sync_between_devices_each),
                    icon = Icons.Rounded.Sync
                )
                HelpItem(
                    title = stringResource(R.string.settings_where_my_library_kept),
                    body = stringResource(R.string.settings_pettibox_has_no_servers_library),
                    icon = Icons.Rounded.Lock
                )
                HelpItem(
                    title = stringResource(R.string.settings_edited_two_devices),
                    body = stringResource(R.string.settings_if_same_title_note_link),
                    icon = Icons.Rounded.Devices
                )
                HelpItem(
                    title = stringResource(R.string.settings_back_up_shelf),
                    body = stringResource(R.string.settings_every_backup_always_keeps_copy),
                    icon = Icons.Rounded.Download
                )
                HelpItem(
                    title = stringResource(R.string.settings_restore_backup_2),
                    body = stringResource(R.string.settings_new_phone_need_recover_tap),
                    icon = Icons.Rounded.FolderOpen
                )
                HelpItem(
                    title = stringResource(R.string.settings_bring_bookmarks_other_apps),
                    body = stringResource(R.string.settings_coming_chrome_firefox_raindrop_io),
                    icon = Icons.Rounded.FolderOpen
                )
                HelpItem(
                    title = stringResource(R.string.settings_back_up_google_drive),
                    body = stringResource(R.string.settings_connect_google_drive_backup_section),
                    icon = Icons.Rounded.Cloud
                )
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = stringResource(R.string.settings_privacy)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_lock_pettibox),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.settings_ask_fingerprint_face_phone_pin),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = appLockOn,
                        onCheckedChange = { enabled ->
                            val activity = ctx as? androidx.fragment.app.FragmentActivity
                            when {
                                !enabled -> scope.launch {
                                    viewModel.setAppLock(false)
                                    snackbarHostState.showSnackbar(ctx.getString(R.string.settings_app_lock_off))
                                }
                                !AppLockSession.isAvailable(ctx) -> scope.launch {
                                    snackbarHostState.showSnackbar(
                                        ctx.getString(R.string.settings_set_up_screen_lock_pin)
                                    )
                                }
                                // Confirm it works before switching it on, so no one locks themselves out.
                                activity != null -> AppLockSession.prompt(
                                    activity,
                                    title = ctx.getString(R.string.settings_turn_app_lock),
                                    onSuccess = {
                                        scope.launch {
                                            viewModel.setAppLock(true)
                                            snackbarHostState.showSnackbar(ctx.getString(R.string.settings_app_lock))
                                        }
                                    }
                                )
                            }
                        }
                    )
                }
                if (appLockOn && !appLockAvailable) {
                    // The gate steps aside when there's nothing to unlock
                    // with; say so instead of showing a switch that lies.
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.settings_paused_phone_has_no_screen),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (appLockOn) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.settings_lock_after_leaving),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    LockAfterChoices(
                        selectedMs = appLockAfterMs,
                        onSelect = { ms -> scope.launch { viewModel.setAppLockAfterMs(ms) } }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.settings_locking_phones_screen_always_locks),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Locked collections. Looks the same whether or not any exist,
                // so Settings gives nothing away until after an unlock.
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (collectionsUnlocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_locked_collections),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.settings_lock_collection_its_edit_screen),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = {
                        val activity = ctx as? androidx.fragment.app.FragmentActivity
                        when {
                            collectionsUnlocked -> {
                                AppLockSession.lockCollections()
                                scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.settings_locked_collections_hidden_again)) }
                            }
                            activity != null -> AppLockSession.unlockCollections(activity) {
                                scope.launch {
                                    if (viewModel.lockedCollectionCount() > 0) {
                                        onOpenBrowse()
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            ctx.getString(R.string.settings_nothing_locked_yet_lock_collection)
                                        )
                                    }
                                }
                            }
                        }
                    }) {
                        Text(if (collectionsUnlocked) stringResource(R.string.settings_lock_again) else stringResource(R.string.settings_open), fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.width(36.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_show_locked_browse),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (showLockedTile) stringResource(R.string.settings_locked_tile_bottom_browse_opens)
                            else stringResource(R.string.settings_no_trace_browse_open_them),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showLockedTile,
                        onCheckedChange = { show -> scope.launch { viewModel.setShowLockedTile(show) } }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = stringResource(R.string.settings_backup)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_automatic_nightly_backup),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (localBackupStatus.lastBackupAt > 0L) {
                                stringResource(R.string.settings_runs_overnight_last_backup, formatBackupTime(localBackupStatus.lastBackupAt))
                            } else {
                                stringResource(R.string.settings_runs_overnight_every_place_below)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = localBackupStatus.enabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                viewModel.setAutoLocalBackup(enabled)
                                snackbarHostState.showSnackbar(
                                    if (enabled) ctx.getString(R.string.settings_automatic_nightly_backup_2)
                                    else ctx.getString(R.string.settings_automatic_nightly_backup_off)
                                )
                            }
                        }
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.settings_where_backups_go),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                BackupDestinationRow(
                    icon = Icons.Rounded.PhoneAndroid,
                    title = stringResource(R.string.settings_phone_2),
                    caption = stringResource(R.string.settings_always_included_keeps_3_newest)
                )
                BackupDestinationRow(
                    icon = Icons.Rounded.Cloud,
                    title = stringResource(R.string.settings_google_drive_2),
                    caption = when {
                        !driveBackupStatus.enabled ->
                            stringResource(R.string.settings_not_connected_cloud_copies_survive)
                        driveBackupStatus.accountEmail.isNotBlank() && driveBackupStatus.lastBackupAt > 0L ->
                            stringResource(R.string.settings_last_upload, driveBackupStatus.accountEmail, formatBackupTime(driveBackupStatus.lastBackupAt))
                        driveBackupStatus.accountEmail.isNotBlank() ->
                            stringResource(R.string.settings_uploads_tonights_backup, driveBackupStatus.accountEmail)
                        driveBackupStatus.lastBackupAt > 0L ->
                            stringResource(R.string.settings_connected_last_upload, formatBackupTime(driveBackupStatus.lastBackupAt))
                        else -> stringResource(R.string.settings_connected_uploads_tonights_backup)
                    },
                    actionLabel = if (driveBackupStatus.enabled) stringResource(R.string.settings_disconnect) else stringResource(R.string.settings_connect),
                    onAction = {
                        if (driveBackupStatus.enabled) {
                            scope.launch {
                                viewModel.disconnectDrive()
                                snackbarHostState.showSnackbar(
                                    ctx.getString(R.string.settings_google_drive_disconnected_copies_already)
                                )
                            }
                        } else {
                            connectDrive()
                        }
                    },
                    secondaryActionLabel = if (driveBackupStatus.enabled) stringResource(R.string.settings_switch_account) else null,
                    onSecondaryAction = if (driveBackupStatus.enabled) switchDriveAccount else null
                )
                if (driveBackupStatus.enabled && driveBackupStatus.needsReconnectAt > 0L) {
                    NoticeBanner(
                        text = stringResource(R.string.settings_pettibox_lost_access_google_drive),
                        action = stringResource(R.string.settings_reconnect),
                        onAction = connectDrive,
                        pose = KeeperPose.BackupWarning,
                        isError = true
                    )
                    Spacer(Modifier.height(6.dp))
                } else if (driveBackupStatus.enabled && driveBackupStatus.lastFailedAt > 0L) {
                    Text(
                        stringResource(R.string.settings_last_drive_upload_didnt_finish),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                }
                BackupDestinationRow(
                    icon = Icons.Rounded.FolderOpen,
                    title = stringResource(R.string.settings_extra_folder),
                    caption = if (localBackupStatus.folderUri.isBlank()) {
                        stringResource(R.string.settings_optional_also_drop_each_copy)
                    } else {
                        stringResource(R.string.settings_each_backup_also_copied_chosen)
                    },
                    actionLabel = if (localBackupStatus.folderUri.isBlank()) stringResource(R.string.settings_choose) else stringResource(R.string.settings_change),
                    onAction = {
                        // Some OEM builds ship without a Storage Access
                        // Framework picker or block the implicit intent —
                        // OpenDocumentTree throws ActivityNotFoundException
                        // there. Surface a friendly snackbar instead of
                        // crashing or doing nothing.
                        runCatching { chooseBackupFolder.launch(null) }
                            .onFailure {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        ctx.getString(R.string.settings_no_file_picker_available_device)
                                    )
                                }
                            }
                    }
                )
                if (localBackupStatus.lastCopyFailedAt > 0L) {
                    NoticeBanner(
                        text = stringResource(R.string.settings_pettibox_made_backup_but_couldnt),
                        action = stringResource(R.string.settings_choose_folder),
                        onAction = { chooseBackupFolder.launch(null) },
                        pose = KeeperPose.BackupWarning,
                        isError = true
                    )
                }

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        scope.launch {
                            // One button, every destination: the zip always
                            // lands on this phone (and the extra folder if
                            // set); the Drive upload rides on top when
                            // connected.
                            if (driveBackupStatus.enabled) {
                                uploadToDriveNow()
                            } else {
                                busyLabel = ctx.getString(R.string.settings_backing_up)
                                runCatching { viewModel.createLocalBackupNow() }
                                    .onSuccess { (_, result) ->
                                        snackbarHostState.showSnackbar(
                                            backupSummaryMessage(ctx.resources, R.string.settings_backed_up_summary, result)
                                        )
                                    }
                                    .onFailure {
                                        snackbarHostState.showSnackbar(ctx.getString(R.string.settings_couldnt_make_backup))
                                    }
                                busyLabel = null
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_back_up_now), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showRestoreChooser = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_restore_backup), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busyLabel = ctx.getString(R.string.settings_preparing_export)
                            runCatching { viewModel.exportBackupZipFile() }
                                .onSuccess { (file, result) ->
                                    if (!shareBackupFile(ctx, file)) {
                                        snackbarHostState.showSnackbar(ctx.getString(R.string.settings_couldnt_export_backup))
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            backupSummaryMessage(ctx.resources, R.string.settings_backup_includes_summary, result)
                                        )
                                    }
                                }
                                .onFailure {
                                    snackbarHostState.showSnackbar(ctx.getString(R.string.settings_couldnt_export_backup))
                                }
                            busyLabel = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_export_share_backup_file), fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = stringResource(R.string.settings_move_between_apps)) {
                Text(
                    stringResource(R.string.settings_coming_chrome_firefox_raindrop_io_2),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        // Same OEM guard as the backup pickers — some builds
                        // ship without a SAF document picker.
                        runCatching {
                            importBookmarks.launch(
                                arrayOf(
                                    "text/html",
                                    "text/csv",
                                    "text/comma-separated-values",
                                    "text/plain",
                                    "*/*"
                                )
                            )
                        }.onFailure {
                            scope.launch {
                                snackbarHostState.showSnackbar(ctx.getString(R.string.settings_no_file_picker_available_device_2))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_import_bookmarks_file), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busyLabel = ctx.getString(R.string.settings_preparing_csv)
                            runCatching { viewModel.exportCsvFile() }
                                .onSuccess { (file, links) ->
                                    when {
                                        links == 0 -> snackbarHostState.showSnackbar(
                                            ctx.getString(R.string.settings_no_links_export_yet_csv)
                                        )
                                        !shareCsvFile(ctx, file) -> snackbarHostState.showSnackbar(
                                            ctx.getString(R.string.settings_couldnt_export_csv)
                                        )
                                        else -> snackbarHostState.showSnackbar(
                                            ctx.resources.getQuantityString(R.plurals.settings_csv_exported, links, links)
                                        )
                                    }
                                }
                                .onFailure {
                                    snackbarHostState.showSnackbar(ctx.getString(R.string.settings_couldnt_export_csv))
                                }
                            busyLabel = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_export_links_csv), fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = stringResource(R.string.settings_about)) {
                Text(
                    stringResource(R.string.settings_enjoying_pettibox_ran_into_something),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        if (!HelpLinks.openPlayListing(ctx)) {
                            scope.launch {
                                snackbarHostState.showSnackbar(ctx.getString(R.string.settings_couldnt_open_play_store_device))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_rate_pettibox), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        if (!HelpLinks.openSupportEmail(ctx)) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    ctx.getString(R.string.settings_no_email_app_found_write, HelpLinks.SUPPORT_EMAIL)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_email_support), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        if (!HelpLinks.shareApp(ctx)) {
                            scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.settings_couldnt_open_share_sheet)) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_share_pettibox_friend), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showLicenses = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_open_source_licenses))
                }
                if (showLicenses) LicensesSheet(onDismiss = { showLicenses = false })
                Text(
                    "PettiBox ${BuildConfig.VERSION_NAME} · ${HelpLinks.SUPPORT_EMAIL}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            // Outer NavGraph Scaffold already pads for the bottom nav, and
            // this screen's Scaffold uses WindowInsets(0) to avoid double
            // padding — so this trailing spacer just adds breathing room
            // below the last section. The earlier .navigationBarsPadding()
            // chained after .height() was double-counting the inset.
            Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** One "where your backups go" line: icon, name, live status, optional trailing action. */
@Composable
private fun BackupDestinationRow(
    icon: ImageVector,
    title: String,
    caption: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                Text(
                    secondaryActionLabel,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable(onClick = onSecondaryAction)
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Tappable source option inside the "Restore a backup" chooser dialog. */
@Composable
private fun RestoreSourceRow(
    icon: ImageVector,
    title: String,
    caption: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WorkInProgressBanner(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun BackupConfidenceBanner(status: LocalBackupStatus) {
    val hasBackup = status.lastBackupAt > 0L
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(
            Icons.Rounded.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (hasBackup) stringResource(R.string.settings_last_backup, formatBackupTime(status.lastBackupAt))
                else if (status.enabled) stringResource(R.string.settings_safety_copy)
                else stringResource(R.string.settings_safety_copy_off),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                when {
                    status.lastCopyFailedAt > 0L -> stringResource(R.string.settings_pettibox_made_private_copy_but)
                    hasBackup && status.folderUri.isNotBlank() -> stringResource(R.string.settings_also_copied_folder_chose)
                    hasBackup -> stringResource(R.string.settings_stored_privately_device)
                    status.enabled -> stringResource(R.string.settings_pettibox_will_make_copy_overnight)
                    else -> stringResource(R.string.settings_turn_automatic_safety_copy_below)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoticeBanner(
    text: String,
    action: String,
    onAction: () -> Unit,
    pose: KeeperPose = KeeperPose.Error,
    isError: Boolean = false
) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        KeeperMascot(
            size = 40.dp,
            accent = color,
            pose = pose,
            modifier = Modifier.padding(end = 10.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onAction) {
            Text(action, color = color)
        }
    }
}

@Composable
private fun HelpFlow() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(12.dp)
    ) {
        Text(
            stringResource(R.string.settings_main_idea),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        HelpStep(number = "1", title = stringResource(R.string.settings_save), body = stringResource(R.string.settings_tap_share_any_app_choose))
        HelpStep(number = "2", title = stringResource(R.string.settings_organize), body = stringResource(R.string.settings_pick_collection_add_notes_tags))
        HelpStep(number = "3", title = stringResource(R.string.settings_find), body = stringResource(R.string.settings_search_words_source_type_tag))
        HelpStep(number = "4", title = stringResource(R.string.settings_protect), body = stringResource(R.string.settings_archive_instead_deleting_keep_automatic))
    }
}

@Composable
private fun HelpStep(
    number: String,
    title: String,
    body: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Subhead inside the Help section. Smaller and quieter than a section
 * title — just enough to break a long Help block into scannable groups
 * without making the page look like a TOC.
 */
@Composable
private fun HelpGroupTitle(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun HelpItem(
    title: String,
    body: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageLimitChoices(selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OcrPreferences.PDF_PAGE_LIMIT_OPTIONS.forEach { limit ->
            PageLimitChoice(limit = limit, selected = selected == limit, onClick = { onSelect(limit) })
        }
    }
}

@Composable
private fun PageLimitChoice(
    limit: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = if (selected) {
        ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.primary
        )
    } else {
        ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    }
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = colors
    ) {
        Text(if (limit == OcrPreferences.ALL_PDF_PAGES) stringResource(R.string.settings_pdf_limit_all) else limit.toString(), fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LockAfterChoices(selectedMs: Long, onSelect: (Long) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AppLockPreferences.LOCK_AFTER_OPTIONS.forEach { (label, ms) ->
            LockAfterChoice(label = stringResource(label), selected = selectedMs == ms, onClick = { onSelect(ms) })
        }
    }
}

@Composable
private fun LockAfterChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = if (selected) {
        ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.primary
        )
    } else {
        ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    }
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = colors,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Which preset anchor the reminder-time dialog is editing. */
private enum class ReminderTimeTarget { MORNING, EVENING }

/** Formats an hour/minute into the user's 12/24-hour locale clock, e.g. "9:00 PM". */
/** SQLite LENGTH() counts characters; close enough to bytes for a size hint. */
private fun formatStorage(bytes: Long): String = when {
    bytes < 1024 * 1024 -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private fun formatClock(hour: Int, minute: Int): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }
    // The app language's own clock ("9:00 PM", "21:00").
    val locale = Locale.getDefault()
    return SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "jmm"), locale).format(cal.time)
}

/** Tappable Settings row showing a reminder anchor and its current time. */
@Composable
private fun ReminderTimeRow(
    icon: ImageVector,
    label: String,
    sub: String,
    time: String,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = scheme.onSurface
            )
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            time,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = scheme.primary
        )
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Modal clock picker for a reminder anchor. Uses the same Material3
 * [TimePicker] as the custom-reminder sheet, wrapped in a plain Dialog so the
 * clock face has room (an AlertDialog's content slot clips it on small phones).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimeDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val timeState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                TimePicker(state = timeState)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(timeState.hour, timeState.minute) },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.settings_set), fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

/**
 * Compact in-Settings collection manager. Lists every collection —
 * starters included, since those are fully renameable/deletable now —
 * with the user's own shelves first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectionsManager(
    collections: List<CategoryEntity>,
    onCreateClick: () -> Unit,
    onEditCollection: (CategoryEntity) -> Unit
) {
    val customCollections = remember(collections) {
        collections.sortedWith(
            compareByDescending<CategoryEntity> { it.userCreated }
                .thenByDescending { it.createdAt }
                .thenBy { it.sortOrder }
        )
    }
    Text(
        if (customCollections.isEmpty()) {
            stringResource(R.string.settings_no_collections_yet_create_one)
        } else {
            stringResource(R.string.settings_tap_any_collection_rename_change)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (customCollections.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            customCollections.forEach { c ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(androidx.compose.ui.graphics.Color(c.colorHex).copy(alpha = 0.16f))
                        .clickable { onEditCollection(c) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(c.emoji, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        c.displayName,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Button(
        onClick = onCreateClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.settings_create_collection), fontWeight = FontWeight.Bold)
    }
}

/** Every app language, each named in its own script; "System" follows the phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppLanguageChoices(selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AppLocale.supported.forEach { (tag, name) ->
            val isSelected = selected == tag
            OutlinedButton(
                onClick = { onSelect(tag) },
                shape = RoundedCornerShape(12.dp),
                colors = if (isSelected) {
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                } else {
                    ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                }
            ) {
                Text(
                    if (tag.isEmpty()) stringResource(R.string.settings_language_system) else name,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun ThemeChoice(
    mode: ThemeMode,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = if (selected) {
        ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.primary
        )
    } else {
        ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    }

    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = colors
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(mode.label(), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
        }
    }
}

/** A restore the user picked but hasn't confirmed yet (shelf wasn't empty). */
private sealed interface PendingRestore {
    data class FromFile(val uri: Uri) : PendingRestore
    data class FromDrive(val backup: DriveBackupFile) : PendingRestore
    data class FromLocal(val file: File) : PendingRestore
}

private fun shareBackupFile(ctx: Context, file: File): Boolean =
    shareExportFile(ctx, file, "application/zip", ctx.getString(R.string.settings_share_backup_subject), ctx.getString(R.string.settings_share_backup_chooser))

private fun shareCsvFile(ctx: Context, file: File): Boolean =
    shareExportFile(ctx, file, "text/csv", ctx.getString(R.string.settings_share_links_subject), ctx.getString(R.string.settings_export_links_csv))

private fun shareExportFile(
    ctx: Context,
    file: File,
    mime: String,
    subject: String,
    chooserTitle: String
): Boolean {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        clipData = ClipData.newUri(ctx.contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching {
        ctx.startActivity(Intent.createChooser(intent, chooserTitle))
    }.isSuccess
}

private fun android.content.res.Resources.count(id: Int, n: Int): String = getQuantityString(id, n, n)

/** "[prefix] 12 saves, 3 files, 2 favorites…": [prefix] is a string resource ending where the list starts. */
private fun backupSummaryMessage(
    res: android.content.res.Resources,
    prefix: Int,
    result: SaveRepository.BackupExportResult
): String {
    val parts = buildList {
        add(res.count(R.plurals.count_saves, result.saves))
        add(res.count(R.plurals.count_files, result.embeddedFiles))
        if (result.favorites > 0) add(res.count(R.plurals.count_favorites, result.favorites))
        if (result.archived > 0) add(res.count(R.plurals.count_archived, result.archived))
        if (result.tags > 0) add(res.count(R.plurals.count_tags, result.tags))
    }
    return res.getString(prefix, parts.joinToString(res.getString(R.string.list_separator)))
}

private fun restoreResultMessage(res: android.content.res.Resources, result: SaveRepository.BackupImportResult): String {
    if (result.saves == 0 && result.skippedDuplicates > 0) {
        return res.getString(R.string.settings_restore_nothing_new)
    }
    val parts = listOf(
        res.count(R.plurals.count_saves, result.saves),
        res.count(R.plurals.count_collections, result.categories),
        res.count(R.plurals.count_tags, result.tags)
    ).joinToString(res.getString(R.string.list_separator))
    val base = res.getString(R.string.settings_restored_summary, parts)
    return if (result.skippedDuplicates > 0) {
        res.getString(R.string.settings_restored_with_skipped, base, res.count(R.plurals.settings_already_on_shelf, result.skippedDuplicates))
    } else base
}

private fun bookmarkImportMessage(res: android.content.res.Resources, result: SaveRepository.BookmarkImportResult): String {
    if (result.imported == 0) {
        return res.getString(R.string.settings_import_nothing_new)
    }
    val parts = buildList {
        add(res.count(R.plurals.settings_imported_links, result.imported))
        if (result.newCollections > 0) add(res.count(R.plurals.settings_new_collections, result.newCollections))
        if (result.skippedDuplicates > 0) add(res.count(R.plurals.settings_already_saved, result.skippedDuplicates))
    }
    return parts.joinToString(res.getString(R.string.list_separator))
}

private fun formatBackupTime(timestamp: Long): String {
    val locale = Locale.getDefault()
    return SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMdjmm"), locale).format(Date(timestamp))
}
