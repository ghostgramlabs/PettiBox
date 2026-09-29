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
import com.ghostgramlabs.pettibox.data.local.TagEntity
import com.ghostgramlabs.pettibox.data.preferences.ArticlePreferences
import com.ghostgramlabs.pettibox.data.preferences.OcrPreferences
import com.ghostgramlabs.pettibox.data.reminders.ReminderScheduler
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    @ApplicationContext private val appContext: Context,
    handle: SavedStateHandle
) : ViewModel() {

    private val itemId: Long = handle.get<Long>("id") ?: -1L

    /** Offline reading copy of this save's link, if one was made or attempted. */
    val article: StateFlow<ArticleCopySummary?> = articleRepository.observeSummary(itemId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val readerTextZoom: StateFlow<Int> = articlePreferences.readerTextZoom
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArticlePreferences.DEFAULT_TEXT_ZOOM)

    /** Save, retry, or update this link's offline copy. */
    fun requestArticleCopy() = viewModelScope.launch {
        val it = state.value.item ?: return@launch
        articleRepository.request(it.id, it.url)
    }

    /** The full copy for the reader; loaded on open, never kept in list/detail state. */
    suspend fun loadArticle(): ArticleCopyEntity? = articleRepository.load(itemId)

    fun setReaderTextZoom(zoom: Int) = viewModelScope.launch {
        articlePreferences.setReaderTextZoom(zoom)
    }

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
    suspend fun deletePermanently(item: SaveItemEntity) {
        ReminderScheduler.cancel(appContext, item.id)
        repo.delete(item.id)
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
