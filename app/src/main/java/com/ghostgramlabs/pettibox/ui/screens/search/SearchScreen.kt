package com.ghostgramlabs.pettibox.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.ghostgramlabs.pettibox.domain.model.ContentType
import com.ghostgramlabs.pettibox.domain.model.SourceApp
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity
import com.ghostgramlabs.pettibox.ui.components.CategoryChip
import com.ghostgramlabs.pettibox.ui.components.EmptyState
import com.ghostgramlabs.pettibox.ui.components.QuickActionSheet
import com.ghostgramlabs.pettibox.ui.components.ReminderCustomSheet
import com.ghostgramlabs.pettibox.ui.components.ReminderPickerSheet
import com.ghostgramlabs.pettibox.ui.components.SaveCard
import com.ghostgramlabs.pettibox.ui.components.ScreenHeading
import com.ghostgramlabs.pettibox.ui.components.SearchField
import com.ghostgramlabs.pettibox.ui.components.SectionHeader
import com.ghostgramlabs.pettibox.ui.components.rememberNotificationPermissionRequester
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    initialQuery: String = "",
    initialSource: String? = null,
    onOpenItem: (Long) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val results = viewModel.results.collectAsLazyPagingItems()
    var showFilters by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // O(1) accent lookup per result card — mirrors the Home perf pass.
    // Without this, every scroll frame ran a linear scan through
    // state.categories per visible row.
    val categoriesById = remember(state.categories) {
        state.categories.associateBy { it.id }
    }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Long-press quick-action plumbing — identical pattern to HomeScreen.
    var quickActionItem by remember { mutableStateOf<SaveItemEntity?>(null) }
    var reminderItem by remember { mutableStateOf<SaveItemEntity?>(null) }
    var customReminderItem by remember { mutableStateOf<SaveItemEntity?>(null) }
    val requestNotificationPermission = rememberNotificationPermissionRequester()
    val requestDelete: (SaveItemEntity) -> Unit = { item ->
        scope.launch {
            // Deleting moves the save to Recently deleted (Browse) for 30
            // days — archived or not — so Undo here is just the fast path.
            viewModel.stageDelete(item)
            val result = snackbarHostState.showSnackbar(
                message = "Moved to Recently deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoStagedDelete(item)
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
                if (at != null) {
                    requestNotificationPermission { viewModel.setRemindAt(item, at) }
                } else {
                    viewModel.setRemindAt(item, null)
                }
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

    LaunchedEffect(initialQuery, initialSource) {
        viewModel.applyRouteFilters(initialQuery, initialSource)
    }

    val activeFilterCount = listOfNotNull(
        state.typeFilter, state.categoryFilter, state.sourceFilter, state.tagFilter
    ).size + if (state.reminderFilter) 1 else 0

    if (showFilters) {
        ModalBottomSheet(
            onDismissRequest = { showFilters = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            FilterSheetBody(
                state = state,
                onToggleType = viewModel::toggleType,
                onToggleCategory = viewModel::toggleCategory,
                onToggleSource = viewModel::toggleSource,
                onToggleTag = viewModel::toggleTag,
                onToggleReminders = viewModel::toggleReminders,
                onClear = viewModel::clearFilters,
                onClose = { showFilters = false }
            )
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ScreenHeading(
                title = "Look it up",
                subtitle = "Titles, notes, saved articles, and text inside images."
            )
            SearchField(
                value = state.query,
                placeholder = "Search anything you saved",
                onValueChange = viewModel::onQuery,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(12.dp))

            // Single compact filter row: a Filters button (with active count) +
            // a Clear chip when anything is set + a row of currently-active chips.
            FilterToolbar(
                state = state,
                activeFilterCount = activeFilterCount,
                onOpen = { showFilters = true },
                onClear = viewModel::clearFilters,
                onToggleType = viewModel::toggleType,
                onToggleCategory = viewModel::toggleCategory,
                onToggleSource = viewModel::toggleSource,
                onToggleTag = viewModel::toggleTag,
                onToggleReminders = viewModel::toggleReminders
            )
            if (state.resultCount > 0) {
                Spacer(Modifier.height(8.dp))
                SearchSortStrip(selected = state.sort, onSelect = viewModel::setSort)
            }

            Spacer(Modifier.height(8.dp))

            val anyFilter = activeFilterCount > 0
            when {
                // Blank query, no filters: render the Quick-suggestion
                // strip in-line with a friendly empty state below. The
                // previous layout stacked the suggestions ABOVE a
                // separately-centered EmptyState, which read as two
                // disjoint screens. Now they share one composition.
                state.query.isBlank() && !anyFilter -> {
                    SearchDiscovery(
                        tags = state.knownTags,
                        sources = state.sources,
                        onToggleType = viewModel::toggleType,
                        onToggleTag = viewModel::toggleTag,
                        onToggleSource = viewModel::toggleSource,
                        onToggleReminders = viewModel::toggleReminders
                    )
                }
                state.resultCount == 0 -> NoResultsRecovery(
                    query = state.query,
                    hasFilters = anyFilter,
                    onClearFilters = viewModel::clearFilters,
                    onClearQuery = { viewModel.onQuery("") },
                    onOpenFilters = { showFilters = true }
                )
                else -> {
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Adaptive(156.dp),
                        contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 96.dp),
                        verticalItemSpacing = 12.dp,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item(span = StaggeredGridItemSpan.FullLine) {
                            // SectionHeader gives results the same
                            // squiggle-underline treatment as every other
                            // section on Home and Browse, so the page
                            // reads as part of the same app.
                            SectionHeader(
                                title = "Matches",
                                subtitle = "${state.resultCount} found"
                            )
                        }
                        items(
                            count = results.itemCount,
                            key = results.itemKey { it.id },
                            contentType = { "result" }
                        ) { index ->
                            val item = results[index] ?: return@items
                            val cat = categoriesById[item.categoryId]
                            SaveCard(
                                item = item,
                                accent = cat?.let { Color(it.colorHex) } ?: MaterialTheme.colorScheme.primary,
                                categoryEmoji = cat?.emoji,
                                categoryName = cat?.name,
                                onClick = { onOpenItem(item.id) },
                                onLongClick = { quickActionItem = item }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSortStrip(selected: SearchSort, onSelect: (SearchSort) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(SearchSort.entries, key = { _, sort -> sort.name }) { index, sort ->
            CategoryChip(
                label = sort.label,
                emoji = sort.emoji,
                color = sort.accent,
                selected = selected == sort,
                tilt = if (index % 2 == 0) -2f else 1.5f,
                onClick = { onSelect(sort) }
            )
        }
    }
}

@Composable
private fun SearchDiscovery(
    tags: List<String>,
    sources: List<SourceApp>,
    onToggleType: (ContentType) -> Unit,
    onToggleTag: (String) -> Unit,
    onToggleSource: (String) -> Unit,
    onToggleReminders: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(8.dp))
        SectionHeader(
            title = "Start anywhere",
            subtitle = "Use a type, source, or tag when you don't remember the exact words"
        )
        Spacer(Modifier.height(8.dp))
        SuggestionGroupTitle("Types")
        QuickTypeSuggestions(
            onToggleType = onToggleType,
            onToggleReminders = onToggleReminders
        )
        if (sources.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SuggestionGroupTitle("Sources")
            QuickSourceSuggestions(sources = sources, onToggleSource = onToggleSource)
        }
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SuggestionGroupTitle("Tags")
            QuickTagSuggestions(tags = tags, onToggleTag = onToggleTag)
        }
        Spacer(Modifier.height(18.dp))
        EmptyState(
            emoji = "\uD83D\uDD0D",
            headline = "Find anything you stashed",
            body = "Search titles, notes, links, sources, tags, English text inside images or PDFs, and articles saved for offline reading. Archive is included, so tucked-away saves still show up."
        )
    }
}

@Composable
private fun SuggestionGroupTitle(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

@Composable
private fun QuickTypeSuggestions(
    onToggleType: (ContentType) -> Unit,
    onToggleReminders: () -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            CategoryChip(
                label = "Screenshots",
                emoji = "\uD83D\uDDBC",
                color = MaterialTheme.colorScheme.primary,
                selected = false,
                tilt = -2f,
                onClick = { onToggleType(ContentType.IMAGE) }
            )
        }
        item {
            CategoryChip(
                label = "Links",
                emoji = "\uD83D\uDD17",
                color = MaterialTheme.colorScheme.secondary,
                selected = false,
                tilt = 1.5f,
                onClick = { onToggleType(ContentType.LINK) }
            )
        }
        item {
            CategoryChip(
                label = "PDFs",
                emoji = "\uD83D\uDCC4",
                color = MaterialTheme.colorScheme.tertiary,
                selected = false,
                tilt = -2f,
                onClick = { onToggleType(ContentType.PDF) }
            )
        }
        item {
            CategoryChip(
                label = "Upcoming reminders",
                emoji = "\u23F0",
                color = Color(0xFF2F9B8F),
                selected = false,
                tilt = 1.5f,
                onClick = onToggleReminders
            )
        }
        item {
            CategoryChip(
                label = "Notes",
                emoji = "\uD83D\uDDD2",
                color = MaterialTheme.colorScheme.primary,
                selected = false,
                tilt = 1.5f,
                onClick = { onToggleType(ContentType.NOTE) }
            )
        }
    }
}

@Composable
private fun QuickTagSuggestions(
    tags: List<String>,
    onToggleTag: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tags.take(8).forEachIndexed { index, tag ->
            item(key = "tag-$tag") {
                CategoryChip(
                    label = "#$tag",
                    emoji = null,
                    color = MaterialTheme.colorScheme.tertiary,
                    selected = false,
                    tilt = if (index % 2 == 0) -2f else 1.5f,
                    onClick = { onToggleTag(tag) }
                )
            }
        }
    }
}

@Composable
private fun QuickSourceSuggestions(
    sources: List<SourceApp>,
    onToggleSource: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sources.take(8).forEachIndexed { index, source ->
            item(key = "source-${source.name}") {
                CategoryChip(
                    label = source.displayName,
                    emoji = source.emoji,
                    color = MaterialTheme.colorScheme.secondary,
                    selected = false,
                    tilt = if (index % 2 == 0) 1.5f else -2f,
                    onClick = { onToggleSource(source.name) }
                )
            }
        }
    }
}

@Composable
private fun NoResultsRecovery(
    query: String,
    hasFilters: Boolean,
    onClearFilters: () -> Unit,
    onClearQuery: () -> Unit,
    onOpenFilters: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        EmptyState(
            emoji = "\uD83E\uDD14",
            headline = "Nothing matched",
            body = "Try a shorter word, remove a filter, or search another source. OCR works best with English and may still be catching up."
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (hasFilters) {
                item {
                    CategoryChip(
                        label = "Clear filters",
                        emoji = "\u2715",
                        color = MaterialTheme.colorScheme.primary,
                        selected = false,
                        tilt = -2f,
                        onClick = onClearFilters
                    )
                }
            }
            if (query.isNotBlank()) {
                item {
                    CategoryChip(
                        label = "Clear search",
                        emoji = "\uD83D\uDD0D",
                        color = MaterialTheme.colorScheme.secondary,
                        selected = false,
                        tilt = 1.5f,
                        onClick = onClearQuery
                    )
                }
            }
            item {
                CategoryChip(
                    label = "Change filters",
                    emoji = "\u2699",
                    color = MaterialTheme.colorScheme.tertiary,
                    selected = false,
                    tilt = -2f,
                    onClick = onOpenFilters
                )
            }
        }
    }
}

@Composable
private fun FilterToolbar(
    state: SearchState,
    activeFilterCount: Int,
    onOpen: () -> Unit,
    onClear: () -> Unit,
    onToggleType: (ContentType) -> Unit,
    onToggleCategory: (String) -> Unit,
    onToggleSource: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onToggleReminders: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (activeFilterCount > 0) scheme.primary.copy(alpha = 0.14f)
                    else scheme.surface
                )
                .border(
                    width = 1.5.dp,
                    color = if (activeFilterCount > 0) scheme.primary else scheme.outline,
                    shape = RoundedCornerShape(12.dp)
                )
                .clickable(onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Icon(
                Icons.Rounded.Tune,
                contentDescription = "Open filters",
                tint = if (activeFilterCount > 0) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (activeFilterCount > 0) "Filters · $activeFilterCount" else "Filters",
                style = MaterialTheme.typography.labelLarge,
                color = if (activeFilterCount > 0) scheme.primary else scheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (activeFilterCount > 0) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClear) {
                Text("Clear", color = scheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.width(8.dp))

        // Compact horizontal display of currently-active filters. Tapping
        // the trailing ✕ removes the filter — the label body is read-only
        // so a tap on the chip text doesn't silently kill a filter the
        // user is using as a reference for what they're looking at.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.typeFilter?.let { t ->
                item { ActivePill(t.label()) { onToggleType(t) } }
            }
            state.categoryFilter?.let { cid ->
                val c = state.categories.firstOrNull { it.id == cid }
                if (c != null) {
                    item { ActivePill("${c.emoji} ${c.name}") { onToggleCategory(cid) } }
                }
            }
            state.sourceFilter?.let { sf ->
                val sa = runCatching { SourceApp.valueOf(sf) }.getOrNull()
                if (sa != null) {
                    item { ActivePill("${sa.emoji} ${sa.displayName}") { onToggleSource(sf) } }
                }
            }
            state.tagFilter?.let { tag ->
                item { ActivePill("#$tag") { onToggleTag(tag) } }
            }
            if (state.reminderFilter) {
                item { ActivePill("Upcoming reminders") { onToggleReminders() } }
            }
        }
    }
}

