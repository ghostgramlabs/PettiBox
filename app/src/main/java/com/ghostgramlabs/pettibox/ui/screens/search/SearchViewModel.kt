package com.ghostgramlabs.pettibox.ui.screens.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ghostgramlabs.pettibox.data.local.CategoryEntity
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity
import com.ghostgramlabs.pettibox.data.reminders.ReminderScheduler
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import com.ghostgramlabs.pettibox.domain.model.ContentType
import com.ghostgramlabs.pettibox.domain.model.SourceApp
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SearchState(
    val query: String = "",
    val sourceFilter: String? = null,
    val categoryFilter: String? = null,
    val typeFilter: ContentType? = null,
    val tagFilter: String? = null,
    val reminderFilter: Boolean = false,
    /** Total matches; the matches themselves page in via SearchViewModel.results. */
    val resultCount: Int = 0,
    val categories: List<CategoryEntity> = emptyList(),
    val knownTags: List<String> = emptyList(),
    val sources: List<SourceApp> = emptyList(),
    val sort: SearchSort = SearchSort.RELEVANT
)

enum class SearchSort(val label: String) {
    RELEVANT("Relevant"),
    NEWEST("Newest"),
    OLDEST("Oldest"),
    UPDATED("Recently edited"),
    REMINDER("Reminder time")
}

/** What the results area should run. */
private sealed interface SearchTarget {
    /** Blank query and no filters: the discovery screen shows instead. */
    data object Idle : SearchTarget
    /** A query of only punctuation/operators: nothing can match it. */
    data object NoMatch : SearchTarget
    data class Run(val spec: SaveRepository.SearchSpec) : SearchTarget
}

