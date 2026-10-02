package com.ghostgramlabs.pettibox.ui.screens.save

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ghostgramlabs.pettibox.data.local.CategoryEntity
import com.ghostgramlabs.pettibox.ui.components.CategoryChip
import com.ghostgramlabs.pettibox.ui.components.CreateCollectionDialog
import com.ghostgramlabs.pettibox.ui.components.KeeperMascot
import com.ghostgramlabs.pettibox.ui.components.KeeperPose
import com.ghostgramlabs.pettibox.ui.components.ReminderCustomSheet
import com.ghostgramlabs.pettibox.ui.components.ReminderPickerSheet
import com.ghostgramlabs.pettibox.ui.components.formatReminderAt
import com.ghostgramlabs.pettibox.ui.components.rememberNotificationPermissionRequester
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveSheet(
    incoming: IncomingShare,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onOpenExisting: (Long) -> Unit = {},
    viewModel: SaveSheetViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Always open fully expanded. A half-expanded sheet pushes the sticky
    // Save footer below the visible area, and that gets worse once the
    // keyboard opens for the collection search.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var createInitialName by remember { mutableStateOf("") }
    var showReminderPicker by remember { mutableStateOf(false) }
    var showCustomReminder by remember { mutableStateOf(false) }
    var collectionQuery by remember { mutableStateOf("") }
    val requestNotificationPermission = rememberNotificationPermissionRequester()
    val titleFocus = remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    val selectedCategoryName = state.categories.firstOrNull { it.id == state.selectedCategory }?.name
        ?: if (state.selectedCategory != null) "collection" else null

    if (showReminderPicker) {
        ReminderPickerSheet(
            currentRemindAt = state.remindAt,
            onPick = { at ->
                showReminderPicker = false
                if (at != null) {
                    requestNotificationPermission { viewModel.setRemindAt(at) }
                } else {
                    viewModel.setRemindAt(null)
                }
            },
            onCustom = {
                showReminderPicker = false
                showCustomReminder = true
            },
            onDismiss = { showReminderPicker = false }
        )
    }
    if (showCustomReminder) {
        ReminderCustomSheet(
            onConfirm = { at ->
                showCustomReminder = false
                requestNotificationPermission { viewModel.setRemindAt(at) }
            },
            onDismiss = { showCustomReminder = false }
        )
    }

    LaunchedEffect(incoming) { viewModel.ingest(incoming) }
    // Deliberately not auto-focusing the title - the primary action is
    // picking a collection. The user opens the sheet, taps a chip, taps Save.
    // Editing the title or adding a note is an optional second step that
    // shouldn't pop the keyboard up unprompted.
    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            // Hold the success state on screen briefly so the user sees the check
            delay(900)
            sheetState.hide()
            onSaved()
        }
    }

    if (showCreate) {
        CreateCollectionDialog(
            initialName = createInitialName,
            onCreate = { nc ->
                viewModel.createCollection(nc)
                showCreate = false
                createInitialName = ""
            },
            onDismiss = {
                showCreate = false
                createInitialName = ""
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .width(48.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.outline)
            )
        }
    ) {
        if (state.isSaved) {
            SuccessPanel(remindAt = state.remindAt)
            return@ModalBottomSheet
        }
        if (state.mode == SaveMode.PICK_EXISTING) {
            PickExistingBody(
                items = state.recentItems,
                categories = state.categories,
                onPick = { id -> viewModel.saveToExisting(id) },
                onCancel = { viewModel.setMode(SaveMode.NEW) }
            )
            return@ModalBottomSheet
        }
        val finderShown = state.categories.size > 8
        val visibleCategories = remember(
            state.categories, state.suggestedCategory, state.recentCategoryIds, collectionQuery
        ) {
            val suggested = state.suggestedCategory
            val q = collectionQuery.trim()
            val filtered = if (q.isBlank()) {
                state.categories
            } else {
                state.categories.filter { c ->
                    c.name.contains(q, ignoreCase = true) || c.emoji.contains(q)
                }
            }
            // Save is habit-driven: show the likely match first, then the
            // user's recent shelves, then their custom shelves, then starter
            // collections. Each collection appears once.
            val recentRank = state.recentCategoryIds.withIndex()
                .associate { (i, id) -> id to i }
            filtered.sortedWith(
                compareBy(
                    { if (it.id == suggested) -1 else 0 },
                    { recentRank[it.id] ?: Int.MAX_VALUE },
                    { if (it.userCreated) 0 else 1 },
                    // Within each group — your own collections first, then the
                    // built-in starters — sort alphabetically, matching Browse.
                    { it.name.lowercase() }
                )
            )
        }

        // Many collections: a dedicated picker layout where the collection list
        // owns its own scroll and the search field + Save button stay pinned,
        // so typing/scrolling never shoves the rest of the sheet around.
        if (finderShown) {
            FinderPickerBody(
                state = state,
                query = collectionQuery,
                onQueryChange = { collectionQuery = it },
                visibleCategories = visibleCategories,
                selectedCategoryName = selectedCategoryName,
                onSelectCategory = { viewModel.pickCategory(it) },
                onToggleFavorite = { viewModel.toggleFavorite() },
                onSave = { viewModel.save() },
                onCreateCollection = { initialName ->
                    createInitialName = initialName.orEmpty()
                    showCreate = true
                },
                onPickReminder = { showReminderPicker = true },
                onAppendExisting = { viewModel.setMode(SaveMode.PICK_EXISTING) },
                onTitleChange = viewModel::setTitle,
                onNotesChange = viewModel::setNotes,
                onTagsChange = viewModel::setTags,
                titleFocus = titleFocus,
                openNotes = !incoming.hasAnything,
                onOpenExisting = onOpenExisting,
                onDismissDuplicate = { viewModel.dismissDuplicate() }
            )
            return@ModalBottomSheet
        }

        // Few collections: the original single-scroll layout with the big
        // preview and a horizontal chip row — no finder, no separate scroll.
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .imePadding()
        ) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Save this?",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "${state.sourceApp.emoji} ${state.sourceApp.displayName}" +
                    if (state.attachments.size > 1) " · ${state.attachments.size} items" else "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            // "You already saved this" — shown when the shared URL matches an
            // existing live save. Advisory, not blocking: the user can open
            // the original or dismiss and save a second copy anyway.
            state.duplicateOf?.let { dup ->
                DuplicateBanner(
                    existing = dup,
                    categoryLabel = state.categories.firstOrNull { it.id == dup.categoryId }
                        ?.let { "${it.emoji} ${it.name}" },
                    onOpen = { onOpenExisting(dup.id) },
                    onDismiss = { viewModel.dismissDuplicate() }
                )
                Spacer(Modifier.height(12.dp))
            }

            // Preview
            if (state.attachments.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.attachments) { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(110.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            } else {
                val img = state.previewImage ?: state.localUri
                if (img != null) {
                    AsyncImage(
                        model = img,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(132.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }

            // Resolved title shown as compact text - display only by default,
            // editable on demand. Avoids popping the keyboard up just to view.
            Text(
                state.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (!state.description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    state.description!!.take(120),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }

            // Primary action: pick a collection.
            Spacer(Modifier.height(14.dp))
            Text(
                "Save to",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(10.dp))
            // The familiar horizontal chip row with New / Remind me / Append as
            // peer chips at the end. Tap selects the destination; the sticky
            // footer commits, so notes/tags/reminder can still be added first.
            LazyRow(
                contentPadding = PaddingValues(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visibleCategories, key = { it.id }) { c ->
                    val tilt = if (c.sortOrder % 2 == 0) -2f else 1.5f
                    CategoryChip(
                        label = c.name,
                        emoji = c.emoji,
                        color = Color(c.colorHex),
                        selected = state.selectedCategory == c.id,
                        tilt = tilt,
                        // Hint border on the URL/title-suggested chip so the
                        // user notices it without us auto-saving for them.
                        suggested = state.selectedCategory == null &&
                            state.suggestedCategory == c.id,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.saveToCategory(c.id)
                        }
                    )
                }
                item {
                    NewCollectionChip(onClick = {
                        createInitialName = ""
                        showCreate = true
                    })
                }
                item {
                    RemindMeChip(
                        remindAt = state.remindAt,
                        onClick = { showReminderPicker = true }
                    )
                }
                if (state.recentItems.isNotEmpty()) {
                    item {
                        AddToExistingChip(
                            onClick = { viewModel.setMode(SaveMode.PICK_EXISTING) }
                        )
                    }
                }
            }

            // Optional details expand on demand.
            Spacer(Modifier.height(20.dp))
            OptionalDetails(
                title = state.title,
                notes = state.notes,
                tagsInput = state.tagsInput,
                onTitleChange = viewModel::setTitle,
                onNotesChange = viewModel::setNotes,
                onTagsChange = viewModel::setTags,
                titleFocus = titleFocus,
                openNotes = !incoming.hasAnything
            )
            // Bottom padding inside the scroll so the last field clears the
            // sticky action bar even before the user scrolls.
            Spacer(Modifier.height(16.dp))
        }
        SaveFooter(
            isFavorite = state.isFavorite,
            selectedCategoryName = selectedCategoryName,
            onToggleFavorite = { viewModel.toggleFavorite() },
            onSave = { viewModel.save() }
        )
        }
    }
}