@Composable
private fun ActivePill(label: String, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.primary.copy(alpha = 0.12f))
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
        )
        Text(
            "✕",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.primary.copy(alpha = 0.7f),
            modifier = Modifier
                .clickable(onClick = onRemove)
                .padding(start = 2.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
        )
    }
}

@Composable
private fun FilterSheetBody(
    state: SearchState,
    onToggleType: (ContentType) -> Unit,
    onToggleCategory: (String) -> Unit,
    onToggleSource: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onToggleReminders: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Filters",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("Clear all") }
            TextButton(onClick = onClose) { Text("Done") }
        }

        Spacer(Modifier.height(16.dp))
        FilterGroupTitle("Type")
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(typeChoices()) { idx, triple ->
                val (t, label, emoji) = triple
                CategoryChip(
                    label = label, emoji = emoji,
                    color = MaterialTheme.colorScheme.primary,
                    selected = state.typeFilter == t,
                    tilt = if (idx % 2 == 0) -2f else 1.5f,
                    onClick = { onToggleType(t) }
                )
            }
            item {
                CategoryChip(
                    label = "Upcoming reminders",
                    emoji = "\u23F0",
                    color = Color(0xFF2F9B8F),
                    selected = state.reminderFilter,
                    tilt = -2f,
                    onClick = onToggleReminders
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        FilterGroupTitle("Collection")
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.categories, key = { it.id }) { c ->
                val tilt = if (c.sortOrder % 2 == 0) -2f else 1.5f
                CategoryChip(
                    label = c.name, emoji = c.emoji,
                    color = Color(c.colorHex),
                    selected = state.categoryFilter == c.id,
                    tilt = tilt,
                    onClick = { onToggleCategory(c.id) }
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        FilterGroupTitle("Source")
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SourceApp.entries.toList(), key = { it.name }) { s ->
                CategoryChip(
                    label = s.displayName, emoji = s.emoji,
                    color = MaterialTheme.colorScheme.secondary,
                    selected = state.sourceFilter == s.name,
                    tilt = if (s.ordinal % 2 == 0) -2f else 1.5f,
                    onClick = { onToggleSource(s.name) }
                )
            }
        }

        if (state.knownTags.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            FilterGroupTitle("Tags")
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.knownTags, key = { _, t -> t }) { idx, t ->
                    CategoryChip(
                        label = "#$t",
                        emoji = null,
                        color = MaterialTheme.colorScheme.tertiary,
                        selected = state.tagFilter.equals(t, ignoreCase = true),
                        tilt = if (idx % 2 == 0) -2f else 1.5f,
                        onClick = { onToggleTag(t) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterGroupTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun typeChoices() = listOf(
    Triple(ContentType.LINK, "Links", "\uD83D\uDD17"),
    Triple(ContentType.IMAGE, "Images", "\uD83D\uDDBC"),
    Triple(ContentType.PDF, "PDFs", "\uD83D\uDCC4"),
    Triple(ContentType.TEXT, "Text", "\uD83D\uDCDD"),
    Triple(ContentType.NOTE, "Notes", "\uD83D\uDDD2")
)

private val SearchSort.emoji: String
    get() = when (this) {
        SearchSort.RELEVANT -> "\u2728"
        SearchSort.NEWEST -> "\u2193"
        SearchSort.OLDEST -> "\u2191"
        SearchSort.UPDATED -> "\u270E"
        SearchSort.REMINDER -> "\u23F0"
    }

private val SearchSort.accent: Color
    @Composable get() = when (this) {
        SearchSort.RELEVANT -> MaterialTheme.colorScheme.primary
        SearchSort.NEWEST -> MaterialTheme.colorScheme.secondary
        SearchSort.OLDEST -> MaterialTheme.colorScheme.tertiary
        SearchSort.UPDATED -> Color(0xFF6E7FB8)
        SearchSort.REMINDER -> Color(0xFF2F9B8F)
    }

private fun ContentType.label(): String = when (this) {
    ContentType.LINK -> "Link"
    ContentType.IMAGE -> "Image"
    ContentType.PDF -> "PDF"
    ContentType.TEXT -> "Text"
    ContentType.NOTE -> "Note"
    ContentType.FILE -> "File"
}
