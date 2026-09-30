package com.ghostgramlabs.pettibox.ui.screens.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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
    val results: List<SaveItemEntity> = emptyList(),
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

    private val candidates: StateFlow<List<SaveItemEntity>> = combine(
        _query
            .debounce(180)
            .distinctUntilChanged(),
        filters
    ) { q, f -> q to f }
        .flatMapLatest { (q, f) ->
            flow {
                val haveFilters = f.source != null || f.category != null ||
                    f.type != null || f.tag != null || f.reminders
                emit(
                    when {
                        q.isNotBlank() -> repo.search(q)
                        haveFilters -> repo.browseForSearch()
                        else -> emptyList()
                    }
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Tag aggregation hits the [item_tags] table directly — never the items.
     */
    private val knownTags = repo.observeTopTags(20).map { list -> list.map { it.name } }

    /**
     * When the user filters by tag, we resolve the tag → item ids via an
     * indexed JOIN once per change; the rest of filtering is then a cheap
     * post-filter on the (already capped) FTS or browse result set.
     */
    private val tagItemIdSet = _tagFilter.flatMapLatest { tag ->
        flow {
            emit(if (tag == null) null else repo.itemIdsForTag(tag).toSet())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val knownSources = repo.observeSourceCounts().map { counts ->
        counts.mapNotNull { sc -> runCatching { SourceApp.valueOf(sc.source) }.getOrNull() }
    }

    val state: StateFlow<SearchState> = combine(
        _query, filters, candidates, repo.observeCategories(), knownTags, tagItemIdSet, _sort, knownSources
    ) { args ->
        // See HomeViewModel: combine(vararg) erases types through Array<Any?>
        // and each cast emits its own warning. Suppress per-line.
        val q = args[0] as String
        val f = args[1] as Filters
        @Suppress("UNCHECKED_CAST")
        val candidates = args[2] as List<SaveItemEntity>
        @Suppress("UNCHECKED_CAST")
        val cats = args[3] as List<CategoryEntity>
        @Suppress("UNCHECKED_CAST")
        val tags = args[4] as List<String>
        @Suppress("UNCHECKED_CAST")
        val tagIds = args[5] as Set<Long>?
        val sort = args[6] as SearchSort
        @Suppress("UNCHECKED_CAST")
        val sources = args[7] as List<SourceApp>

        val filtered = candidates.filter { item ->
                (f.source == null || item.sourceApp == f.source) &&
                (f.category == null || item.categoryId == f.category) &&
                (f.type == null || item.contentType == f.type.name) &&
                (!f.reminders || (item.remindAt ?: 0L) > System.currentTimeMillis()) &&
                (tagIds == null || item.id in tagIds)
        }.let { list ->
            when (sort) {
                SearchSort.RELEVANT -> list
                SearchSort.NEWEST -> list.sortedByDescending { it.createdAt }
                SearchSort.OLDEST -> list.sortedBy { it.createdAt }
                SearchSort.UPDATED -> list.sortedByDescending { it.updatedAt }
                SearchSort.REMINDER -> list.sortedWith(
                    compareBy<SaveItemEntity> { it.remindAt ?: Long.MAX_VALUE }
                        .thenByDescending { it.createdAt }
                )
            }
        }
        SearchState(
            query = q,
            sourceFilter = f.source,
            categoryFilter = f.category,
            typeFilter = f.type,
            tagFilter = f.tag,
            reminderFilter = f.reminders,
            results = filtered,
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
