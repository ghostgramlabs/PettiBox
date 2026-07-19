package com.ghostgramlabs.pettibox.ui.screens.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostgramlabs.pettibox.data.local.CategoryEntity
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import com.ghostgramlabs.pettibox.ui.components.CategoryChip
import com.ghostgramlabs.pettibox.ui.components.EmptyState
import com.ghostgramlabs.pettibox.ui.components.HelpLinks
import com.ghostgramlabs.pettibox.ui.components.KeeperMascot
import com.ghostgramlabs.pettibox.ui.components.KeeperPose
import com.ghostgramlabs.pettibox.ui.components.QuickActionSheet
import com.ghostgramlabs.pettibox.ui.components.ReminderCustomSheet
import com.ghostgramlabs.pettibox.ui.components.ReminderPickerSheet
import com.ghostgramlabs.pettibox.ui.components.rememberNotificationPermissionRequester
import com.ghostgramlabs.pettibox.ui.components.SaveCard
import com.ghostgramlabs.pettibox.ui.components.ScreenHeading
import com.ghostgramlabs.pettibox.ui.components.SectionHeader
import com.ghostgramlabs.pettibox.ui.screens.save.IncomingShare
import com.ghostgramlabs.pettibox.ui.screens.save.SaveSheet
import com.ghostgramlabs.pettibox.ui.theme.isLightTheme
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onOpenItem: (Long) -> Unit,
    onOpenSource: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenAllCategories: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // Category lookups happen per-card per-recomposition on every scroll
    // frame. The list grew from O(1) categories to a few dozen as users
    // create their own — the linear scan was hot enough to show up in the
    // recomp profiler. Memoize as a map keyed by id; rebuilds only when
    // the underlying list changes.
    val categoriesById = remember(state.categories) {
        state.categories.associateBy { it.id }
    }
    var collectionQuery by remember { mutableStateOf("") }
    val visibleBrowseCategories = remember(state.categories, collectionQuery) {
        val query = collectionQuery.trim()
        if (query.isBlank()) {
            state.categories
        } else {
            state.categories.filter { category ->
                category.name.contains(query, ignoreCase = true) ||
                    category.emoji.contains(query)
            }
        }
    }

    // Routing state for the manual-add flow. The chooser sheet sets one of
    // these; SaveSheet renders when [pendingShare] is non-null.
    var showChooser by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var pendingShare by remember { mutableStateOf<IncomingShare?>(null) }
    var isImportingDiscoveredBackup by remember { mutableStateOf(false) }

    // Milestone rating prompt: when the shelf crosses a save-count mark
    // (see RatingPreferences), ask Play for the in-app review sheet. The
    // milestone is recorded as spent before launching — Play silently
    // no-ops when over quota, and retry-on-every-launch reads as nagging.
    LaunchedEffect(state.totalCount) {
        if (state.totalCount == 0 || state.showOnboarding) return@LaunchedEffect
        val milestone = viewModel.ratingPromptDue(state.totalCount) ?: return@LaunchedEffect
        val activity = ctx as? Activity ?: return@LaunchedEffect
        viewModel.markRatingPrompted(milestone)
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            val info = manager.requestReview()
            manager.launchReview(activity, info)
        }
    }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            pendingShare = IncomingShare(imageUris = uris, mimeType = "image/*")
        }
    }
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Take a persistable read grant so SaveSheet can ingest the file
            // even if the user navigates away first.
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val mime = ctx.contentResolver.getType(uri)
            pendingShare = IncomingShare(fileUris = listOf(uri), mimeType = mime)
        }
    }

    val openQuickNote = { pendingShare = IncomingShare() }

    val shouldOfferBackupRestore = !state.isLoading &&
        state.totalCount == 0 &&
        state.archivedCount == 0 &&
        state.backupRestoreCandidate != null

    if (shouldOfferBackupRestore) {
        val candidate = state.backupRestoreCandidate
        AlertDialog(
            onDismissRequest = {
                if (!isImportingDiscoveredBackup) viewModel.skipDiscoveredBackup()
            },
            title = { Text("Restore your PettiBox?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("We found a backup in the PettiBox backup folder. Import it to bring your saves, collections, tags, reminders, and attachments back.")
                    Text(
                        "PettiBox only checks its own backup folder automatically. If your backup is somewhere else, skip this and choose the file from Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (candidate != null) {
                        Text(
                            "${candidate.fileName} • ${formatBackupSize(candidate.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isImportingDiscoveredBackup,
                    onClick = {
                        scope.launch {
                            isImportingDiscoveredBackup = true
                            val result = runCatching { viewModel.importDiscoveredBackup() }
                            isImportingDiscoveredBackup = false
                            result
                                .onSuccess { imported ->
                                    if (imported != null) {
                                        snackbarHostState.showSnackbar("Restored ${imported.saves} saves from backup")
                                    } else {
                                        snackbarHostState.showSnackbar("Backup was no longer available")
                                    }
                                }
                                .onFailure {
                                    snackbarHostState.showSnackbar("Couldn't import that backup")
                                }
                        }
                    }
                ) {
                    if (isImportingDiscoveredBackup) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isImportingDiscoveredBackup) "Importing" else "Import")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isImportingDiscoveredBackup,
                    onClick = { viewModel.skipDiscoveredBackup() }
                ) { Text("Skip") }
            },
            shape = RoundedCornerShape(24.dp)
        )
    } else if (state.showOnboarding && !state.isLoading) {
        HomeOnboardingDialog(
            onDismiss = viewModel::completeOnboarding,
            onAdd = {
                viewModel.completeOnboarding()
                showChooser = true
            }
        )
    }

    pendingShare?.let { share ->
        SaveSheet(
            incoming = share,
            onDismiss = { pendingShare = null },
            onSaved = { pendingShare = null },
            onOpenExisting = { id ->
                pendingShare = null
                onOpenItem(id)
            }
        )
    }

    if (showChooser) {
        AddChooserSheet(
            onDismiss = { showChooser = false },
            onPickNote = {
                showChooser = false
                openQuickNote()
            },
            onPickLink = {
                showChooser = false
                showLinkDialog = true
            },
            onPickPaste = {
                showChooser = false
                // Manual one-tap "direct copy to PettiBox": read the
                // primary clip, route URLs to a LINK save and anything
                // else to a NOTE — both via the existing SaveSheet so
                // the user can still tweak title/category before
                // committing. Reading happens on tap, not on chooser
                // open, so we don't trigger the Android 12+ "pasted
                // from clipboard" toast for users who didn't pick this.
                val text = readClipboardText(ctx)?.trim().orEmpty()
                if (text.isBlank()) {
                    Toast.makeText(ctx, "Nothing on clipboard to save", Toast.LENGTH_SHORT).show()
                } else {
                    val isUrl = text.startsWith("http://", ignoreCase = true) ||
                        text.startsWith("https://", ignoreCase = true)
                    pendingShare = if (isUrl) {
                        IncomingShare(text = text, urls = listOf(text), mimeType = "text/plain")
                    } else {
                        IncomingShare(text = text, mimeType = "text/plain")
                    }
                }
            },
            onPickImage = {
                showChooser = false
                pickImages.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPickFile = {
                showChooser = false
                pickFile.launch(arrayOf("*/*"))
            }
        )
    }

    if (showLinkDialog) {
        AddLinkSheet(
            initial = clipboardUrl(ctx).orEmpty(),
            onDismiss = { showLinkDialog = false },
            onConfirm = { url ->
                showLinkDialog = false
                pendingShare = IncomingShare(
                    text = url,
                    urls = listOf(url),
                    mimeType = "text/plain"
                )
            }
        )
    }

    // Long-press quick-action plumbing. quickActionItem is the item the
    // sheet is opened for; reminderItem routes the picker. They never
    // overlap (the sheet dismisses before opening the picker).
    var quickActionItem by remember { mutableStateOf<SaveItemEntity?>(null) }
    var reminderItem by remember { mutableStateOf<SaveItemEntity?>(null) }
    var customReminderItem by remember { mutableStateOf<SaveItemEntity?>(null) }
    val requestNotificationPermission = rememberNotificationPermissionRequester()
    val requestDelete: (SaveItemEntity) -> Unit = { item ->
        scope.launch {
            if (item.isArchived) {
                // Already in Archive — Delete here means permanent. Skipping
                // the stage step means we don't lie with "Moved to Archive"
                // (it was already there), and the row actually disappears.
                viewModel.deletePermanently(item)
                snackbarHostState.showSnackbar("Save deleted")
            } else {
                viewModel.stageDelete(item)
                // Stage-into-archive remains under the hood — it's how
                // we make Undo work without an in-memory snapshot — but
                // the user-facing copy now matches the button they
                // pressed ("Delete"), not the implementation detail.
                val result = snackbarHostState.showSnackbar(
                    message = "Save deleted",
                    actionLabel = "Undo"
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.undoStagedDelete(item)
                } else {
                    viewModel.deletePermanently(item)
                }
            }
        }
    }

    quickActionItem?.let { item ->
        QuickActionSheet(
            item = item,
            categories = state.categories,
            onTogglePin = { viewModel.togglePinned(item) },
            onToggleFavorite = { viewModel.toggleFavorite(item) },
            onToggleArchive = { viewModel.toggleArchived(item) },
            onRemind = { reminderItem = item },
            onMoveTo = { id -> viewModel.moveTo(item, id) },
            onDelete = { requestDelete(item) },
            onDismiss = { quickActionItem = null }
        )
    }

    reminderItem?.let { item ->
        ReminderPickerSheet(
            currentRemindAt = item.remindAt,
            onPick = { at ->
                reminderItem = null
                if (at != null) requestNotificationPermission { viewModel.setRemindAt(item, at) }
                else viewModel.setRemindAt(item, null)
            },
            onCustom = {
                reminderItem = null
                customReminderItem = item
            },
            onDismiss = { reminderItem = null }
        )
    }

    customReminderItem?.let { item ->
        ReminderCustomSheet(
            onConfirm = { at ->
                customReminderItem = null
                requestNotificationPermission { viewModel.setRemindAt(item, at) }
            },
            onDismiss = { customReminderItem = null }
        )
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // The NavGraph's outer Scaffold already consumed status-bar and
        // bottom-nav insets — re-applying them here is what created the
        // double-padded gap above the bottom nav and below the status bar.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Visible on an empty shelf too — hiding the only manual-add
            // entry point exactly when a new user is looking for it was
            // backwards. Only the initial load hides it, to avoid a flash.
            if (!state.isLoading) {
                // Squircle shape is SHARED across both themes (same geometry
                // rule as the nav dock) so they match. Only the lift differs as
                // per-mode treatment: a flat 3 dp paper-cut in light, the
                // floatier default 6 dp in dark. Persimmon identity kept.
                val light = isLightTheme()
                FloatingActionButton(
                    onClick = { showChooser = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(20.dp),
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = if (light) 3.dp else 6.dp,
                        pressedElevation = if (light) 6.dp else 12.dp
                    )
                ) { Icon(Icons.Rounded.Add, contentDescription = "Add to shelf") }
            }
        }
    ) { padding ->
        if (state.isLoading) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                Greeting()
                Text(
                    "Loading your shelf...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            return@Scaffold
        }

        if (state.recent.isEmpty() && state.pinned.isEmpty() && state.favorites.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                Greeting()
                if (state.archivedCount > 0) {
                    // The shelf isn't empty, the user has archived all of
                    // it. Pointing them at the Share menu would imply
                    // they've never saved anything, which is wrong. The
                    // path back to their saves is the Browse archive view.
                    EmptyState(
                        emoji = "\uD83D\uDDC3",
                        headline = "Nothing live on your shelf",
                        body = "You've archived everything. Open Browse \u2192 tap the archive icon on any collection to see what's tucked away.",
                        fillScreen = false
                    )
                } else {
                    // True fresh install \u2014 show the 3-step guide + the
                    // share-into-PettiBox explainer below.
                    FirstRunGuide(
                        // Opens the full add chooser (note / link / paste /
                        // image / file) \u2014 not the bare quick-note editor,
                        // which nudged first-time users into creating an
                        // empty note as their first "save".
                        onAdd = { showChooser = true },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    // FirstRunGuide already exposes the primary "Add
                    // something now" call-to-action; this empty state is
                    // purely an explainer below it.
                    EmptyState(
                        emoji = "\uD83D\uDCE5",
                        headline = "PettiBox lives in your Share menu",
                        body = "In YouTube, Instagram, Chrome, Photos, or Files, tap Share and choose PettiBox. Pick a collection and it becomes searchable.",
                        fillScreen = false
                    )
                }
            }
            return@Scaffold
        }

        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(156.dp),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp,
                top = 0.dp, bottom = padding.calculateBottomPadding() + 96.dp
            ),
            verticalItemSpacing = 12.dp,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .padding(top = padding.calculateTopPadding())
                .fillMaxSize()
        ) {
            // Header - full-width span
            item(span = StaggeredGridItemSpan.FullLine) {
                Column {
                    Greeting()
                    if (state.isIndexingText) {
                        Spacer(Modifier.height(10.dp))
                        IndexingStatusChip(modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    if (state.notificationsBlocked) {
                        Spacer(Modifier.height(10.dp))
                        ReminderWarningBanner(
                            onEnable = {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                        }
                                    )
                                }
                            },
                            onDismiss = viewModel::clearNotificationWarning,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    if (state.categories.isNotEmpty()) {
                        // No "See all" trailing — Browse is one tap away in
                        // the bottom nav already; the link was duplicate
                        // navigation cluttering the header.
                        SectionHeader(
                            "Browse",
                            subtitle = "Tap a vibe to explore"
                        )
                        Spacer(Modifier.height(12.dp))
                        if (state.categories.size > 8) {
                            HomeCollectionFinder(
                                query = collectionQuery,
                                onQueryChange = { collectionQuery = it },
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        CategoryStrip(
                            categories = visibleBrowseCategories,
                            onClickCategory = onOpenCategory
                        )
                        if (visibleBrowseCategories.isEmpty()) {
                            Text(
                                "No collection matches \"$collectionQuery\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    if (state.sources.isNotEmpty()) {
                        SectionHeader("From", subtitle = "Where you saved it from")
                        Spacer(Modifier.height(12.dp))
                        SourceStrip(
                            sources = state.sources,
                            onClick = onOpenSource
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                    if (state.pinned.isNotEmpty()) {
                        SectionHeader("Pinned", subtitle = "Stuff you didn't want to lose")
                        Spacer(Modifier.height(12.dp))
                        // Pinned items render as a leaning "shelf" instead of
                        // joining the staggered grid: alternating tilt, varying
                        // widths, and a hand-drawn shelf line beneath. Reads
                        // like books on a shelf rather than items in a grid.
                        PinnedShelf(
                            items = state.pinned,
                            categoriesById = categoriesById,
                            onOpenItem = onOpenItem,
                            onLongPress = { quickActionItem = it }
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    // Favorites get a quieter horizontal row — distinct from
                    // the leaning Pinned shelf so the two read as different
                    // intents (Pinned = sticky for now, Favorites = saves
                    // you've signaled you love long-term). Hidden when empty.
                    if (state.favorites.isNotEmpty()) {
                        SectionHeader("Loved", subtitle = "Things you marked with a heart")
                        Spacer(Modifier.height(12.dp))
                        FavoritesRow(
                            items = state.favorites,
                            categoriesById = categoriesById,
                            onOpenItem = onOpenItem,
                            onLongPress = { quickActionItem = it }
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }

            if (state.recent.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Column {
                        Spacer(Modifier.height(24.dp))
                        SectionHeader(
                            "Recent saves",
                            subtitle = "Fresh from the share sheet"
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            // `key` uses the raw row id (Long, primitive-stable) so
            // Compose can identify items across recompositions without
            // allocating a new String per item per frame as it did with
            // "r-${it.id}". `contentType` is the section name so the
            // staggered grid doesn't recycle across visually different
            // sections in the rare case future sections are paged into
            // this list.
            items(
                state.recent,
                key = { it.id },
                contentType = { "recent" }
            ) { item ->
                CardForItem(
                    item = item,
                    category = categoriesById[item.categoryId],
                    onOpen = onOpenItem,
                    onLongPress = { quickActionItem = it }
                )
            }
        }
    }
}

private fun formatBackupSize(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "${kotlin.math.max(1, kb.toInt())} KB"
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.1f MB", mb)
}

@Composable
private fun ReminderWarningBanner(
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.error.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        KeeperMascot(
            size = 42.dp,
            accent = scheme.error,
            pose = KeeperPose.NotificationsBlocked,
            modifier = Modifier.padding(end = 10.dp)
        )
        Text(
            "Reminders need notifications turned on.",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = scheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onEnable) { Text("Enable") }
        TextButton(onClick = onDismiss) { Text("Hide") }
    }
}

@Composable
private fun HomeOnboardingDialog(
    onDismiss: () -> Unit,
    onAdd: () -> Unit
) {
    var page by remember { mutableStateOf(0) }
    val steps = listOf(
        OnboardingStep(
            icon = Icons.Rounded.Share,
            title = "Keep things for later",
            body = "Save links, images, PDFs, notes, and reminders into one searchable PettiBox.",
            tip = null
        ),
        OnboardingStep(
            icon = Icons.Rounded.GridView,
            title = "Share into PettiBox",
            body = "Tap Share in another app, choose PettiBox, then save it to a shelf. You can add notes, tags, or reminders when they help.",
            tip = null,
            showDemoVideo = true
        ),
        OnboardingStep(
            icon = Icons.Rounded.Search,
            title = "Find it fast",
            body = "Search by words, source app, file type, tag, reminder, or text inside images and PDFs.",
            // One-line discovery hint for the single most-leveraged
            // interaction in the app. Long-press is invisible without
            // it being said somewhere.
            tip = "Tip: Archive is your safety net. Delete only when you're sure."
        )
    )
    val current = steps[page]
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            KeeperMascot(
                size = 92.dp,
                pose = KeeperPose.Welcome,
                badgeIcon = current.icon
            )
        },
        title = { Text(current.title, fontWeight = FontWeight.ExtraBold) },
        text = {
            val ctx = LocalContext.current
            Column {
                Text(current.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (current.tip != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        current.tip,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (current.showDemoVideo) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "▶ Watch the short demo",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { HelpLinks.openShareDemo(ctx) }
                            .padding(vertical = 6.dp, horizontal = 2.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.indices.forEach { i ->
                        Box(
                            Modifier
                                .size(width = if (i == page) 24.dp else 8.dp, height = 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i == page) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (page < steps.lastIndex) page++ else onAdd()
                }
            ) {
                Text(if (page < steps.lastIndex) "Next" else "Try a quick note")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Skip") }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

private data class OnboardingStep(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val tip: String? = null,
    /** Offer the share-flow demo video on this step. */
    val showDemoVideo: Boolean = false
)

@Composable
private fun IndexingStatusChip(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "text-indexing")
    val dotOne by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot-one"
    )
    val dotTwo by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, delayMillis = 160),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot-two"
    )
    val dotThree by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, delayMillis = 320),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot-three"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.primary.copy(alpha = 0.11f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(dotOne, dotTwo, dotThree).forEach { alpha ->
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = alpha))
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            "Indexing saved text",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = scheme.primary
        )
    }
}

@Composable
private fun FirstRunGuide(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .padding(16.dp)
    ) {
        Text(
            "Your shelf starts with one save",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = scheme.onSurface
        )
        Spacer(Modifier.height(12.dp))
        GuideStep(
            icon = Icons.Rounded.Share,
            title = "Use Share",
            body = "From Chrome, YouTube, Photos, Files, or another app, tap Share and choose PettiBox."
        )
        val ctx = LocalContext.current
        Text(
            "▶ Watch how it works",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = scheme.primary,
            modifier = Modifier
                .padding(start = 30.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { HelpLinks.openShareDemo(ctx) }
                .padding(vertical = 6.dp, horizontal = 2.dp)
        )
        Spacer(Modifier.height(10.dp))
        GuideStep(
            icon = Icons.Rounded.GridView,
            title = "Pick a collection",
            body = "Choose where it belongs. You can add a note, tag, or reminder later."
        )
        Spacer(Modifier.height(10.dp))
        GuideStep(
            icon = Icons.Rounded.Search,
            title = "Find it later",
            body = "Search by words, source, type, tag, reminder, or text inside images and PDFs."
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.primary)
                .clickable(onClick = onAdd)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = null,
                tint = scheme.onPrimary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Add something now",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = scheme.onPrimary
            )
        }
    }
}

@Composable
private fun GuideStep(
    icon: ImageVector,
    title: String,
    body: String
) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = scheme.onSurface
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Greeting() {
    ScreenHeading(title = "What's on the shelf?")
}

@Composable
private fun SourceStrip(
    sources: List<SourceCount>,
    onClick: (String) -> Unit
) {
    // A tidy row of equal-width "source cards" — a soft-edged tile with a
    // squircle emoji token, the app name, and its count. Fixed width + a
    // whisper of a border makes them read as one curated set on a shelf
    // rather than loose icons floating on the page.
    val scheme = MaterialTheme.colorScheme
    val light = isLightTheme()
    val tileShape = RoundedCornerShape(16.dp)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sources, key = { it.source }) { s ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(86.dp)
                    .then(
                        if (light) Modifier
                            .shadow(2.dp, tileShape, clip = false)
                            .clip(tileShape)
                            .background(scheme.surface)
                            .border(1.dp, scheme.outline.copy(alpha = 0.6f), tileShape)
                        else Modifier
                            .clip(tileShape)
                            .background(scheme.surface)
                    )
                    .clickable { onClick(s.source) }
                    .padding(horizontal = 8.dp, vertical = 12.dp)
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(scheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(s.emoji, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    s.display,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${s.count} saved",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun CategoryStrip(
    categories: List<CategoryEntity>,
    onClickCategory: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(categories) { c ->
            // Alternating tilt - gives the row a hand-arranged feel.
            val tilt = if (c.sortOrder % 2 == 0) -2f else 1.5f
            CategoryChip(
                label = c.name,
                emoji = c.emoji,
                color = Color(c.colorHex),
                selected = false,
                tilt = tilt,
                onClick = { onClickCategory(c.id) }
            )
        }
    }
}

@Composable
private fun HomeCollectionFinder(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("Find collection") },
        leadingIcon = {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingIcon = {
            if (query.isNotBlank()) {
                androidx.compose.material3.IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear collection filter")
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun CardForItem(
    item: SaveItemEntity,
    category: CategoryEntity?,
    onOpen: (Long) -> Unit,
    onLongPress: (SaveItemEntity) -> Unit = {}
) {
    SaveCard(
        item = item,
        accent = category?.let { Color(it.colorHex) } ?: MaterialTheme.colorScheme.primary,
        categoryEmoji = category?.emoji,
        categoryName = category?.name,
        onClick = { onOpen(item.id) },
        onLongClick = { onLongPress(item) }
    )
}

/**
 * Pinned items rendered as a leaning shelf: a horizontally-scrolling row
 * of cards with alternating tilts, alternating top-padding (so some sit
 * lower like they slumped against the next one), varying widths, and a
 * single hand-drawn ink line beneath as the shelf board. Visually the
 * opposite of a grid — that's the point.
 */
@Composable
private fun PinnedShelf(
    items: List<SaveItemEntity>,
    categoriesById: Map<String, CategoryEntity>,
    onOpenItem: (Long) -> Unit,
    onLongPress: (SaveItemEntity) -> Unit = {}
) {
    val tilts = listOf(-3f, 2.5f, -1.5f, 3f, -2.5f, 1.5f)
    val widthsDp = listOf(170, 150, 175, 158, 165, 152)
    Column(Modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { idx, item ->
                val tilt = tilts[idx % tilts.size]
                val width = widthsDp[idx % widthsDp.size].dp
                // Alternating cards "slump" lower so the row reads as
                // hand-arranged, not aligned-by-grid.
                val lift = if (idx % 2 == 1) 10.dp else 0.dp
                val cat = item.categoryId?.let { categoriesById[it] }
                Box(
                    Modifier
                        .padding(top = lift, bottom = 0.dp)
                        .width(width)
                        .rotate(tilt)
                ) {
                    SaveCard(
                        item = item,
                        accent = cat?.let { Color(it.colorHex) } ?: MaterialTheme.colorScheme.primary,
                        categoryEmoji = cat?.emoji,
                        categoryName = cat?.name,
                        onClick = { onOpenItem(item.id) },
                        onLongClick = { onLongPress(item) }
                    )
                }
            }
        }
        ShelfLine(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
        )
    }
}

/**
 * Favorited saves rendered as a horizontally-scrolling row of compact
 * cards. Visually orderly (no leaning, no shelf line) so it doesn't
 * read as a second pinned shelf — favorites are a curated long-term
 * collection, not a workspace.
 */
@Composable
private fun FavoritesRow(
    items: List<SaveItemEntity>,
    categoriesById: Map<String, CategoryEntity>,
    onOpenItem: (Long) -> Unit,
    onLongPress: (SaveItemEntity) -> Unit = {}
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(items, key = { it.id }) { item ->
            val cat = item.categoryId?.let { categoriesById[it] }
            Box(Modifier.width(160.dp)) {
                SaveCard(
                    item = item,
                    accent = cat?.let { Color(it.colorHex) } ?: MaterialTheme.colorScheme.primary,
                    categoryEmoji = cat?.emoji,
                    categoryName = cat?.name,
                    onClick = { onOpenItem(item.id) },
                    onLongClick = { onLongPress(item) }
                )
            }
        }
    }
}

/** Wavy single-stroke ink line — the "shelf board." */
@Composable
private fun ShelfLine(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.height(8.dp)) {
        val midY = size.height / 2f
        val amp = 1.4f
        val period = 16f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, midY)
            var x = 0f
            while (x <= size.width) {
                val y = midY + amp * kotlin.math.sin(x / period * Math.PI.toFloat() * 2f)
                lineTo(x, y)
                x += 2f
            }
        }
        drawPath(
            path = path,
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddChooserSheet(
    onDismiss: () -> Unit,
    onPickNote: () -> Unit,
    onPickLink: () -> Unit,
    onPickPaste: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Was Color.Transparent, which left the title + rows floating on the
        // dimmed app with no panel behind them. A solid paper panel (matching
        // the other sheets) makes it read as one cohesive popup; the rows use
        // the brighter `surface` so they still lift off it.
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 20.dp)
                .navigationBarsPadding()
        ) {
            Text(
                "Add to your shelf",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Pick what you're saving.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            ChooserRow(
                icon = Icons.Rounded.EditNote,
                title = "Note",
                subtitle = "Jot a quick thought",
                onClick = onPickNote
            )
            Spacer(Modifier.height(8.dp))
            ChooserRow(
                icon = Icons.Rounded.Link,
                title = "Link",
                subtitle = "Paste a URL — we'll fetch the title",
                onClick = onPickLink
            )
            Spacer(Modifier.height(8.dp))
            // One-tap clipboard import — sits next to Link/Note since it
            // routes to whichever fits what's on the clipboard. Saves a
            // detour through the Link dialog or the Note paste-keyboard
            // dance, and is the manual replacement for the auto-capture
            // flow that was removed.
            ChooserRow(
                icon = Icons.Rounded.ContentPaste,
                title = "Paste",
                subtitle = "Save whatever's on your clipboard",
                onClick = onPickPaste
            )
            Spacer(Modifier.height(8.dp))
            ChooserRow(
                icon = Icons.Rounded.Image,
                title = "Picture",
                subtitle = "From your gallery",
                onClick = onPickImage
            )
            Spacer(Modifier.height(8.dp))
            ChooserRow(
                icon = Icons.Rounded.AttachFile,
                title = "File",
                subtitle = "PDF, document, anything",
                onClick = onPickFile
            )
        }
    }
}

@Composable
private fun ChooserRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = scheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddLinkSheet(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val trimmed = text.trim()
    val isValid = trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 20.dp)
                .navigationBarsPadding()
        ) {
            Text(
                "Save a link",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Paste a URL — we'll fetch the title.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("https://...") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            if (initial.isNotBlank() && initial == text) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Pulled from your clipboard.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = isValid,
                    onClick = { onConfirm(trimmed) },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Save", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private fun clipboardUrl(ctx: Context): String? {
    val text = readClipboardText(ctx)?.trim().orEmpty()
    if (text.isBlank()) return null
    val starts = text.startsWith("http://", ignoreCase = true) ||
        text.startsWith("https://", ignoreCase = true)
    return if (starts) text else null
}

/**
 * One-shot read of the primary clip, used by the manual Add-Link sheet
 * to pre-fill the URL field. Triggers the Android 12+ system "PettiBox
 * pasted from clipboard" toast — fine here because the user just
 * actively opened the link-add flow themselves.
 */
private fun readClipboardText(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return null
    return runCatching {
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
    }.getOrNull()
}