/**
 * Picker-first layout used when there are enough collections to need the
 * finder. The collection list owns its own scroll region (a [LazyColumn] with
 * weight) between a pinned header (Save-to + search) and a pinned [SaveFooter],
 * so typing or scrolling collections never moves the rest of the sheet.
 *
 * Engaging the search (focusing it or typing) collapses the preview and the
 * secondary chips/optional-details, handing the whole sheet to the list —
 * which is where the user's attention is when they're hunting for a
 * collection. Stepping out of search restores the full sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinderPickerBody(
    state: SaveSheetState,
    query: String,
    onQueryChange: (String) -> Unit,
    visibleCategories: List<CategoryEntity>,
    selectedCategoryName: String?,
    onSelectCategory: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onSave: () -> Unit,
    onCreateCollection: (String?) -> Unit,
    onPickReminder: () -> Unit,
    onAppendExisting: () -> Unit,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
    titleFocus: FocusRequester,
    openNotes: Boolean,
    onOpenExisting: (Long) -> Unit,
    onDismissDuplicate: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var finderFocused by remember { mutableStateOf(false) }
    val searching = finderFocused || query.isNotBlank()
    val imeVisible = WindowInsets.isImeVisible

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .imePadding()
    ) {
        // Pinned header: tiny context + Save-to + finder. The item never
        // disappears while the user searches, which keeps the save target clear.
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(6.dp))
            if (!searching) {
                state.duplicateOf?.let { dup ->
                    DuplicateBanner(
                        existing = dup,
                        categoryLabel = state.categories.firstOrNull { it.id == dup.categoryId }
                            ?.let { "${it.emoji} ${it.name}" },
                        onOpen = { onOpenExisting(dup.id) },
                        onDismiss = onDismissDuplicate
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
            CompactContext(state, dense = searching)
            Spacer(Modifier.height(if (searching) 10.dp else 14.dp))
            Text(
                "Save to",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(10.dp))
            CollectionFinder(
                query = query,
                onQueryChange = onQueryChange,
                onFocusChanged = { finderFocused = it }
            )
            Spacer(Modifier.height(10.dp))
        }

        // The one scroll region: the collection list. weight(1f) lets it grow
        // when the keyboard is closed and shrink when it opens, always keeping
        // the finder above and the footer below in view.
        if (visibleCategories.isEmpty()) {
            // In-character empty state: the Keeper went looking and came up
            // empty, then offers to start the collection they searched for —
            // instead of a bare line of grey text.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(8.dp))
                KeeperMascot(size = 92.dp, pose = KeeperPose.Search)
                Spacer(Modifier.height(12.dp))
                Text(
                    "No collection called \"$query\" yet",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Want to start one?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                NewCollectionChip(
                    label = query.trim().takeIf { it.isNotBlank() }?.let { "Create \"$it\"" } ?: "New",
                    onClick = { onCreateCollection(query.trim().takeIf { it.isNotBlank() }) }
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(visibleCategories, key = { _, c -> c.id }) { index, c ->
                    val previous = visibleCategories.getOrNull(index - 1)
                    val group = collectionGroupLabel(c, state.suggestedCategory, state.recentCategoryIds)
                    val previousGroup = previous?.let {
                        collectionGroupLabel(it, state.suggestedCategory, state.recentCategoryIds)
                    }
                    if (index == 0 || group != previousGroup) {
                        CollectionGroupLabel(group)
                    }
                    CollectionResultRow(
                        name = c.name,
                        emoji = c.emoji,
                        color = Color(c.colorHex),
                        selected = state.selectedCategory == c.id,
                        suggested = state.selectedCategory == null &&
                            state.suggestedCategory == c.id,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelectCategory(c.id)
                        }
                    )
                }
            }
        }

        // Pinned bottom: secondary actions + optional details. Search still
        // gets most of the sheet, but the edit/reminder affordances stay
        // reachable so the user never has to back out of typing to finish.
        Column(Modifier.padding(horizontal = 20.dp)) {
            if (!searching) {
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { NewCollectionChip(onClick = { onCreateCollection(null) }) }
                    item { RemindMeChip(remindAt = state.remindAt, onClick = onPickReminder) }
                    if (state.recentItems.isNotEmpty()) {
                        item { AddToExistingChip(onClick = onAppendExisting) }
                    }
                }
                Spacer(Modifier.height(14.dp))
            } else {
                SearchSelectionActions(
                    selectedCategoryName = selectedCategoryName,
                    remindAt = state.remindAt,
                    onPickReminder = onPickReminder
                )
                Spacer(Modifier.height(10.dp))
            }
            // One call site outside the branch above, so moving focus between
            // the finder and a title/note/tags field doesn't remount it — that
            // used to collapse the field being typed into and drop its focus.
            OptionalDetails(
                title = state.title,
                notes = state.notes,
                tagsInput = state.tagsInput,
                onTitleChange = onTitleChange,
                onNotesChange = onNotesChange,
                onTagsChange = onTagsChange,
                titleFocus = titleFocus,
                openNotes = openNotes,
                // While the user is typing in the finder the list needs the
                // room; open fields fold away (keeping what's typed) until the
                // finder lets go or the keyboard closes.
                compact = finderFocused && imeVisible
            )
            Spacer(Modifier.height(if (searching) 10.dp else 12.dp))
        }
        SaveFooter(
            isFavorite = state.isFavorite,
            selectedCategoryName = selectedCategoryName,
            onToggleFavorite = onToggleFavorite,
            onSave = onSave
        )
    }
}

/**
 * Compact one-row item context (thumbnail + title + source) used by the
 * picker-first layout, where the full-width preview would eat scarce vertical
 * space that belongs to the collection list.
 */
