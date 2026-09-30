package com.ghostgramlabs.pettibox.ui.screens.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostgramlabs.pettibox.data.local.CategoryEntity
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity
import com.ghostgramlabs.pettibox.data.local.TagWithCount
import com.ghostgramlabs.pettibox.ui.components.CategoryChip
import com.ghostgramlabs.pettibox.ui.components.CollectionColorSeeds
import com.ghostgramlabs.pettibox.ui.components.CollectionEmojiSeeds
import com.ghostgramlabs.pettibox.ui.components.EmptyState
import com.ghostgramlabs.pettibox.ui.components.QuickActionSheet
import com.ghostgramlabs.pettibox.ui.components.ReminderCustomSheet
import com.ghostgramlabs.pettibox.ui.components.ReminderPickerSheet
import com.ghostgramlabs.pettibox.ui.components.SaveCard
import com.ghostgramlabs.pettibox.ui.components.ScreenHeading
import com.ghostgramlabs.pettibox.ui.components.rememberNotificationPermissionRequester
import com.ghostgramlabs.pettibox.ui.components.EditCollectionDialog
import com.ghostgramlabs.pettibox.ui.components.toLongHex
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    onOpenItem: (Long) -> Unit,
    viewModel: CategoriesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val destination = state.destination
    val selectedCategory = (destination as? BrowseDestination.Category)?.let { dest ->
        state.categories.firstOrNull { it.id == dest.id }
    }
    var showDeleteCategory by remember { mutableStateOf(false) }
    var showEditCategory by remember { mutableStateOf(false) }
    var selectedItems by remember(destination) { mutableStateOf<Set<Long>>(emptySet()) }
    var selectionMode by remember(destination) { mutableStateOf(false) }
    // O(1) lookup for items rendered in special destinations where we
    // don't have a hand-picked accent color (Favorites, Archive, Tag drill).
    val categoriesById = remember(state.categories) {
        state.categories.associateBy { it.id }
    }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    if (showDeleteCategory && selectedCategory != null) {
        AlertDialog(
            onDismissRequest = { showDeleteCategory = false },
            title = { Text("Delete ${selectedCategory.name}?") },
            text = { Text("Saves in this collection will stay in PettiBox, but the collection will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteCategory = false
                    viewModel.deleteSelectedCategory()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteCategory = false }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showEditCategory && selectedCategory != null) {
        EditCollectionDialog(
            category = selectedCategory,
            onDismiss = { showEditCategory = false },
            onSave = { name, emoji, colorHex ->
                showEditCategory = false
                viewModel.updateSelectedCategory(name, emoji, colorHex)
            }
        )
    }

    // Recently deleted: tapping a save asks restore-or-delete instead of opening it.
    var trashPromptItem by remember { mutableStateOf<SaveItemEntity?>(null) }
    var showEmptyTrash by remember { mutableStateOf(false) }
    trashPromptItem?.let { item ->
        AlertDialog(
            onDismissRequest = { trashPromptItem = null },
            title = { Text(item.title.ifBlank { "Untitled save" }) },
            text = { Text("Put it back where it was, or delete it for good?") },
            confirmButton = {
                TextButton(onClick = {
                    trashPromptItem = null
                    viewModel.restore(listOf(item))
                    scope.launch { snackbarHostState.showSnackbar("Restored") }
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = {
                    trashPromptItem = null
                    scope.launch {
                        viewModel.deletePermanently(item)
                        snackbarHostState.showSnackbar("Deleted for good")
                    }
                }) { Text("Delete for good", color = MaterialTheme.colorScheme.error) }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
    if (showEmptyTrash) {
        AlertDialog(
            onDismissRequest = { showEmptyTrash = false },
            title = { Text("Empty Recently deleted?") },
            text = { Text("Everything here is removed for good, including its files. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showEmptyTrash = false
                    scope.launch {
                        val n = viewModel.emptyTrash()
                        snackbarHostState.showSnackbar(
                            if (n == 1) "1 save deleted for good" else "$n saves deleted for good"
                        )
                    }
                }) { Text("Empty", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrash = false }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    // Long-press quick-action plumbing for drilled-in items.
    var quickActionItem by remember { mutableStateOf<SaveItemEntity?>(null) }
    var reminderItem by remember { mutableStateOf<SaveItemEntity?>(null) }
    var customReminderItem by remember { mutableStateOf<SaveItemEntity?>(null) }
    val requestNotificationPermission = rememberNotificationPermissionRequester()
    val requestDelete: (SaveItemEntity) -> Unit = { item ->
        scope.launch {
            if (destination == BrowseDestination.Trash) {
                // Already in Recently deleted: this is the for-good delete.
                viewModel.deletePermanently(item)
                snackbarHostState.showSnackbar("Deleted for good")
            } else {
                // Everywhere else a delete lands in Recently deleted for 30
                // days — archived or not — so Undo is just the fast path.
                viewModel.stageDelete(item)
                val result = snackbarHostState.showSnackbar(
                    message = "Moved to Recently deleted",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.undoStagedDelete(item)
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
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (destination) {
                BrowseDestination.Grid -> BrowseGrid(
                    state = state,
                    onBack = onBack,
                    onSelectCategory = { id -> viewModel.navigate(BrowseDestination.Category(id)) },
                    onOpenFavorites = { viewModel.navigate(BrowseDestination.Favorites) },
                    onOpenArchive = { viewModel.navigate(BrowseDestination.Archive) },
                    onOpenReminders = { viewModel.navigate(BrowseDestination.Reminders) },
                    onOpenTags = { viewModel.navigate(BrowseDestination.TagList) },
                    onOpenUnread = { viewModel.navigate(BrowseDestination.Unread) },
                    onOpenTrash = { viewModel.navigate(BrowseDestination.Trash) }
                )

                BrowseDestination.TagList -> TagListView(
                    tags = state.topTags,
                    onBack = { viewModel.navigate(BrowseDestination.Grid) },
                    onPickTag = { name -> viewModel.navigate(BrowseDestination.Tag(name)) }
                )

                else -> DrillView(
                    destination = destination,
                    selectedCategory = selectedCategory,
                    categoriesById = categoriesById,
                    showArchived = state.showArchived,
                    onBack = { viewModel.navigate(BrowseDestination.Grid) },
                    onToggleArchived = { viewModel.toggleArchivedView() },
                    onEditCategory = { showEditCategory = true },
                    onDeleteCategory = { showDeleteCategory = true },
                    sort = state.sort,
                    onSort = viewModel::setSort,
                    selectedIds = selectedItems,
                    selectionMode = selectionMode,
                    onStartSelection = { selectionMode = true },
                    onToggleSelect = { item ->
                        selectedItems = if (item.id in selectedItems) selectedItems - item.id else selectedItems + item.id
                    },
                    onClearSelection = {
                        selectedItems = emptySet()
                        selectionMode = false
                    },
                    onArchiveSelected = { items ->
                        viewModel.archiveItems(items)
                        selectedItems = emptySet()
                        selectionMode = false
                    },
                    onDeleteSelected = { items ->
                        selectedItems = emptySet()
                        selectionMode = false
                        scope.launch {
                            if (destination == BrowseDestination.Trash) {
                                viewModel.deleteItemsPermanently(items)
                                snackbarHostState.showSnackbar(
                                    if (items.size == 1) "Deleted for good"
                                    else "${items.size} saves deleted for good"
                                )
                            } else {
                                viewModel.stageDeleteItems(items)
                                val result = snackbarHostState.showSnackbar(
                                    message = if (items.size == 1) "Moved to Recently deleted"
                                    else "${items.size} saves moved to Recently deleted",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Long
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.undoStagedDeleteItems(items)
                                }
                            }
                        }
                    },
                    onRestoreSelected = { items ->
                        selectedItems = emptySet()
                        selectionMode = false
                        viewModel.restore(items)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (items.size == 1) "Restored" else "${items.size} saves restored"
                            )
                        }
                    },
                    onEmptyTrash = { showEmptyTrash = true },
                    canEmptyTrash = state.trashCount > 0,
                    onOpenItem = onOpenItem,
                    onLongPressItem = { quickActionItem = it },
                    onTrashItem = { trashPromptItem = it },
                    drillItems = viewModel.drillItems
                )
            }
        }
    }

}

@Composable
private fun BrowseGrid(
    state: CategoriesState,
    onBack: () -> Unit,
    onSelectCategory: (String) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenUnread: () -> Unit,
    onOpenTrash: () -> Unit
) {
    var collectionQuery by remember { mutableStateOf("") }
    val visibleCategories = remember(state.categories, collectionQuery) {
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
    ScreenHeading(
        title = "Browse"
    )
    // A staggered grid would be wrong here — the special row needs a
    // full-line span so the three pills always sit on one row and the
    // collection grid lives in its own 2-up rhythm beneath.
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(156.dp),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
        verticalItemSpacing = 12.dp,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Column {
                // Special collections sit above the user's stashes. These
                // are virtual destinations bound to flags rather than
                // category_id — Favorites = is_favorite, Archive =
                // is_archived (regardless of collection, so orphan
                // archived saves are reachable), Tags = the hub.
                SpecialRow(
                    items = listOfNotNull(
                        SpecialEntry(
                            icon = Icons.Rounded.AutoStories,
                            label = "Unread",
                            count = state.unreadCount,
                            accent = UnreadAccent,
                            onClick = onOpenUnread
                        ),
                        SpecialEntry(
                            icon = Icons.Rounded.Favorite,
                            label = "Favorites",
                            count = state.favoriteCount,
                            accent = Color(0xFFE85A6E),
                            onClick = onOpenFavorites
                        ),
                        SpecialEntry(
                            icon = Icons.Rounded.Archive,
                            label = "Archive",
                            count = state.archivedCount,
                            accent = Color(0xFF8B7355),
                            onClick = onOpenArchive
                        ),
                        SpecialEntry(
                            icon = Icons.Rounded.AccessTime,
                            label = "Reminders",
                            count = state.reminderCount,
                            accent = Color(0xFF2F9B8F),
                            onClick = onOpenReminders
                        ),
                        SpecialEntry(
                            icon = Icons.Rounded.LocalOffer,
                            label = "Tags",
                            count = state.topTags.size,
                            accent = Color(0xFF5B7BC9),
                            onClick = onOpenTags
                        ),
                        // Only worth a pill while there's something to restore.
                        if (state.trashCount > 0) SpecialEntry(
                            icon = Icons.Rounded.RestoreFromTrash,
                            label = "Recently deleted",
                            count = state.trashCount,
                            accent = TrashAccent,
                            onClick = onOpenTrash
                        ) else null
                    )
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "Collections",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
                CollectionFinder(
                    query = collectionQuery,
                    onQueryChange = { collectionQuery = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        // Use the count-based overload here because the file has three
        // `items` extensions in scope (regular Lazy, LazyGrid, and
        // LazyStaggeredGrid) and Kotlin can't disambiguate a List<T>
        // overload at this call site without an explicit type ascription.
        items(
            count = visibleCategories.size,
            key = { idx -> visibleCategories[idx].id },
            contentType = { "category" }
        ) { idx ->
            val c = visibleCategories[idx]
            val tilt = if (c.sortOrder % 2 == 0) -1f else 1f
            CategoryTile(
                c = c,
                count = state.countsByCategory[c.id] ?: 0,
                tilt = tilt,
                onClick = { onSelectCategory(c.id) }
            )
        }
        if (visibleCategories.isEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Text(
                    "No collection matches \"$collectionQuery\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun CollectionFinder(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("Find a collection") },
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
                    Icon(Icons.Rounded.Close, contentDescription = "Clear collection search")
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.padding(top = 2.dp, bottom = 10.dp)
    )
}

private val UnreadAccent = Color(0xFFD9822B)
private val TrashAccent = Color(0xFF8A8580)

private data class SpecialEntry(
    val icon: ImageVector,
    val label: String,
    val count: Int,
    val accent: Color,
    val onClick: () -> Unit
)

/**
 * Horizontally-scrolling row of hub pills. Was a weighted 4-up FlowRow
 * that compressed each label to ~80 dp wide — "Favorites" and "Reminders"
 * wrapped to a second line inside the pill, making the row look broken.
 * A scrolling row lets each pill take its natural width, fits more hubs
 * if we add them later, and re-uses the same horizontal-strip rhythm
 * the rest of Home uses (CategoryStrip / SourceStrip).
 */
@Composable
private fun SpecialRow(items: List<SpecialEntry>) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(items, key = { it.label }) { e -> SpecialPill(entry = e) }
    }
}

@Composable
private fun SpecialPill(entry: SpecialEntry) {
    val scheme = MaterialTheme.colorScheme
    // Horizontal layout (icon + Column of label/count) gives the label
    // room to stay on a single line at every reasonable label length,
    // and keeps the pill compact vertically so the Browse header
    // doesn't dominate the screen.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(entry.accent.copy(alpha = 0.14f))
            .clickable(onClick = entry.onClick)
            .padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(entry.accent.copy(alpha = 0.95f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                entry.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                entry.label,
                maxLines = 1,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = scheme.onSurface
            )
            Text(
                if (entry.count == 0) "Empty" else entry.count.toString(),
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TagListView(
    tags: List<TagWithCount>,
    onBack: () -> Unit,
    onPickTag: (String) -> Unit
) {
    ScreenHeading(
        title = "Tags",
        subtitle = "Cross-cutting labels you've added to saves",
        leading = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        }
    )
    if (tags.isEmpty()) {
        EmptyState(
            emoji = "🏷",
            headline = "No tags yet",
            body = "Tags are short labels (#urgent, #read-later) you can add to a save. They cut across collections — one save can have many tags. Add tags from the save sheet next time you stash something."
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(tags, key = { it.id }) { t ->
            TagRow(name = t.name, count = t.count, onClick = { onPickTag(t.name) })
        }
    }
}

@Composable
private fun TagRow(name: String, count: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(scheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.LocalOffer,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "#$name",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = scheme.onSurface
            )
            Text(
                "$count save${if (count == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun DrillView(
    destination: BrowseDestination,
    selectedCategory: CategoryEntity?,
    categoriesById: Map<String, CategoryEntity>,
    showArchived: Boolean,
    onBack: () -> Unit,
    onToggleArchived: () -> Unit,
    onEditCategory: () -> Unit,
    onDeleteCategory: () -> Unit,
    sort: BrowseSort,
    onSort: (BrowseSort) -> Unit,
    selectedIds: Set<Long>,
    selectionMode: Boolean,
    onStartSelection: () -> Unit,
    onToggleSelect: (SaveItemEntity) -> Unit,
    onClearSelection: () -> Unit,
    onArchiveSelected: (List<SaveItemEntity>) -> Unit,
    onDeleteSelected: (List<SaveItemEntity>) -> Unit,
    onRestoreSelected: (List<SaveItemEntity>) -> Unit,
    onEmptyTrash: () -> Unit,
    canEmptyTrash: Boolean,
    onOpenItem: (Long) -> Unit,
    onLongPressItem: (SaveItemEntity) -> Unit,
    onTrashItem: (SaveItemEntity) -> Unit,
    drillItems: kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<SaveItemEntity>>
) {
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }
    val inTrash = destination == BrowseDestination.Trash
    val title = when (destination) {
        BrowseDestination.Favorites -> "Favorites"
        BrowseDestination.Archive -> "Archive"
        BrowseDestination.Reminders -> "Reminders"
        BrowseDestination.Unread -> "Unread"
        BrowseDestination.Trash -> "Recently deleted"
        is BrowseDestination.Tag -> "#${destination.name}"
        is BrowseDestination.Category ->
            if (selectedCategory != null) "${selectedCategory.emoji} ${selectedCategory.name}"
            else "Collection"
        else -> ""
    }
    val subtitle = when (destination) {
        BrowseDestination.Favorites -> "Saves you marked with a heart"
        BrowseDestination.Archive -> "Everything you've tucked away"
        BrowseDestination.Reminders -> "Upcoming nudges, sorted by time"
        BrowseDestination.Unread -> "Saved, but not opened yet"
        BrowseDestination.Trash -> "Kept for 30 days, then removed for good. Tap one to restore it."
        is BrowseDestination.Tag -> "Saves carrying this tag"
        is BrowseDestination.Category -> if (showArchived) "Archived saves" else null
        else -> null
    }
    val defaultAccent = MaterialTheme.colorScheme.primary
    val accent: Color = when (destination) {
        BrowseDestination.Favorites -> Color(0xFFE85A6E)
        BrowseDestination.Archive -> Color(0xFF8B7355)
        BrowseDestination.Reminders -> Color(0xFF2F9B8F)
        BrowseDestination.Unread -> UnreadAccent
        BrowseDestination.Trash -> TrashAccent
        is BrowseDestination.Tag -> Color(0xFF5B7BC9)
        is BrowseDestination.Category -> selectedCategory?.let { Color(it.colorHex) } ?: defaultAccent
        else -> defaultAccent
    }
    ScreenHeading(
        title = title,
        subtitle = subtitle,
        leading = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        },
        trailing = {
            // Per-category archive toggle is still useful inside a single
            // collection. The first-class Archive destination covers the
            // global case.
            if (destination is BrowseDestination.Category) {
                Row {
                    IconButton(onClick = onToggleArchived) {
                        Icon(
                            if (showArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                            contentDescription = if (showArchived) "Show active" else "Show archived",
                            tint = if (showArchived) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onEditCategory) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Edit collection")
                    }
                    IconButton(onClick = onDeleteCategory) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Delete collection",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            if (inTrash && canEmptyTrash) {
                TextButton(onClick = onEmptyTrash) {
                    Text("Empty", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        }
    )

    val items = drillItems.collectAsLazyPagingItems()
    val selectedVisibleItems = (0 until items.itemCount)
        .mapNotNull { idx -> items.peek(idx) }
        .filter { it.id in selectedIds }
    val isEmpty = items.itemCount == 0 &&
        items.loadState.refresh is androidx.paging.LoadState.NotLoading

    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = {
                Text(
                    if (inTrash) "Delete ${selectedVisibleItems.size} for good?"
                    else "Delete ${selectedVisibleItems.size} selected?"
                )
            },
            text = {
                Text(
                    if (inTrash) "They're removed for good, including their files. This can't be undone."
                    else "They move to Recently deleted (in Browse) for 30 days, so you can still bring them back."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBulkDeleteConfirm = false
                    onDeleteSelected(selectedVisibleItems)
                }) {
                    Text(
                        if (inTrash) "Delete for good" else "Delete",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (isEmpty) {
        val (emoji, headline, body) = when (destination) {
            BrowseDestination.Favorites -> Triple(
                "❤️",
                "No favorites yet",
                "Tap the heart on a save (long-press a card → ❤️) and it'll live here."
            )
            BrowseDestination.Archive -> Triple(
                "🗃",
                "Nothing archived",
                "Archive a save (long-press → archive) to tuck it away without deleting it."
            )
            BrowseDestination.Reminders -> Triple(
                "⏰",
                "No reminders waiting",
                "Set a reminder from a save card or the save sheet, and upcoming nudges will gather here."
            )
            BrowseDestination.Unread -> Triple(
                "📭",
                "All caught up",
                "You've opened everything you saved. New saves wait here until you open them."
            )
            BrowseDestination.Trash -> Triple(
                "🧺",
                "Nothing deleted",
                "Deleted saves stay here for 30 days so you can change your mind."
            )
            is BrowseDestination.Tag -> Triple(
                "🏷",
                "No saves with #${destination.name}",
                "Items tagged #${destination.name} will appear here."
            )
            else -> Triple(
                "📬",
                if (showArchived) "No archived saves" else "Nothing here yet",
                if (showArchived) "Archived items appear here. Toggle off to see your active saves."
                else "Save something into ${selectedCategory?.name ?: "this"} from the share sheet, then it'll show up."
            )
        }
        EmptyState(emoji = emoji, headline = headline, body = body, accent = accent)
        return
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(156.dp),
        contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp),
        verticalItemSpacing = 12.dp,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Column {
                // Recently deleted is always newest-deleted first.
                if (!inTrash) SortStrip(selected = sort, onSelect = onSort)
                if (selectionMode) {
                    Spacer(Modifier.height(8.dp))
                    if (selectedIds.isEmpty()) {
                        SelectionHintBar(onClear = onClearSelection)
                    } else {
                        BulkActionBar(
                            count = selectedIds.size,
                            onClear = onClearSelection,
                            primaryLabel = if (inTrash) "Restore" else "Archive",
                            onPrimary = {
                                if (inTrash) onRestoreSelected(selectedVisibleItems)
                                else onArchiveSelected(selectedVisibleItems)
                            },
                            onDelete = { showBulkDeleteConfirm = true }
                        )
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onStartSelection) {
                        Text("Select")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        items(
            count = items.itemCount,
            key = { idx -> items.peek(idx)?.id ?: idx }
        ) { idx ->
            val item = items[idx] ?: return@items
            // In Favorites / Archive / Tag drills the per-item category
            // accent matters: items come from across collections, so a
            // single screen-wide accent would erase the visual cue. Use
            // the item's own category when available; fall back to the
            // destination accent.
            val perItemCategory = item.categoryId?.let { categoriesById[it] }
            val perItemAccent = perItemCategory?.let { Color(it.colorHex) } ?: accent
            Box {
                SaveCard(
                    item = item,
                    accent = perItemAccent,
                    categoryEmoji = perItemCategory?.emoji,
                    categoryName = perItemCategory?.name,
                    onClick = {
                        when {
                            selectionMode -> onToggleSelect(item)
                            inTrash -> onTrashItem(item)
                            else -> onOpenItem(item.id)
                        }
                    },
                    onLongClick = when {
                        selectionMode -> null
                        inTrash -> ({ onTrashItem(item) })
                        else -> ({ onLongPressItem(item) })
                    }
                )
                if (item.id in selectedIds) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(28.dp)
                    )
                }
            }
        }
    }
}

/**
 * Sort selector styled to match the rest of the app: rounded chips with
 * a slight alternating tilt (same hand-arranged feel as CategoryChip),
 * selected chip fills with primary, unselected uses a soft accent tint.
 * Earlier this used hard-edged outlined-button rectangles that looked
 * borrowed from a different design system.
 */
@Composable
private fun SortStrip(selected: BrowseSort, onSelect: (BrowseSort) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(BrowseSort.entries, key = { it.name }) { sort ->
            CategoryChip(
                label = sort.label,
                emoji = sort.identityMark,
                color = sort.identityAccent,
                selected = selected == sort,
                tilt = if (sort.ordinal % 2 == 0) -1.5f else 1.5f,
                onClick = { onSelect(sort) }
            )
        }
    }
}

private val BrowseSort.identityMark: String
    get() = when (this) {
        BrowseSort.NEWEST -> "↓"
        BrowseSort.OLDEST -> "↑"
        BrowseSort.UPDATED -> "✎"
        BrowseSort.REMINDER -> "⏰"
    }

private val BrowseSort.identityAccent: Color
    @Composable
    get() = when (this) {
        BrowseSort.NEWEST -> MaterialTheme.colorScheme.secondary
        BrowseSort.OLDEST -> MaterialTheme.colorScheme.tertiary
        BrowseSort.UPDATED -> Color(0xFF6E7FB8)
        BrowseSort.REMINDER -> Color(0xFF2F9B8F)
    }

@Composable
private fun SelectionHintBar(onClear: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Exit selection")
        }
        Text(
            "Tap saves to select",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = scheme.primary
        )
    }
}

@Composable
private fun BulkActionBar(
    count: Int,
    onClear: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Rounded.Close, contentDescription = "Clear selection")
        }
        Text(
            "$count selected",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onPrimary) { Text(primaryLabel) }
        TextButton(onClick = onDelete) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CategoryTile(c: CategoryEntity, count: Int, tilt: Float, onClick: () -> Unit) {
    val color = Color(c.colorHex)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 168.dp)
            .rotate(tilt)
            .clip(RoundedCornerShape(22.dp))
            .background(color.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(color.copy(alpha = 0.95f)),
            contentAlignment = Alignment.Center
        ) { Text(c.emoji, style = MaterialTheme.typography.displaySmall) }
        Column {
            Text(
                c.name,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                if (count == 0) "Empty" else "$count item${if (count == 1) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// EditCollectionDialog moved to components/EditCollectionDialog.kt so
// Settings can open the same dialog without duplicating the rename /
// emoji / color UX. Imported above.