private data class Filters(
    val source: String?,
    val category: String?,
    val type: ContentType?,
    val tag: String?,
    val reminders: Boolean
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: SaveRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _sourceFilter = MutableStateFlow<String?>(null)
    private val _categoryFilter = MutableStateFlow<String?>(null)
    private val _typeFilter = MutableStateFlow<ContentType?>(null)
    private val _tagFilter = MutableStateFlow<String?>(null)
    private val _reminderFilter = MutableStateFlow(false)
    private val _sort = MutableStateFlow(SearchSort.RELEVANT)

    private val filters = combine(
        _sourceFilter, _categoryFilter, _typeFilter, _tagFilter, _reminderFilter
    ) { src, cat, type, tag, reminders -> Filters(src, cat, type, tag, reminders) }

    private val target: Flow<SearchTarget> = combine(
        _query.debounce(180).distinctUntilChanged(),
        filters
    ) { q, f ->
        val haveFilters = f.source != null || f.category != null ||
            f.type != null || f.tag != null || f.reminders
        val spec = repo.searchSpec(q, f.source, f.category, f.type?.name, f.tag, f.reminders)
        when {
            q.isBlank() && !haveFilters -> SearchTarget.Idle
            // Don't let an unsearchable query fall through to "everything".
            q.isNotBlank() && spec.ftsQuery.isBlank() -> SearchTarget.NoMatch
            else -> SearchTarget.Run(spec)
        }
    }

    /**
     * Every match, filtered and sorted in the database and loaded a page at
     * a time. Room invalidates the source when saves change, so an open
     * search stays current after new saves, edits, deletes and OCR.
     */
    val results: Flow<PagingData<SaveItemEntity>> = combine(target, _sort) { t, sort -> t to sort }
        .flatMapLatest { (t, sort) ->
            if (t !is SearchTarget.Run) flowOf(PagingData.empty())
            else Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) {
                repo.pagedSearch(t.spec, sort.name)
            }.flow
        }
        .cachedIn(viewModelScope)

    private val resultCount: Flow<Int> = target.flatMapLatest { t ->
        if (t is SearchTarget.Run) repo.observeSearchCount(t.spec) else flowOf(0)
    }

    private val knownTags = repo.observeTopTags(20).map { list -> list.map { it.name } }

    private val knownSources = repo.observeSourceCounts().map { counts ->
        counts.mapNotNull { sc -> runCatching { SourceApp.valueOf(sc.source) }.getOrNull() }
    }

    val state: StateFlow<SearchState> = combine(
        _query, filters, resultCount, repo.observeCategories(), knownTags, _sort, knownSources
    ) { args ->
        // See HomeViewModel: combine(vararg) erases types through Array<Any?>
        // and each cast emits its own warning. Suppress per-line.
        val q = args[0] as String
        val f = args[1] as Filters
        val count = args[2] as Int
        @Suppress("UNCHECKED_CAST")
        val cats = args[3] as List<CategoryEntity>
        @Suppress("UNCHECKED_CAST")
        val tags = args[4] as List<String>
        val sort = args[5] as SearchSort
        @Suppress("UNCHECKED_CAST")
        val sources = args[6] as List<SourceApp>
        SearchState(
            query = q,
            sourceFilter = f.source,
            categoryFilter = f.category,
            typeFilter = f.type,
            tagFilter = f.tag,
            reminderFilter = f.reminders,
            resultCount = count,
            categories = cats,
            knownTags = tags,
            sources = sources,
            sort = sort
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState())

    fun onQuery(q: String) { _query.value = q }
    fun applyRouteFilters(query: String, source: String?) {
        _query.value = query
        _sourceFilter.value = source
    }
    fun toggleSource(name: String) {
        _sourceFilter.value = if (_sourceFilter.value == name) null else name
    }
    fun toggleCategory(id: String) {
        _categoryFilter.value = if (_categoryFilter.value == id) null else id
    }
    fun toggleType(t: ContentType) {
        val next = if (_typeFilter.value == t) null else t
        _typeFilter.value = next
        if (next != null && _query.value.isBlank()) _sort.value = SearchSort.NEWEST
    }
    fun toggleTag(t: String) {
        _tagFilter.value = if (_tagFilter.value.equals(t, ignoreCase = true)) null else t
    }
    fun toggleReminders() {
        _reminderFilter.value = !_reminderFilter.value
        if (_reminderFilter.value) _sort.value = SearchSort.REMINDER
    }
    fun clearFilters() {
        _sourceFilter.value = null
        _categoryFilter.value = null
        _typeFilter.value = null
        _tagFilter.value = null
        _reminderFilter.value = false
    }

    fun setSort(sort: SearchSort) {
        _sort.value = sort
    }

    // ── Quick actions (long-press) ───────────────────────────────────────
    fun toggleFavorite(item: SaveItemEntity) = viewModelScope.launch {
        repo.setFavorite(item.id, !item.isFavorite)
    }
    fun togglePinned(item: SaveItemEntity) = viewModelScope.launch {
        repo.setPinned(item.id, !item.isPinned)
    }
    fun toggleArchived(item: SaveItemEntity) = viewModelScope.launch {
        repo.setArchived(item.id, !item.isArchived)
        if (!item.isArchived && item.remindAt != null) {
            repo.setRemindAt(item.id, null)
            ReminderScheduler.cancel(appContext, item.id)
        }
    }
    fun setRemindAt(item: SaveItemEntity, at: Long?) = viewModelScope.launch {
        repo.setRemindAt(item.id, at)
        if (at != null) ReminderScheduler.schedule(appContext, item.id, at)
        else ReminderScheduler.cancel(appContext, item.id)
    }
    fun moveTo(item: SaveItemEntity, categoryId: String?) = viewModelScope.launch {
        repo.update(item.copy(categoryId = categoryId, updatedAt = System.currentTimeMillis()))
    }
    fun delete(item: SaveItemEntity) = viewModelScope.launch {
        ReminderScheduler.cancel(appContext, item.id)
        repo.delete(item.id)
    }

    suspend fun stageDelete(item: SaveItemEntity) {
        // is_pending_delete hides the row everywhere during the Undo
        // window so the user doesn't see the supposedly-deleted save
        // pop up in Archive.
        repo.setPendingDelete(item.id, true)
        if (item.remindAt != null) {
            repo.setRemindAt(item.id, null)
            ReminderScheduler.cancel(appContext, item.id)
        }
    }

    suspend fun undoStagedDelete(item: SaveItemEntity) {
        repo.setPendingDelete(item.id, false)
        if (item.remindAt != null && item.remindAt > System.currentTimeMillis()) {
            repo.setRemindAt(item.id, item.remindAt)
            ReminderScheduler.schedule(appContext, item.id, item.remindAt)
        }
    }

}
