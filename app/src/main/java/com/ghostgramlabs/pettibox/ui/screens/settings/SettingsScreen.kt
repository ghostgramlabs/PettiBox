package com.ghostgramlabs.pettibox.ui.screens.settings

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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RestoreFromTrash
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
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val autoScanOcr by viewModel.autoScanOcr.collectAsStateWithLifecycle(initialValue = true)
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
                        snackbarHostState.showSnackbar("Updated ${updated.emoji} ${updated.name}")
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
            title = { Text("Delete ${target.name}?") },
            text = {
                Text(
                    "Saves in this collection will stay in PettiBox — they'll just no longer belong to a collection. You can move them somewhere else from each save's detail screen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = target.name
                    val emoji = target.emoji
                    deletingCollection = null
                    viewModel.deleteCollection(target) {
                        scope.launch {
                            snackbarHostState.showSnackbar("Deleted $emoji $name")
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingCollection = null }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showRemoveOfflineCopies) {
        AlertDialog(
            onDismissRequest = { showRemoveOfflineCopies = false },
            title = { Text("Remove offline copies?") },
            text = {
                Text(
                    "Your saved links stay exactly as they are — only the article copies kept for offline reading are deleted. " +
                        "You can save a copy again from any link."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveOfflineCopies = false
                    scope.launch {
                        viewModel.removeAllOfflineCopies()
                        snackbarHostState.showSnackbar("Offline copies removed")
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveOfflineCopies = false }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showRemoveStarters) {
        AlertDialog(
            onDismissRequest = { showRemoveStarters = false },
            title = { Text("Remove starter collections?") },
            text = {
                Text(
                    "Empty starter collections will be removed in one go, and they won't come back. " +
                        "Starters that hold saves are kept so nothing loses its place — you can empty or delete those individually. " +
                        "Collections you created yourself aren't touched."
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
                                    "No empty starters to remove — ${result.keptWithSaves} hold saves"
                                result.keptWithSaves > 0 ->
                                    "Removed ${result.removed} starter collections · ${result.keptWithSaves} kept (they hold saves)"
                                else ->
                                    "Removed ${result.removed} starter collections"
                            }
                        )
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveStarters = false }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    editingReminderTime?.let { target ->
        val current = if (target == ReminderTimeTarget.MORNING) morningReminderTime else eveningReminderTime
        ReminderTimeDialog(
            title = if (target == ReminderTimeTarget.MORNING) "Set morning time" else "Set evening time",
            initialHour = current.hour,
            initialMinute = current.minute,
            onDismiss = { editingReminderTime = null },
            onConfirm = { hour, minute ->
                editingReminderTime = null
                val label = if (target == ReminderTimeTarget.MORNING) {
                    viewModel.setMorningReminderTime(hour, minute); "Morning"
                } else {
                    viewModel.setEveningReminderTime(hour, minute); "Evening"
                }
                scope.launch {
                    snackbarHostState.showSnackbar("$label time set to ${formatClock(hour, minute)}")
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
                        snackbarHostState.showSnackbar("A collection named \"${newCollection.name}\" already exists")
                    }
                    return@CreateCollectionDialog
                }
                viewModel.createCollection(newCollection) { created ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "Created ${created.emoji} ${created.name}"
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
            busyLabel = "Importing backup"
            runCatching { viewModel.importBackupUri(uri) }
                .onSuccess { result ->
                    snackbarHostState.showSnackbar(restoreResultMessage(result))
                    if (result.saves > 0) askForReview(RatingPreferences.MOMENT_RESTORE)
                }
                .onFailure {
                    snackbarHostState.showSnackbar("That backup file couldn't be imported")
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

    // ── Google Drive: connect, upload-now, restore ────────────────────────
    var driveRestoreChoices by remember { mutableStateOf<List<DriveBackupFile>?>(null) }

    val runDriveRestore: (DriveBackupFile) -> Unit = { backup ->
        scope.launch {
            busyLabel = "Restoring from Google Drive"
            runCatching { viewModel.restoreFromDrive(backup.id) }
                .onSuccess { result ->
                    snackbarHostState.showSnackbar(restoreResultMessage(result))
                    if (result.saves > 0) askForReview(RatingPreferences.MOMENT_RESTORE)
                }
                .onFailure {
                    snackbarHostState.showSnackbar("Couldn't restore that backup — try again")
                }
            busyLabel = null
        }
    }

    val uploadToDriveNow: suspend () -> Unit = {
        busyLabel = "Backing up"
        val outcome = runCatching { viewModel.backupToDriveNow() }
            .getOrElse { DriveBackupManager.UploadOutcome.Failed(it) }
        when (outcome) {
            is DriveBackupManager.UploadOutcome.Uploaded ->
                snackbarHostState.showSnackbar("Backed up — copy saved on this phone and in your Google Drive")
            DriveBackupManager.UploadOutcome.NeedsReconnect ->
                snackbarHostState.showSnackbar("Saved on this phone. Google Drive needs reconnecting — tap Connect again")
            is DriveBackupManager.UploadOutcome.Failed ->
                snackbarHostState.showSnackbar("Saved on this phone, but the Drive upload failed — check your connection")
        }
        busyLabel = null
    }

    val driveConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        scope.launch {
            if (result.resultCode == Activity.RESULT_OK && viewModel.completeDriveConnect(result.data)) {
                snackbarHostState.showSnackbar("Google Drive connected")
                uploadToDriveNow()
            } else {
                snackbarHostState.showSnackbar("Google Drive connection was cancelled")
            }
        }
    }

    val connectDrive: () -> Unit = {
        scope.launch {
            busyLabel = "Connecting Google Drive"
            runCatching { viewModel.beginDriveConnect() }
                .onSuccess { step ->
                    busyLabel = null
                    when (step) {
                        SettingsViewModel.DriveConnectStep.Connected -> {
                            snackbarHostState.showSnackbar("Google Drive connected")
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
                        "Couldn't reach Google Play services — is this device signed in to Google?"
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
            busyLabel = "Switching Google account"
            runCatching { viewModel.switchDriveAccount(accountName) }
                .onSuccess { step ->
                    busyLabel = null
                    when (step) {
                        SettingsViewModel.DriveConnectStep.Connected -> {
                            snackbarHostState.showSnackbar("Google Drive connected as $accountName")
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
                    snackbarHostState.showSnackbar("Couldn't switch Google account")
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
                snackbarHostState.showSnackbar("No account chooser available on this device")
            }
        }
    }

    val openDriveRestore: () -> Unit = {
        scope.launch {
            busyLabel = "Checking Google Drive"
            val backups = runCatching { viewModel.listDriveBackups() }.getOrNull()
            busyLabel = null
            when {
                backups == null -> snackbarHostState.showSnackbar(
                    "Couldn't reach Google Drive — reconnect and try again"
                )
                backups.isEmpty() -> snackbarHostState.showSnackbar(
                    "No Drive backups yet — tap \"Back up now\" first"
                )
                else -> driveRestoreChoices = backups
            }
        }
    }

    if (showRestoreChooser) {
        AlertDialog(
            onDismissRequest = { showRestoreChooser = false },
            title = { Text("Restore a backup") },
            text = {
                Column {
                    RestoreSourceRow(
                        icon = Icons.Rounded.Cloud,
                        title = "From Google Drive",
                        caption = if (driveBackupStatus.enabled) {
                            "Pick one of your cloud copies"
                        } else {
                            "Connect Google Drive first — new phone? Your copies are waiting there"
                        },
                        onClick = {
                            showRestoreChooser = false
                            if (driveBackupStatus.enabled) openDriveRestore() else connectDrive()
                        }
                    )
                    RestoreSourceRow(
                        icon = Icons.Rounded.FolderOpen,
                        title = "From a file",
                        caption = "Pick a PettiBox backup zip from this phone",
                        onClick = {
                            showRestoreChooser = false
                            importBackup.launch(arrayOf("application/zip", "application/json", "text/*", "*/*"))
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showRestoreChooser = false }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    pendingRestore?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore on top of your shelf?") },
            text = {
                Text(
                    "You already have $totalSaves saves. Restoring adds the backup's items alongside them — " +
                        "anything already on your shelf is skipped, so nothing gets duplicated."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    when (pending) {
                        is PendingRestore.FromFile -> runFileRestore(pending.uri)
                        is PendingRestore.FromDrive -> runDriveRestore(pending.backup)
                    }
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    driveRestoreChoices?.let { backups ->
        AlertDialog(
            onDismissRequest = { driveRestoreChoices = null },
            title = { Text("Restore from Google Drive") },
            text = {
                Column {
                    Text(
                        "Pick a backup to bring onto this device. Your current saves stay — the backup's items are added alongside them, and anything already here is skipped.",
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
                TextButton(onClick = { driveRestoreChoices = null }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    val importBookmarks = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                busyLabel = "Importing bookmarks"
                runCatching { viewModel.importBookmarksUri(uri) }
                    .onSuccess { result ->
                        snackbarHostState.showSnackbar(bookmarkImportMessage(result))
                        if (result.imported > 0) askForReview(RatingPreferences.MOMENT_IMPORT)
                    }
                    .onFailure {
                        snackbarHostState.showSnackbar(
                            "Couldn't read that file. Export from your bookmark app as HTML or CSV, then try again."
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
                    .onSuccess { snackbarHostState.showSnackbar("Backup folder selected") }
                    .onFailure { snackbarHostState.showSnackbar("Couldn't use that folder") }
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
                title = "Make it yours",
                subtitle = "Your shelf settings, safety copies, and quick help."
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
            Spacer(Modifier.height(16.dp))
            SettingsSection(title = "Appearance") {
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
            SettingsSection(title = "Collections") {
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
                        Text("Remove starter collections", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = "Text recognition") {
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
                            "Auto-scan images and PDFs for search",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Applies to new saves only. English text works best; other languages may be partial.",
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
                                    if (enabled) "Auto-scan is on for new saves"
                                    else "Auto-scan is off for new saves"
                                )
                            }
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busyLabel = "Queueing text scan"
                            runCatching { viewModel.scanExistingSaves() }
                                .onSuccess { count ->
                                    snackbarHostState.showSnackbar(
                                        if (count == 0) "No older images or PDFs need scanning"
                                        else "Scanning existing saves for search: queued $count item${if (count == 1) "" else "s"}"
                                    )
                                }
                                .onFailure { snackbarHostState.showSnackbar("Couldn't queue text scan") }
                            busyLabel = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("Scan existing saves", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "PDF scan limit",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Higher limits make more pages searchable, but large PDFs take longer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OcrPreferences.PDF_PAGE_LIMIT_OPTIONS.forEach { limit ->
                        PageLimitChoice(
                            limit = limit,
                            selected = pdfPageLimit == limit,
                            onClick = {
                                scope.launch {
                                    viewModel.setPdfPageLimit(limit)
                                    snackbarHostState.showSnackbar("PDF scan limit set to $limit pages")
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = "Offline reading") {
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
                            "Save articles to read offline",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "When you save a link, keep a copy of the article's text on this phone — readable without internet, even if the page changes or disappears. Text only, so it takes little space.",
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
                                    if (enabled) "New links will be saved for offline reading"
                                    else "New links won't be saved offline — you can still save one from its page"
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
                                    if (queued == 0) "None of your older links can be saved offline"
                                    else "Saving $queued link${if (queued == 1) "" else "s"} in the background — keep using the app"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text("Save older links too ($linksWithoutCopy)", fontWeight = FontWeight.Bold)
                    }
                }
                if (offlineCopyStats.count > 0) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${offlineCopyStats.count} article${if (offlineCopyStats.count == 1) "" else "s"} saved · " +
                                formatStorage(offlineCopyStats.bytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showRemoveOfflineCopies = true }) {
                            Text("Remove all", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = "Reminders") {
                Text(
                    "Default reminder times",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "When you tap a quick reminder, this is the time of day it lands on. You can always pick an exact date and time with \"Custom\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                ReminderTimeRow(
                    icon = Icons.Rounded.LightMode,
                    label = "Morning time",
                    sub = "Used by Tomorrow, This weekend & Next week",
                    time = formatClock(morningReminderTime.hour, morningReminderTime.minute),
                    onClick = { editingReminderTime = ReminderTimeTarget.MORNING }
                )
                Spacer(Modifier.height(8.dp))
                ReminderTimeRow(
                    icon = Icons.Rounded.DarkMode,
                    label = "Evening time",
                    sub = "Used by Tonight",
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
                            "Weekly shelf nudge",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Saturday morning, one thing you saved but haven't opened yet. Silent, and skipped when there's nothing unread.",
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
                                        if (enabled) "You'll get one nudge on Saturday mornings"
                                        else "Weekly nudge turned off"
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
                            "On-time reminders",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (hasExactAlarmPermission) {
                                "Reminders fire at the exact time you set."
                            } else {
                                "Android may delay reminders by a few minutes. Turn on exact alarms for on-the-dot delivery."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!hasExactAlarmPermission) {
                    Spacer(Modifier.height(12.dp))
                    NoticeBanner(
                        text = "For on-the-dot reminders, allow Alarms & reminders in Android settings.",
                        action = "Open settings",
                        pose = KeeperPose.Reminder,
                        onAction = {
                            if (!viewModel.openExactAlarmSettings()) {
                                scope.launch { snackbarHostState.showSnackbar("Couldn't open alarm settings") }
                            }
                            hasExactAlarmPermission = viewModel.hasExactAlarmPermission()
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = "Help") {
                HelpFlow()
                Spacer(Modifier.height(12.dp))
                // The share-sheet flow is the app's core gesture and the
                // hardest to explain in words — lead with the video.
                OutlinedButton(
                    onClick = {
                        if (!HelpLinks.openShareDemo(ctx)) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Couldn't open the video — no browser on this device")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Watch: save from any app", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showHelpDetails = !showHelpDetails },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (showHelpDetails) "Hide detailed guide" else "Show detailed guide",
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
                HelpGroupTitle("The basics")
                HelpItem(
                    title = "Save from any app",
                    body = "Tap Share in another app, choose PettiBox, pick a collection, then save. You can also highlight text in any app and choose \"Save to PettiBox\" from the selection menu. Add a note or reminder only when you need it.",
                    icon = Icons.Rounded.Share
                )
                HelpItem(
                    title = "Add from PettiBox",
                    body = "Tap the + button on Home to add a note, paste a link, choose pictures, or pick a file.",
                    icon = Icons.Rounded.PhoneAndroid
                )
                HelpItem(
                    title = "Find it later",
                    body = "Search by words, source app, file type, tag, collection, or upcoming reminders. It also checks English text found inside images and PDFs, and the text of articles saved for offline reading.",
                    icon = Icons.Rounded.Search
                )
                HelpItem(
                    title = "Make a new collection",
                    body = "Use Collections above to create or rename shelves. New collections show up in Browse and in the save sheet.",
                    icon = Icons.Rounded.GridView
                )

                Spacer(Modifier.height(14.dp))
                HelpGroupTitle("Get more out of it")
                HelpItem(
                    title = "Long-press for quick actions",
                    body = "Press and hold any save to pin, favorite, archive, set a reminder, move it, copy a link, or delete it.",
                    icon = Icons.Rounded.TouchApp
                )
                HelpItem(
                    title = "Love it — see all favorites",
                    body = "Tap the heart on a save (or long-press → ❤️). Open Browse → Favorites to see every save you've hearted, all in one place.",
                    icon = Icons.Rounded.Favorite
                )
                HelpItem(
                    title = "Tags cut across collections",
                    body = "A save lives in one collection, but can carry many tags. Add tags from the save sheet or the detail screen. Open Browse → Tags to see every tag with its save count.",
                    icon = Icons.Rounded.LocalOffer
                )
                HelpItem(
                    title = "Edit anytime — it saves itself",
                    body = "Open any save to change its title, note, or tags. Edits save automatically when you tap away or leave the screen, and a quick Undo appears in case you change your mind.",
                    icon = Icons.Rounded.Edit
                )
                HelpItem(
                    title = "Unread: your read-later list",
                    body = "Anything you save stays in Browse → Unread until you open it, newest first. Once a week (Saturday morning) PettiBox can nudge you about one of them — turn that off under Reminders above.",
                    icon = Icons.Rounded.AutoStories
                )
                HelpItem(
                    title = "Widget and app-icon shortcuts",
                    body = "Long-press the PettiBox icon for New note, Add a link, Search, or Unread. Add the PettiBox widget to your home screen (long-press an empty spot → Widgets) to see unread saves and add new ones in one tap.",
                    icon = Icons.Rounded.Widgets
                )
                HelpItem(
                    title = "Archive when you're done",
                    body = "Archived saves disappear from Home but stay searchable. Use Archive instead of Delete when you may need something again.",
                    icon = Icons.Rounded.Archive
                )
                HelpItem(
                    title = "Remind me later",
                    body = "Tap the clock on any save or in the save sheet. Quick reminders use your Morning and Evening times above; Custom lets you pick the exact date and time.",
                    icon = Icons.Rounded.AccessTime
                )
                HelpItem(
                    title = "Read links offline",
                    body = "When you save a link, PettiBox keeps a copy of the article's text. Open the save and tap \"Read offline\" — it works with no internet, even if the page is later changed or taken down. The copy is made in the background whenever you're online. Videos and social posts (YouTube, Instagram, X) don't have copies. Turn this off, or save older links too, under Offline reading above.",
                    icon = Icons.AutoMirrored.Rounded.MenuBook
                )
                HelpItem(
                    title = "\"No offline copy\"?",
                    body = "Some pages can't be copied: paywalled or login-only articles, pages that no longer exist, and very long pages. Tap \"Try again\" on the save — or \"Open original\" to read it on the web.",
                    icon = Icons.AutoMirrored.Rounded.MenuBook
                )
                HelpItem(
                    title = "Zoom pictures, read PDFs",
                    body = "Tap a picture to open it full screen, then pinch or double-tap to zoom and drag to look around. PDFs open right inside PettiBox: scroll through the pages, pinch to zoom, or tap the open-in-app icon to use another PDF app.",
                    icon = Icons.Rounded.ZoomIn
                )
                HelpItem(
                    title = "Text recognition is optional",
                    body = "Auto-scan makes English screenshots and PDFs searchable. Other languages may be partial. Large PDFs are indexed for the first 30 pages to keep the app fast.",
                    icon = Icons.AutoMirrored.Rounded.TextSnippet
                )

                Spacer(Modifier.height(14.dp))
                HelpGroupTitle("Backups & safety")
                HelpItem(
                    title = "Share and remove safely",
                    body = "Open a saved item to share one attachment from a group, swipe through items, or remove an attachment with Undo.",
                    icon = Icons.Rounded.Share
                )
                HelpItem(
                    title = "Deleted something by mistake?",
                    body = "Deleted saves wait in Browse → Recently deleted for 30 days. Tap one to restore it, or delete it for good. After 30 days they're removed automatically.",
                    icon = Icons.Rounded.RestoreFromTrash
                )
                HelpItem(
                    title = "Lock PettiBox",
                    body = "Turn on App lock under Privacy to require your fingerprint, face, or phone PIN. Pick how soon it locks again after you leave the app (a minute by default). The widget shows titles only while PettiBox is unlocked, and the weekly nudge never shows them.",
                    icon = Icons.Rounded.Lock
                )
                HelpItem(
                    title = "Back up your shelf",
                    body = "Every backup always keeps a copy on this phone; connect Google Drive (and optionally an extra folder) to send the same copy there too. \"Back up now\" fills every destination in one tap. Offline article copies are included, so they come back on a new phone too.",
                    icon = Icons.Rounded.Download
                )
                HelpItem(
                    title = "Restore from a backup",
                    body = "New phone, or need to recover? Tap \"Restore a backup\" in the Backup section and choose Google Drive or a backup file — everything comes back either way.",
                    icon = Icons.Rounded.FolderOpen
                )
                HelpItem(
                    title = "Bring bookmarks from other apps",
                    body = "Coming from Chrome, Firefox, Raindrop.io, or Pocket? Export your bookmarks there (HTML or CSV), then use \"Import bookmarks file\" below. Folders and tags carry over.",
                    icon = Icons.Rounded.FolderOpen
                )
                HelpItem(
                    title = "Back up to your Google Drive",
                    body = "Connect Google Drive in the Backup section and every nightly safety copy also uploads to a \"PettiBox Backups\" folder in your own Drive. PettiBox can only see files it created — never the rest of your Drive. Wrong account connected? Tap \"Switch account\" under Google Drive to pick a different one.",
                    icon = Icons.Rounded.Cloud
                )
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = "Privacy") {
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
                            "Lock PettiBox",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Ask for your fingerprint, face, or phone PIN to open PettiBox or save into it. The widget shows titles only while PettiBox is unlocked, and notifications hide them.",
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
                                    snackbarHostState.showSnackbar("App lock is off")
                                }
                                !AppLockSession.isAvailable(ctx) -> scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Set up a screen lock (PIN, pattern, or fingerprint) in your phone's settings first"
                                    )
                                }
                                // Confirm it works before switching it on, so no one locks themselves out.
                                activity != null -> AppLockSession.prompt(
                                    activity,
                                    title = "Turn on App lock",
                                    onSuccess = {
                                        scope.launch {
                                            viewModel.setAppLock(true)
                                            snackbarHostState.showSnackbar("App lock is on")
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
                        "Paused: this phone has no screen lock right now, so PettiBox opens without asking. Set a PIN, pattern, or fingerprint in your phone's settings and the lock comes back on its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (appLockOn) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Lock after leaving",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    LockAfterChoices(
                        selectedMs = appLockAfterMs,
                        onSelect = { ms -> scope.launch { viewModel.setAppLockAfterMs(ms) } }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = "Backup") {
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
                            "Automatic nightly backup",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (localBackupStatus.lastBackupAt > 0L) {
                                "Runs overnight. Last backup: ${formatBackupTime(localBackupStatus.lastBackupAt)}."
                            } else {
                                "Runs overnight — every place below gets a fresh copy."
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
                                    if (enabled) "Automatic nightly backup is on"
                                    else "Automatic nightly backup is off"
                                )
                            }
                        }
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Where your backups go",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                BackupDestinationRow(
                    icon = Icons.Rounded.PhoneAndroid,
                    title = "This phone",
                    caption = "Always included — keeps the 3 newest copies in PettiBox storage."
                )
                BackupDestinationRow(
                    icon = Icons.Rounded.Cloud,
                    title = "Google Drive",
                    caption = when {
                        !driveBackupStatus.enabled ->
                            "Not connected. Cloud copies survive a lost or broken phone."
                        driveBackupStatus.accountEmail.isNotBlank() && driveBackupStatus.lastBackupAt > 0L ->
                            "${driveBackupStatus.accountEmail} · Last upload: ${formatBackupTime(driveBackupStatus.lastBackupAt)}"
                        driveBackupStatus.accountEmail.isNotBlank() ->
                            "${driveBackupStatus.accountEmail} · Uploads with tonight's backup"
                        driveBackupStatus.lastBackupAt > 0L ->
                            "Connected · Last upload: ${formatBackupTime(driveBackupStatus.lastBackupAt)}"
                        else -> "Connected · Uploads with tonight's backup"
                    },
                    actionLabel = if (driveBackupStatus.enabled) "Disconnect" else "Connect",
                    onAction = {
                        if (driveBackupStatus.enabled) {
                            scope.launch {
                                viewModel.disconnectDrive()
                                snackbarHostState.showSnackbar(
                                    "Google Drive disconnected. Copies already uploaded stay in your Drive."
                                )
                            }
                        } else {
                            connectDrive()
                        }
                    },
                    secondaryActionLabel = if (driveBackupStatus.enabled) "Switch account" else null,
                    onSecondaryAction = if (driveBackupStatus.enabled) switchDriveAccount else null
                )
                if (driveBackupStatus.enabled && driveBackupStatus.needsReconnectAt > 0L) {
                    NoticeBanner(
                        text = "PettiBox lost access to your Google Drive, so cloud copies are paused. Reconnect to resume them.",
                        action = "Reconnect",
                        onAction = connectDrive,
                        pose = KeeperPose.BackupWarning,
                        isError = true
                    )
                    Spacer(Modifier.height(6.dp))
                } else if (driveBackupStatus.enabled && driveBackupStatus.lastFailedAt > 0L) {
                    Text(
                        "Last Drive upload didn't finish — maybe offline. It retries with tonight's backup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                }
                BackupDestinationRow(
                    icon = Icons.Rounded.FolderOpen,
                    title = "Extra folder",
                    caption = if (localBackupStatus.folderUri.isBlank()) {
                        "Optional — also drop each copy into a folder you pick."
                    } else {
                        "Each backup is also copied to your chosen folder."
                    },
                    actionLabel = if (localBackupStatus.folderUri.isBlank()) "Choose" else "Change",
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
                                        "No file picker available on this device. Backups will stay in PettiBox storage."
                                    )
                                }
                            }
                    }
                )
                if (localBackupStatus.lastCopyFailedAt > 0L) {
                    NoticeBanner(
                        text = "PettiBox made a backup, but couldn't copy it to your extra folder. Pick the folder again if it moved or permissions changed.",
                        action = "Choose folder",
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
                                busyLabel = "Backing up"
                                runCatching { viewModel.createLocalBackupNow() }
                                    .onSuccess { (_, result) ->
                                        snackbarHostState.showSnackbar(
                                            backupSummaryMessage("Backed up on this phone:", result)
                                        )
                                    }
                                    .onFailure {
                                        snackbarHostState.showSnackbar("Couldn't make a backup")
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
                    Text("Back up now", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showRestoreChooser = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restore a backup", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busyLabel = "Preparing export"
                            runCatching { viewModel.exportBackupZipFile() }
                                .onSuccess { (file, result) ->
                                    if (!shareBackupFile(ctx, file)) {
                                        snackbarHostState.showSnackbar("Couldn't export backup")
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            backupSummaryMessage("Backup includes", result)
                                        )
                                    }
                                }
                                .onFailure {
                                    snackbarHostState.showSnackbar("Couldn't export backup")
                                }
                            busyLabel = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Export & share a backup file", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = "Move between apps") {
                Text(
                    "Coming from Chrome, Firefox, Raindrop.io, Pocket, or another bookmark app? " +
                        "Export your bookmarks there as an HTML or CSV file, then bring that file in here. " +
                        "Folders become collections, tags come along, and links you already have are skipped. " +
                        "Going the other way? Export your links as a CSV any app can read.",
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
                                snackbarHostState.showSnackbar("No file picker available on this device.")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Import bookmarks file", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busyLabel = "Preparing CSV"
                            runCatching { viewModel.exportCsvFile() }
                                .onSuccess { (file, links) ->
                                    when {
                                        links == 0 -> snackbarHostState.showSnackbar(
                                            "No links to export yet — the CSV covers saves that have a web address"
                                        )
                                        !shareCsvFile(ctx, file) -> snackbarHostState.showSnackbar(
                                            "Couldn't export the CSV"
                                        )
                                        else -> snackbarHostState.showSnackbar(
                                            "Exported $links ${if (links == 1) "link" else "links"} as CSV"
                                        )
                                    }
                                }
                                .onFailure {
                                    snackbarHostState.showSnackbar("Couldn't export the CSV")
                                }
                            busyLabel = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Export links as CSV", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSection(title = "About") {
                Text(
                    "Enjoying PettiBox, or ran into something odd? Either way, I'd love to hear it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        if (!HelpLinks.openPlayListing(ctx)) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Couldn't open the Play Store on this device")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Rate PettiBox", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        if (!HelpLinks.openSupportEmail(ctx)) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "No email app found — write to ${HelpLinks.SUPPORT_EMAIL}"
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Email support", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        if (!HelpLinks.shareApp(ctx)) {
                            scope.launch { snackbarHostState.showSnackbar("Couldn't open the share sheet") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share PettiBox with a friend", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
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
                if (hasBackup) "Last backup: ${formatBackupTime(status.lastBackupAt)}"
                else if (status.enabled) "Safety copy is on"
                else "Safety copy is off",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                when {
                    status.lastCopyFailedAt > 0L -> "PettiBox made a private copy, but the extra folder copy needs attention."
                    hasBackup && status.folderUri.isNotBlank() -> "Also copied to the folder you chose."
                    hasBackup -> "Stored privately on this device."
                    status.enabled -> "PettiBox will make a copy overnight."
                    else -> "Turn on automatic safety copy below."
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
            "The main idea",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        HelpStep(number = "1", title = "Save", body = "Tap Share in any app and choose PettiBox, or use + on Home.")
        HelpStep(number = "2", title = "Organize", body = "Pick a collection. Add notes, tags, or reminders only when useful.")
        HelpStep(number = "3", title = "Find", body = "Search by words, source, type, tag, collection, reminder, or text inside images/PDFs.")
        HelpStep(number = "4", title = "Protect", body = "Archive instead of deleting, and keep automatic safety copy on.")
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
        Text("$limit", fontWeight = FontWeight.Bold)
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
            LockAfterChoice(label = label, selected = selectedMs == ms, onClick = { onSelect(ms) })
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
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
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
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(timeState.hour, timeState.minute) },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Set", fontWeight = FontWeight.Bold) }
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
            "No collections yet. Create one whenever you need a shelf like \"Trip ideas\" or \"Client work\"."
        } else {
            "Tap any collection to rename it, change its emoji or color, or delete it — starter collections included."
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
                        c.name,
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
        Text("Create a collection", fontWeight = FontWeight.Bold)
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
}

private fun shareBackupFile(ctx: Context, file: File): Boolean =
    shareExportFile(ctx, file, "application/zip", "PettiBox backup", "Export PettiBox backup")

private fun shareCsvFile(ctx: Context, file: File): Boolean =
    shareExportFile(ctx, file, "text/csv", "PettiBox links", "Export links as CSV")

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

private fun backupSummaryMessage(
    prefix: String,
    result: SaveRepository.BackupExportResult
): String {
    val extras = buildList {
        if (result.favorites > 0) add("${result.favorites} favorites")
        if (result.archived > 0) add("${result.archived} archived")
        if (result.tags > 0) add("${result.tags} tags")
    }
    val extraText = if (extras.isEmpty()) "" else ", " + extras.joinToString(", ")
    return "$prefix ${result.saves} saves, ${result.embeddedFiles} files$extraText"
}

private fun restoreResultMessage(result: SaveRepository.BackupImportResult): String {
    if (result.saves == 0 && result.skippedDuplicates > 0) {
        return "Everything in that backup is already on your shelf — nothing added"
    }
    val base = "Restored ${result.saves} saves, ${result.categories} collections, ${result.tags} tags"
    return if (result.skippedDuplicates > 0) {
        "$base (${result.skippedDuplicates} already on your shelf)"
    } else base
}

private fun bookmarkImportMessage(result: SaveRepository.BookmarkImportResult): String {
    if (result.imported == 0) {
        return "Nothing new to import — those links are already on your shelf"
    }
    val parts = buildList {
        add("Imported ${result.imported} ${if (result.imported == 1) "link" else "links"}")
        if (result.newCollections > 0) {
            add("${result.newCollections} new ${if (result.newCollections == 1) "collection" else "collections"}")
        }
        if (result.skippedDuplicates > 0) add("${result.skippedDuplicates} already saved")
    }
    return parts.joinToString(", ")
}

private fun formatBackupTime(timestamp: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))
