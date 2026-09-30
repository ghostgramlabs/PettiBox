package com.ghostgramlabs.pettibox.ui.screens.categories

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ghostgramlabs.pettibox.data.local.CategoryEntity
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity
import com.ghostgramlabs.pettibox.data.local.TagWithCount
import com.ghostgramlabs.pettibox.data.reminders.ReminderScheduler
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A first-class place to land on inside Browse. Categories are the obvious
 * one, but Favorites, Archive, and Tag-based filters are equally valid
 * "saved-from-anywhere" destinations and were previously unreachable from
 * Browse — Favorites only lived on Home, archived items were only visible
 * via the per-collection toggle (so orphan archived items were invisible),
 * and tags had no entry point at all.
 *
 * Wire-format on the cid nav arg uses prefixed sentinels so [String] route
 * args still work without changing the NavGraph. Category ids are
 * user-controlled but never collide with the `__` prefix.
 */
sealed interface BrowseDestination {
    data object Grid : BrowseDestination
    data class Category(val id: String) : BrowseDestination
    data object Favorites : BrowseDestination
    data object Archive : BrowseDestination
    data object Reminders : BrowseDestination
    data object TagList : BrowseDestination
    data class Tag(val name: String) : BrowseDestination
    /** Saved but never opened — the read-later queue. */
    data object Unread : BrowseDestination
    /** Deleted saves, kept 30 days before they're removed for good. */
    data object Trash : BrowseDestination

    companion object {
        const val CID_FAVORITES = "__fav"
        const val CID_ARCHIVE = "__arc"
        const val CID_REMINDERS = "__rem"
        const val CID_TAG_LIST = "__tags"
        const val CID_TAG_PREFIX = "__tag:"
        const val CID_UNREAD = "__unread"
        const val CID_TRASH = "__trash"

        fun fromCid(cid: String?): BrowseDestination = when {
            cid.isNullOrBlank() -> Grid
            cid == CID_FAVORITES -> Favorites
            cid == CID_ARCHIVE -> Archive
            cid == CID_REMINDERS -> Reminders
            cid == CID_TAG_LIST -> TagList
            cid == CID_UNREAD -> Unread
            cid == CID_TRASH -> Trash
            cid.startsWith(CID_TAG_PREFIX) -> Tag(cid.removePrefix(CID_TAG_PREFIX))
            else -> Category(cid)
        }

        fun toCid(dest: BrowseDestination): String = when (dest) {
            Grid -> ""
            Favorites -> CID_FAVORITES
            Archive -> CID_ARCHIVE
            Reminders -> CID_REMINDERS
            TagList -> CID_TAG_LIST
            Unread -> CID_UNREAD
            Trash -> CID_TRASH
            is Category -> dest.id
            is Tag -> CID_TAG_PREFIX + dest.name
        }
    }
}

enum class BrowseSort(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    UPDATED("Recently edited"),
    REMINDER("Reminder time")
}