@Composable
private fun CompactContext(state: SaveSheetState, dense: Boolean = false) {
    val img = state.previewImage ?: state.localUri ?: state.attachments.firstOrNull()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (img != null) {
            AsyncImage(
                model = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(if (dense) 40.dp else 52.dp)
                    .clip(RoundedCornerShape(if (dense) 12.dp else 14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.width(if (dense) 10.dp else 12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                state.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = if (dense) 1 else 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                "${state.sourceApp.emoji} ${state.sourceApp.displayName}" +
                    if (state.attachments.size > 1) " · ${state.attachments.size} items" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * Sticky bottom action bar shared by both Save-sheet layouts. Hairline top
 * border separates it from the content above so it reads as a footer.
 */
@Composable
private fun SaveFooter(
    isFavorite: Boolean,
    selectedCategoryName: String?,
    onToggleFavorite: () -> Unit,
    onSave: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(
                    color = Color(0x14000000),
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = stroke
                )
            }
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .navigationBarsPadding()
    ) {
        // Heart icon stands on its own; contentDescription preserves the
        // explanation for screen readers.
        IconButton(onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onToggleFavorite()
        }) {
            Icon(
                if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onSave()
            },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                selectedCategoryName?.let { "Save to $it" } ?: "Save without collection",
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SearchSelectionActions(
    selectedCategoryName: String?,
    remindAt: Long?,
    onPickReminder: () -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (selectedCategoryName != null) {
            item {
                SelectedCollectionPill(selectedCategoryName)
            }
        }
        item {
            RemindMeChip(remindAt = remindAt, onClick = onPickReminder)
        }
    }
}

@Composable
private fun SelectedCollectionPill(label: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(scheme.primary.copy(alpha = 0.14f))
            .border(1.5.dp, scheme.primary, CircleShape)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Icon(
            Icons.Rounded.Check,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "Selected: $label",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PickExistingBody(
    items: List<com.ghostgramlabs.pettibox.data.local.SaveItemEntity>,
    categories: List<com.ghostgramlabs.pettibox.data.local.CategoryEntity>,
    onPick: (Long) -> Unit,
    onCancel: () -> Unit
) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Add to which save?",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.TextButton(onClick = onCancel) { Text("Back") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.id }) { item ->
                val cat = categories.firstOrNull { it.id == item.categoryId }
                PickRow(
                    title = item.title,
                    subtitle = listOfNotNull(cat?.let { "${it.emoji} ${it.name}" }, item.url).joinToString(" · ").take(80),
                    onClick = { onPick(item.id) }
                )
            }
        }
    }
}

@Composable
private fun PickRow(title: String, subtitle: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = scheme.onSurface,
                maxLines = 1
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Text("->", style = MaterialTheme.typography.titleMedium, color = scheme.primary)
    }
}

/**
 * Title / notes / tags are all optional on save - Stash's UX. We show one
 * compact row of "Add ..." chips. Tapping one inlines the field; the
 * keyboard only ever appears in response to a tap, never on sheet open.
 */
@Composable
private fun OptionalDetails(
    title: String,
    notes: String,
    tagsInput: String,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
    titleFocus: FocusRequester,
    openNotes: Boolean = false,
    compact: Boolean = false
) {
    val focusManager = LocalFocusManager.current
    var showTitle by remember { mutableStateOf(false) }
    // A field that already holds something (a quick note's text, say)
    // starts open so the user can see what's being saved.
    // A quick note (opened from "Note", not a share) starts with the note
    // field open and focused: the user's tap on "Note" asked for it.
    var showNotes by remember { mutableStateOf(openNotes || notes.isNotBlank()) }
    var showTags by remember { mutableStateOf(tagsInput.isNotBlank()) }
    // Opening notes/tags puts the cursor there, like the title does —
    // otherwise typing lands in whichever field last had focus.
    var focusTitleOnOpen by remember { mutableStateOf(false) }
    var focusNotesOnOpen by remember { mutableStateOf(openNotes) }
    var focusTagsOnOpen by remember { mutableStateOf(false) }
    val notesFocus = remember { FocusRequester() }
    val tagsFocus = remember { FocusRequester() }
    // Revealing a field also releases the collection finder, so the sheet
    // leaves compact mode and the new field can take the cursor.
    val revealTitle = { focusManager.clearFocus(); showTitle = true; focusTitleOnOpen = true }
    val revealNotes = { focusManager.clearFocus(); showNotes = true; focusNotesOnOpen = true }
    val revealTags = { focusManager.clearFocus(); showTags = true; focusTagsOnOpen = true }

    val anyOpen = showTitle || showNotes || showTags

    if (!anyOpen || compact) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!showTitle) AddChip("Edit title", onClick = revealTitle)
            if (!showNotes) AddChip("Add note", onClick = revealNotes)
            if (!showTags) AddChip("Add tags", onClick = revealTags)
        }
        return
    }

    if (showTitle) {
        TextField(
            value = title,
            onValueChange = onTitleChange,
            placeholder = { Text("Title") },
            singleLine = false,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().focusRequester(titleFocus)
        )
        // Focus the title only after the user explicitly asked to edit it
        // (once — not again each time the field folds back in after a search).
        LaunchedEffect(focusTitleOnOpen) {
            if (focusTitleOnOpen) {
                kotlinx.coroutines.delay(80)
                runCatching { titleFocus.requestFocus() }
                focusTitleOnOpen = false
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (showNotes) {
        OutlinedTextField(
            value = notes,
            onValueChange = onNotesChange,
            placeholder = { Text("Add a note") },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).focusRequester(notesFocus)
        )
        LaunchedEffect(focusNotesOnOpen) {
            if (focusNotesOnOpen) {
                kotlinx.coroutines.delay(80)
                runCatching { notesFocus.requestFocus() }
                focusNotesOnOpen = false
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (showTags) {
        OutlinedTextField(
            value = tagsInput,
            onValueChange = onTagsChange,
            placeholder = { Text("Tags, comma-separated") },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().focusRequester(tagsFocus)
        )
        LaunchedEffect(focusTagsOnOpen) {
            if (focusTagsOnOpen) {
                kotlinx.coroutines.delay(80)
                runCatching { tagsFocus.requestFocus() }
                focusTagsOnOpen = false
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    // Let the user reveal whichever fields they didn't open yet.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!showTitle) AddChip("Edit title", onClick = revealTitle)
        if (!showNotes) AddChip("Add note", onClick = revealNotes)
        if (!showTags) AddChip("Add tags", onClick = revealTags)
    }
}

@Composable
private fun AddChip(label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(scheme.surface)
            .border(1.dp, scheme.outline, androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text("+ $label", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun SuccessPanel(remindAt: Long? = null) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The Keeper celebrates the save. This is the one screen shown on
        // every single save, so it's where the app's character belongs — not
        // a generic checkmark. A clock badge appears when a reminder was
        // attached, confirming both actions at a glance.
        KeeperMascot(
            size = 124.dp,
            accent = accent,
            pose = KeeperPose.SaveSuccess,
            badgeIcon = if (remindAt != null && remindAt > System.currentTimeMillis())
                Icons.Rounded.AccessTime else null
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Tucked away!",
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        // Confirm the reminder time inline. Without this, the user
        // taps Save with a reminder set and gets a generic "Saved!"
        // with no acknowledgement that the nudge actually landed —
        // confidence in the reminder feature drops to zero.
        Text(
            if (remindAt != null && remindAt > System.currentTimeMillis())
                "We'll nudge you " + formatReminderAt(remindAt)
            else
                "Find it from the home screen anytime.",
            style = MaterialTheme.typography.bodyLarge,
            color = if (remindAt != null && remindAt > System.currentTimeMillis())
                accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RemindMeChip(remindAt: Long?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val active = remindAt != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (active) scheme.primary.copy(alpha = 0.14f) else scheme.surface)
            .border(
                1.5.dp,
                if (active) scheme.primary else scheme.onSurfaceVariant,
                CircleShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Icon(
            Icons.Rounded.AccessTime,
            contentDescription = null,
            tint = if (active) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (remindAt != null) formatReminderAt(remindAt) else "Remind me",
            style = MaterialTheme.typography.labelLarge,
            color = if (active) scheme.primary else scheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun AddToExistingChip(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(scheme.surface)
            .border(1.5.dp, scheme.onSurfaceVariant, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            "Append to a save",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(6.dp))
        Text("→", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun DuplicateBanner(
    existing: com.ghostgramlabs.pettibox.data.local.SaveItemEntity,
    categoryLabel: String?,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.secondaryContainer)
            .padding(14.dp)
    ) {
        Text(
            "You already saved this",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = scheme.onSecondaryContainer
        )
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append(existing.title.ifBlank { "Untitled" })
                append(" · saved ")
                append(com.ghostgramlabs.pettibox.data.util.TimeFormat.relative(existing.createdAt))
                categoryLabel?.let { append(" · "); append(it) }
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSecondaryContainer.copy(alpha = 0.85f),
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(scheme.primary)
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    "Open it",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    "Save anyway",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionFinder(
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    val bring = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
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
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear collection filter")
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bring)
            .onFocusChanged { focus ->
                onFocusChanged(focus.isFocused)
                if (focus.isFocused) {
                    scope.launch {
                        // Wait for the keyboard to finish animating in so the
                        // IME inset is applied, then scroll the field toward the
                        // top — requesting a tall region below it (not just the
                        // field) so the filtered results aren't left hidden
                        // behind the keyboard.
                        delay(250)
                        val reveal = with(density) { 300.dp.toPx() }
                        runCatching { bring.bringIntoView(Rect(0f, 0f, 1f, reveal)) }
                    }
                }
            }
    )
}

/**
 * A full-width, tappable collection row shown in the Save sheet's search
 * results. Tapping selects (footer commits); the selected row fills with a
 * tint of the collection color and shows a check. Easier to hit than a chip
 * in a horizontal strip, and the check makes the current pick unambiguous.
 */
@Composable
private fun CollectionGroupLabel(label: String) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

private fun collectionGroupLabel(
    category: CategoryEntity,
    suggestedCategory: String?,
    recentCategoryIds: List<String>
): String = when {
    category.id == suggestedCategory -> "Suggested"
    category.id in recentCategoryIds -> "Recently used"
    category.userCreated -> "Your collections"
    else -> "Starter collections"
}

@Composable
private fun CollectionResultRow(
    name: String,
    emoji: String?,
    color: Color,
    selected: Boolean,
    suggested: Boolean = false,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val borderColor = when {
        selected -> color
        suggested -> scheme.primary
        else -> scheme.outline.copy(alpha = 0.4f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) color.copy(alpha = 0.16f) else scheme.surface)
            .border(
                width = if (selected || suggested) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .size(34.dp)
                .background(color.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!emoji.isNullOrBlank()) {
                Text(emoji, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = color,
                modifier = Modifier.size(20.dp)
            )
        } else if (suggested) {
            Text(
                "Suggested",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.primary
            )
        }
    }
}

@Composable
private fun NewCollectionChip(label: String = "New", onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(scheme.surface)
            .border(1.5.dp, scheme.primary, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Icon(
            Icons.Rounded.Add, null,
            tint = scheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}
