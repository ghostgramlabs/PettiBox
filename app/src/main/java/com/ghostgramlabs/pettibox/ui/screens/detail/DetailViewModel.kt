package com.ghostgramlabs.pettibox.ui.screens.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghostgramlabs.pettibox.data.article.ArticleRepository
import com.ghostgramlabs.pettibox.data.local.ArticleCopyEntity
import com.ghostgramlabs.pettibox.data.local.ArticleCopySummary
import com.ghostgramlabs.pettibox.data.local.AttachmentEntity
import com.ghostgramlabs.pettibox.data.local.CategoryEntity
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity
import com.ghostgramlabs.pettibox.data.local.SyncDao
import com.ghostgramlabs.pettibox.data.local.TagEntity
import com.ghostgramlabs.pettibox.data.lock.LockedCollections
import com.ghostgramlabs.pettibox.data.metadata.PageSnapshotWorker
import com.ghostgramlabs.pettibox.data.preferences.ArticlePreferences
import com.ghostgramlabs.pettibox.data.preferences.OcrPreferences
import com.ghostgramlabs.pettibox.data.preferences.RatingPreferences
import com.ghostgramlabs.pettibox.data.reminders.ReminderScheduler
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import com.ghostgramlabs.pettibox.data.sync.SyncCodec
import com.ghostgramlabs.pettibox.data.sync.SyncEngine
import com.ghostgramlabs.pettibox.data.sync.SyncRecord
import com.ghostgramlabs.pettibox.data.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailState(
    val isLoaded: Boolean = false,
    val item: SaveItemEntity? = null,
    val category: CategoryEntity? = null,
    val categories: List<CategoryEntity> = emptyList(),
    val attachments: List<AttachmentEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val ocrAutoScanEnabled: Boolean = true
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repo: SaveRepository,
    private val ocrPreferences: OcrPreferences,
    private val articleRepository: ArticleRepository,
    private val articlePreferences: ArticlePreferences,
    private val ratingPreferences: RatingPreferences,
    private val syncDao: SyncDao,
    private val syncEngine: SyncEngine,
    @ApplicationContext private val appContext: Context,
    handle: SavedStateHandle
) : ViewModel() {

    private val itemId: Long = handle.get<Long>("id") ?: -1L

    /**
     * Fields edited on two devices without either seeing the other: both
     * values are kept until the user picks one here. Empty unless sync is on.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val conflicts: StateFlow<List<SyncConflict>> = repo.observeById(itemId)
        .map { it?.syncId }
        .distinctUntilChanged()
        .flatMapLatest { syncId -> if (syncId == null) flowOf(null) else syncDao.observeMeta(SyncRecord.KIND_ITEM, syncId) }
        .map { row ->
            val r = row?.let { runCatching { SyncCodec.decodeString(it.recordJson) }.getOrNull() } ?: return@map emptyList()
            CONFLICT_FIELDS.mapNotNull { (field, label) ->
                val others = r.alternatives(field).mapNotNull { it.value as? String }
                if (others.isEmpty()) null else SyncConflict(field, label, r.value(field) as? String, others)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // A save waiting for a choice: sync now, so a choice already made on
        // another device arrives (and the question goes) before anyone answers.
        viewModelScope.launch {
            if (conflicts.first { true }.isEmpty()) conflicts.first { it.isNotEmpty() }
            SyncScheduler.now(appContext)
        }
    }

    /**
     * Still waiting for a choice on [field], after syncing first? A choice on
     * any one device settles it everywhere, so this one never asks again.
     */
    private suspend fun stillUndecided(field: String): Boolean {
        syncEngine.round()
        val syncId = state.value.item?.syncId ?: return false
        val row = syncDao.meta(SyncRecord.KIND_ITEM, syncId) ?: return false
        val r = runCatching { SyncCodec.decodeString(row.recordJson) }.getOrNull() ?: return false
        return r.alternatives(field).isNotEmpty()
    }

    /**
     * Picks the other version: an ordinary edit, which every device then
     * follows. Returns false (nothing changed) if another device chose first.
     */
    suspend fun useVersion(conflict: SyncConflict, value: String): Boolean {
        if (!stillUndecided(conflict.field)) return false
        val it = repo.getById(itemId) ?: return false
        val now = System.currentTimeMillis()
        repo.update(
            when (conflict.field) {
                SyncRecord.F_TITLE -> it.copy(title = value, updatedAt = now)
                SyncRecord.F_URL -> it.copy(url = value, updatedAt = now)
                else -> it.copy(notes = value, updatedAt = now)
            }
        )
        return true
    }

    /** Keeps what's shown and drops the other version everywhere; false if another device chose first. */
    suspend fun keepCurrent(conflict: SyncConflict): Boolean {
        if (!stillUndecided(conflict.field)) return false
        val syncId = state.value.item?.syncId ?: return false
        syncEngine.keepShown(syncId, conflict.field)
        SyncScheduler.soon(appContext)
        return true
    }

    /** Offline reading copy of this save's link, if one was made or attempted. */
    val article: StateFlow<ArticleCopySummary?> = articleRepository.observeSummary(itemId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val readerTextZoom: StateFlow<Int> = articlePreferences.readerTextZoom
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArticlePreferences.DEFAULT_TEXT_ZOOM)

    /** Replaces the card picture with a fresh screenshot of the page. */
    fun requestPageScreenshot() {
        if (state.value.item?.url.isNullOrBlank()) return
        PageSnapshotWorker.enqueue(appContext, itemId, force = true)
    }

    /** Save, retry, or update this link's offline copy. */
    fun requestArticleCopy() = viewModelScope.launch {
        val it = state.value.item ?: return@launch
        articleRepository.request(it.id, it.url)
    }

    /** The full copy for the reader; loaded on open, never kept in list/detail state. */
    suspend fun loadArticle(): ArticleCopyEntity? = articleRepository.load(itemId)

    /**
     * Records a finished offline read; true when it's the moment to show
     * Play's review sheet (the third article someone has read offline).
     */
    suspend fun recordOfflineRead(): Boolean =
        articlePreferences.recordRead() >= 3 &&
            ratingPreferences.claimHappyMoment(RatingPreferences.MOMENT_OFFLINE_READS)

    fun setReaderTextZoom(zoom: Int) = viewModelScope.launch {
        articlePreferences.setReaderTextZoom(zoom)
    }

    /**
     * True while the save sits in a locked collection that hasn't been
     * unlocked; null until known, so neither the save nor the lock flashes.
     * Reminder taps and other deep links land here without passing Browse.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lockedOut: StateFlow<Boolean?> = repo.observeById(itemId)
        .map { it?.categoryId }
        .distinctUntilChanged()
        .flatMapLatest { repo.observeCategoryLocked(it) }
        .combine(LockedCollections.unlocked) { locked, unlocked -> locked && !unlocked }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<DetailState> = combine(
        repo.observeById(itemId),
        repo.observeCategories(),
        repo.observeAttachments(itemId),
        repo.observeTagsForItem(itemId),
        ocrPreferences.autoScan
    ) { item, cats, atts, tags, ocrEnabled ->
        DetailState(
            isLoaded = true,
            item = item,
            category = item?.categoryId?.let { cid -> cats.firstOrNull { it.id == cid } },
            categories = cats,
            attachments = atts,
            tags = tags,
            ocrAutoScanEnabled = ocrEnabled
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState())

    init {
        viewModelScope.launch { if (itemId > 0) repo.touchOpened(itemId) }
    }

    fun toggleFavorite() = viewModelScope.launch {
        val it = state.value.item ?: return@launch
        repo.setFavorite(it.id, !it.isFavorite)
    }
    fun togglePinned() = viewModelScope.launch {
        val it = state.value.item ?: return@launch
        repo.setPinned(it.id, !it.isPinned)
    }
    fun delete() = viewModelScope.launch {
        val it = state.value.item ?: return@launch
        ReminderScheduler.cancel(appContext, it.id)
        repo.delete(it.id)
    }
    suspend fun stageDelete(): SaveItemEntity? {
        val it = state.value.item ?: return null
        // is_pending_delete instead of is_archived: the row disappears
        // from every listing during the Undo window, including Archive,
        // and the user's original is_archived state is preserved for
        // Undo. observeById is unfiltered, so the Detail screen the
        // user is currently staring at keeps rendering the item.
        repo.setPendingDelete(it.id, true)
        if (it.remindAt != null) {
            repo.setRemindAt(it.id, null)
            ReminderScheduler.cancel(appContext, it.id)
        }
        return it
    }
    suspend fun undoStagedDelete(item: SaveItemEntity) {
        repo.setPendingDelete(item.id, false)
        if (item.remindAt != null && item.remindAt > System.currentTimeMillis()) {
            repo.setRemindAt(item.id, item.remindAt)
            ReminderScheduler.schedule(appContext, item.id, item.remindAt)
        }
    }
    fun setArchived(archived: Boolean) = viewModelScope.launch {
        val it = state.value.item ?: return@launch
        repo.setArchived(it.id, archived)
        // Archiving a reminded item cancels its pending notification so
        // the user doesn't get nudged about something they marked done.
        if (archived && it.remindAt != null) {
            repo.setRemindAt(it.id, null)
            ReminderScheduler.cancel(appContext, it.id)
        }
    }
    fun setRemindAt(at: Long?) = viewModelScope.launch {
        val it = state.value.item ?: return@launch
        repo.setRemindAt(it.id, at)
        if (at != null) {
            ReminderScheduler.schedule(appContext, it.id, at)
        } else {
            ReminderScheduler.cancel(appContext, it.id)
        }
    }
    fun setCategory(id: String?) = viewModelScope.launch {
        val it = state.value.item ?: return@launch
        repo.update(it.copy(categoryId = id, updatedAt = System.currentTimeMillis()))
    }
    fun updateNotes(notes: String) = viewModelScope.launch {
        val it = state.value.item ?: return@launch
        repo.update(it.copy(notes = notes.ifBlank { null }, updatedAt = System.currentTimeMillis()))
    }
    fun updateTitle(title: String) = viewModelScope.launch {
        val it = state.value.item ?: return@launch
        val clean = title.trim().ifBlank { "Untitled" }
        if (clean == it.title) return@launch
        repo.update(it.copy(title = clean, updatedAt = System.currentTimeMillis()))
    }

    fun addTag(tag: String) = viewModelScope.launch {
        val it = state.value.item ?: return@launch
        val clean = tag.trim().removePrefix("#")
        if (clean.isBlank() || clean.length > 24) return@launch
        repo.addTag(it.id, clean)
    }

    fun removeTag(tag: String) = viewModelScope.launch {
        val it = state.value.item ?: return@launch
        repo.removeTag(it.id, tag)
    }

    fun deleteAttachment(id: Long) = viewModelScope.launch {
        repo.deleteAttachment(id)
    }
}

/** A field edited on two devices: what's shown now, and the other value(s) kept. */
data class SyncConflict(val field: String, val label: String, val shown: String?, val others: List<String>)

private val CONFLICT_FIELDS = listOf(
    SyncRecord.F_TITLE to "Title",
    SyncRecord.F_NOTES to "Note",
    SyncRecord.F_URL to "Link"
)