data class CategoriesState(
    val destination: BrowseDestination = BrowseDestination.Grid,
    val categories: List<CategoryEntity> = emptyList(),
    val countsByCategory: Map<String, Int> = emptyMap(),
    val favoriteCount: Int = 0,
    val archivedCount: Int = 0,
    val reminderCount: Int = 0,
    val unreadCount: Int = 0,
    val trashCount: Int = 0,
    val topTags: List<TagWithCount> = emptyList(),
    /** Toggle inside a Category drill — show archived items of that one collection. */
    val showArchived: Boolean = false,
    val sort: BrowseSort = BrowseSort.NEWEST
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val repo: SaveRepository,
    @ApplicationContext private val appContext: Context,
    handle: SavedStateHandle
) : ViewModel() {

    private val _destination = MutableStateFlow(
        BrowseDestination.fromCid(handle.get<String>("cid"))
    )
    private val _showArchived = MutableStateFlow(false)
    private val _sort = MutableStateFlow(BrowseSort.NEWEST)

    val state: StateFlow<CategoriesState> = combine(
        _destination,
        repo.observeCategories(),
        repo.observeCategoryCounts(),
        repo.observeFavoriteTotal(),
        repo.observeArchivedTotal(),
        repo.observeUpcomingReminderTotal(),
        repo.observeTopTags(limit = 100),
        _showArchived,
        _sort,
        repo.observeUnreadTotal(),
        repo.observeTrashTotal()
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val cats = args[1] as List<CategoryEntity>
        @Suppress("UNCHECKED_CAST")
        val counts = args[2] as List<com.ghostgramlabs.pettibox.data.local.CategoryCount>
        @Suppress("UNCHECKED_CAST")
        val topTags = args[6] as List<TagWithCount>
        CategoriesState(
            destination = args[0] as BrowseDestination,
            categories = cats,
            countsByCategory = counts.associate { it.categoryId to it.count },
            favoriteCount = args[3] as Int,
            archivedCount = args[4] as Int,
            reminderCount = args[5] as Int,
            topTags = topTags,
            showArchived = args[7] as Boolean,
            sort = args[8] as BrowseSort,
            unreadCount = args[9] as Int,
            trashCount = args[10] as Int
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesState())

    /**
     * Paged stream of items for the current drill destination. The Tag
     * list destination renders the tag *list* directly from state instead
     * of paging items — empty PagingData for that branch is fine.
     */
    val drillItems: Flow<PagingData<SaveItemEntity>> =
        combine(_destination, _showArchived, _sort) { d, archived, sort ->
            Triple(d, archived, sort)
        }.flatMapLatest { (dest, archived, sort) ->
                val source: () -> androidx.paging.PagingSource<Int, SaveItemEntity> = when (dest) {
                    BrowseDestination.Grid -> return@flatMapLatest flowOf(PagingData.empty())
                    BrowseDestination.TagList -> return@flatMapLatest flowOf(PagingData.empty())
                    BrowseDestination.Favorites -> ({ repo.pagedFavorites(sort.name) })
                    BrowseDestination.Archive -> ({ repo.pagedArchived(sort.name) })
                    BrowseDestination.Reminders -> ({ repo.pagedUpcomingReminders(sort.name) })
                    BrowseDestination.Unread -> ({ repo.pagedUnread(sort.name) })
                    BrowseDestination.Trash -> ({ repo.pagedTrash() })
                    is BrowseDestination.Category ->
                        ({ repo.pagedByCategory(dest.id, includeArchived = archived, sort = sort.name) })
                    is BrowseDestination.Tag -> ({ repo.pagedByTag(dest.name, sort.name) })
                }
                Pager(
                    config = PagingConfig(
                        pageSize = 30,
                        prefetchDistance = 10,
                        initialLoadSize = 60,
                        enablePlaceholders = false
                    ),
                    pagingSourceFactory = source
                ).flow
            }.cachedIn(viewModelScope)

    fun navigate(dest: BrowseDestination) {
        _destination.value = dest
        // Reset the per-collection archive toggle whenever we move; it
        // never makes sense outside a single category drill.
        _showArchived.value = false
        _sort.value = if (dest == BrowseDestination.Reminders) BrowseSort.REMINDER else BrowseSort.NEWEST
    }

    /** Convenience for backwards-compatible call sites. */
    fun select(categoryId: String?) {
        navigate(if (categoryId.isNullOrBlank()) BrowseDestination.Grid else BrowseDestination.Category(categoryId))
    }

    fun toggleArchivedView() {
        _showArchived.value = !_showArchived.value
    }

    fun setSort(sort: BrowseSort) {
        _sort.value = sort
    }

    // Starter and user-created collections are equally editable/deletable
    // (saves survive a delete — their category_id just becomes null).
    // Deleted starters stay gone because seeding runs once per install.
    fun deleteSelectedCategory() = viewModelScope.launch {
        val dest = _destination.value as? BrowseDestination.Category ?: return@launch
        repo.deleteCategory(dest.id)
        _destination.value = BrowseDestination.Grid
    }

    fun updateSelectedCategory(name: String, emoji: String, colorHex: Long) = viewModelScope.launch {
        val dest = _destination.value as? BrowseDestination.Category ?: return@launch
        val category = state.value.categories.firstOrNull { it.id == dest.id } ?: return@launch
        if (name.isBlank()) return@launch
        repo.upsertCategory(
            category.copy(
                name = name.trim().take(28),
                emoji = emoji,
                colorHex = colorHex
            )
        )
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
        // is_pending_delete hides the row from every listing during the
        // Undo window; is_archived is left alone so Undo lands the row
        // exactly where the user had it.
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

    suspend fun deletePermanently(item: SaveItemEntity) {
        ReminderScheduler.cancel(appContext, item.id)
        repo.delete(item.id)
    }

    fun archiveItems(items: List<SaveItemEntity>) = viewModelScope.launch {
        items.forEach { item ->
            repo.setArchived(item.id, true)
            if (item.remindAt != null) {
                repo.setRemindAt(item.id, null)
                ReminderScheduler.cancel(appContext, item.id)
            }
        }
    }

    fun deleteItems(items: List<SaveItemEntity>) = viewModelScope.launch {
        items.forEach { item ->
            ReminderScheduler.cancel(appContext, item.id)
            repo.delete(item.id)
        }
    }

    suspend fun stageDeleteItems(items: List<SaveItemEntity>) {
        items.forEach { stageDelete(it) }
    }

    suspend fun undoStagedDeleteItems(items: List<SaveItemEntity>) {
        items.forEach { undoStagedDelete(it) }
    }

    suspend fun deleteItemsPermanently(items: List<SaveItemEntity>) {
        items.forEach { deletePermanently(it) }
    }

    /** Back out of Recently deleted, to wherever the save was (archived or not). */
    fun restore(items: List<SaveItemEntity>) = viewModelScope.launch {
        items.forEach { repo.setPendingDelete(it.id, false) }
    }

    suspend fun emptyTrash(): Int = repo.emptyTrash()
}
