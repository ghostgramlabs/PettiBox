package com.ghostgramlabs.pettibox.ui.screens.save

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import android.webkit.MimeTypeMap
import com.ghostgramlabs.pettibox.data.local.AttachmentEntity
import com.ghostgramlabs.pettibox.data.local.CategoryEntity
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity
import com.ghostgramlabs.pettibox.data.article.ArticleRepository
import com.ghostgramlabs.pettibox.data.metadata.MetadataFetcher
import com.ghostgramlabs.pettibox.data.metadata.ThumbnailWorker
import com.ghostgramlabs.pettibox.data.ocr.OcrWorker
import com.ghostgramlabs.pettibox.data.ocr.PdfTextWorker
import com.ghostgramlabs.pettibox.data.preferences.OcrPreferences
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import com.ghostgramlabs.pettibox.data.util.AttachmentStore
import com.ghostgramlabs.pettibox.data.util.TextUtils
import com.ghostgramlabs.pettibox.domain.model.ContentType
import com.ghostgramlabs.pettibox.domain.model.SourceApp
import com.ghostgramlabs.pettibox.ui.components.NewCollection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import javax.inject.Inject

enum class SaveMode { NEW, PICK_EXISTING }

/** Placeholder title for a save with nothing better to be called (a blank quick note). */
private const val QUICK_SAVE_TITLE = "Quick save"

data class SaveSheetState(
    val mode: SaveMode = SaveMode.NEW,
    val title: String = "",
    val previewImage: String? = null,
    val description: String? = null,
    /** Channel / site / description for search (see LinkMetadata.searchableDetails). */
    val linkDetails: String? = null,
    val notes: String = "",
    val tagsInput: String = "",
    val sourceApp: SourceApp = SourceApp.UNKNOWN,
    val contentType: ContentType = ContentType.NOTE,
    val url: String? = null,
    val localUri: String? = null,
    val attachments: List<String> = emptyList(),
    /** Type of each entry in [attachments], read from the file itself. */
    val attachmentKinds: List<ContentType> = emptyList(),
    val isFavorite: Boolean = false,
    // Reminder picked at save-time. Null means no reminder. The picker
    // sheet writes this, and save()/saveToCategory() persists it plus
    // schedules the worker.
    val remindAt: Long? = null,
    val selectedCategory: String? = null,
    // Category id we'd suggest based on URL/title heuristics — null
    // when we have no good guess. The chip with this id renders with
    // a primary-colored border so the user notices, but is NOT
    // pre-selected (auto-saving to the wrong place is worse than no
    // suggestion at all).
    val suggestedCategory: String? = null,
    val categories: List<CategoryEntity> = emptyList(),
    // Collection ids ordered most-recently-used first. The chip row floats
    // these to the front (after the suggested one), so a user's go-to
    // collections are a tap away instead of buried by sort order.
    val recentCategoryIds: List<String> = emptyList(),
    val recentItems: List<SaveItemEntity> = emptyList(),
    // An existing live save with the same URL, if the incoming share is a
    // link we've seen before. Drives the "you already saved this" banner;
    // null means no duplicate (or the user chose "Save anyway").
    val duplicateOf: SaveItemEntity? = null,
    val isResolving: Boolean = false,
    val isSaved: Boolean = false
)

@HiltViewModel
class SaveSheetViewModel @Inject constructor(
    private val repo: SaveRepository,
    private val metadata: MetadataFetcher,
    private val attachmentStore: AttachmentStore,
    private val ocrPreferences: OcrPreferences,
    private val articleRepository: ArticleRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow(SaveSheetState())
    private val ingestGeneration = AtomicLong(0L)

    val state: StateFlow<SaveSheetState> = combine(
        _state, repo.observeCategories(), repo.observeRecent(30), repo.observeRecentCategoryIds()
    ) { s, cats, recent, recentCatIds ->
        s.copy(categories = cats, recentItems = recent, recentCategoryIds = recentCatIds)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), _state.value)

    fun ingest(share: IncomingShare) {
        val generation = ingestGeneration.incrementAndGet()
        val firstUrl = share.urls.firstOrNull() ?: TextUtils.extractFirstUrl(share.text)
        // Every shared file, typed by what it actually is. The share's own
        // MIME type covers the whole intent and can be vague ("*/*") or plain
        // wrong, which once saved an image as a PDF; a multi-file share with a
        // generic type also used to keep only its first file.
        val streams = share.imageUris + share.fileUris
        val kinds = streams.map { kindOf(it, share.mimeType) }
        val keepAll = share.imageUris.isNotEmpty() || streams.size > 1
        val allImages = if (keepAll) streams.map { it.toString() } else emptyList()

        val type = when {
            firstUrl != null -> ContentType.LINK
            streams.isNotEmpty() -> kinds.first()
            !share.text.isNullOrBlank() -> ContentType.TEXT
            else -> ContentType.NOTE
        }
        // The link's own site wins (a YouTube link is "YouTube" whichever app
        // shared it); generic links fall back to the app that shared them.
        val source = SourceApp.fromUrl(firstUrl).let { fromUrl ->
            if (fromUrl == SourceApp.WEB || fromUrl == SourceApp.UNKNOWN) {
                SourceApp.fromPackage(share.senderPackage) ?: fromUrl
            } else {
                fromUrl
            }
        }

        val fallbackTitle = when {
            streams.size > 1 && kinds.any { it != type } -> "${streams.size} files"
            type == ContentType.IMAGE -> if (streams.size > 1) "${streams.size} images" else "Saved image"
            type == ContentType.PDF -> if (streams.size > 1) "${streams.size} PDFs" else "Saved PDF"
            type == ContentType.FILE -> "Saved file"
            else -> QUICK_SAVE_TITLE
        }
        val initialTitle = TextUtils.smartTitle(share.text, fallback = TextUtils.hostOf(firstUrl) ?: fallbackTitle)

        // Build a fresh state so a reused ViewModel (e.g. FAB → save → FAB
        // again on Home) doesn't carry over isSaved, notes, tags, etc.
        _state.value = SaveSheetState(
            title = initialTitle,
            url = firstUrl,
            localUri = streams.firstOrNull()?.toString(),
            attachments = allImages,
            attachmentKinds = if (keepAll) kinds else emptyList(),
            sourceApp = source,
            contentType = type,
            isResolving = firstUrl != null,
            suggestedCategory = suggestCategoryId(firstUrl, source, initialTitle)
        )

        if (firstUrl != null) {
            viewModelScope.launch {
                val cats = loadCategoriesOnce()
                // Surface a "you already saved this" nudge fast (cheap indexed
                // lookup), before the slower metadata fetch resolves.
                val dup = runCatching { repo.findByUrl(firstUrl) }.getOrNull()
                if (generation != ingestGeneration.get()) return@launch
                if (dup != null) _state.value = _state.value.copy(duplicateOf = dup)
                val meta = metadata.fetch(firstUrl)
                if (generation != ingestGeneration.get()) return@launch
                val current = _state.value
                val richerTitle = meta?.title?.takeIf { it.isNotBlank() }
                val resolvedTitle =
                    if (current.title == initialTitle && richerTitle != null) richerTitle else current.title
                _state.value = current.copy(
                    title = resolvedTitle,
                    previewImage = meta?.imageUrl,
                    description = meta?.description,
                    linkDetails = meta?.searchableDetails(),
                    isResolving = false,
                    // Re-run suggestion with the metadata-resolved title (often
                    // more descriptive than the raw share text) so a YouTube URL
                    // whose share text was just the link still suggests "Music"
                    // once we know the page title. A user collection named in the
                    // content wins; otherwise the keyword heuristic. Don't move
                    // the border out from under a user who's already picked.
                    suggestedCategory = if (current.selectedCategory == null)
                        bestSuggestion(firstUrl, source, resolvedTitle, current.suggestedCategory, cats)
                    else current.suggestedCategory
                )
            }
        } else {
            // No URL to fetch, but once collections load we can still suggest a
            // user collection named in the shared text/title.
            viewModelScope.launch {
                val cats = loadCategoriesOnce()
                if (generation != ingestGeneration.get()) return@launch
                val current = _state.value
                if (current.selectedCategory != null) return@launch
                _state.value = current.copy(
                    suggestedCategory =
                        bestSuggestion(null, source, current.title, current.suggestedCategory, cats)
                )
            }
        }
    }

    private suspend fun loadCategoriesOnce(): List<CategoryEntity> =
        runCatching { repo.observeCategories().first() }.getOrDefault(emptyList())

    /**
     * What a shared file really is: the providing app's MIME type for this
     * URI, then its file extension, and only then the share's overall type.
     * Generic types (match-anything, octet-stream) don't count as an answer.
     */
    private fun kindOf(uri: Uri, shareMime: String?): ContentType {
        fun String?.useful() = this?.takeUnless { it.isBlank() || it == "*/*" || it == "application/octet-stream" }
        val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull().useful()
            ?: MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                ?.takeIf { it.isNotBlank() }
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
            ?: sniffMime(uri)
            ?: shareMime.useful()
        return mime?.let { ContentType.fromMime(it) } ?: ContentType.FILE
    }

    /** Recognises PDFs and common image formats by their first bytes. */
    private fun sniffMime(uri: Uri): String? = runCatching {
        val head = ByteArray(12)
        val read = appContext.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: return null
        fun at(offset: Int, sig: String) =
            read >= offset + sig.length && sig.indices.all { head[offset + it] == sig[it].code.toByte() }
        when {
            at(0, "%PDF") -> "application/pdf"
            read >= 4 && head[0] == 0x89.toByte() && at(1, "PNG") -> "image/png"
            read >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> "image/jpeg"
            at(0, "GIF8") -> "image/gif"
            at(0, "RIFF") && at(8, "WEBP") -> "image/webp"
            at(4, "ftypheic") || at(4, "ftypheix") || at(4, "ftypmif1") -> "image/heic"
            else -> null
        }
    }.getOrNull()

    /**
     * The single chip we border as a hint. A user's own collection named in the
     * content beats everything (most personal signal); otherwise we keep the
     * first keyword-heuristic guess we had, or compute one. Never pre-selects —
     * a wrong border costs nothing, a wrong auto-save would cost trust.
     */
    private fun bestSuggestion(
        url: String?,
        source: SourceApp,
        title: String?,
        existing: String?,
        categories: List<CategoryEntity>
    ): String? {
        val haystack = listOfNotNull(url, title).joinToString(" ").lowercase()
        matchUserCollection(haystack, categories)?.let { return it }
        return existing ?: suggestCategoryId(url, source, title)
    }

    /**
     * Suggests a user-created collection whose name (as a whole word) or emoji
     * appears in the shared content. Seeded collections are left to
     * [suggestCategoryId]'s richer keyword rules. Longest name wins so
     * "Work trips" beats "Work"; names under 3 chars are too noisy to match on.
     */
    private fun matchUserCollection(haystack: String, categories: List<CategoryEntity>): String? {
        if (haystack.isBlank()) return null
        return categories
            .filter { it.userCreated && it.name.length >= 3 }
            .sortedByDescending { it.name.length }
            .firstOrNull { c ->
                val name = c.name.lowercase()
                Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(haystack) ||
                    (c.emoji.isNotBlank() && c.emoji in haystack)
            }
            ?.id
    }

    /**
     * Best-effort category suggestion based on the URL host, source app,
     * and title text. Only returns one of the seeded default category
     * ids — user-created collections aren't auto-suggested because they
     * have no semantic mapping. Returns null when the signal is too weak,
     * so the user gets a neutral chip row instead of a wrong nudge.
     */
    private fun suggestCategoryId(url: String?, source: SourceApp, title: String?): String? {
        val haystack = listOfNotNull(url, title).joinToString(" ").lowercase()
        if (haystack.isBlank()) return null
        return suggestCategoryIdFromText(haystack, source) ?: when {
            // Music / video — these dominate share traffic for many users
            Regex("youtube\\.com|youtu\\.be|soundcloud|spotify\\.com|music\\.apple|bandcamp")
                .containsMatchIn(haystack) -> "music"
            // Recipes — strong signal from URL or "recipe"/"ingredients" in title
            Regex("allrecipes|foodnetwork|food52|epicurious|seriouseats|smittenkitchen|nytimes\\.com/recipes|cooking\\.nytimes|bonappetit\\.com")
                .containsMatchIn(haystack) ||
                "recipe" in haystack ||
                "ingredients" in haystack -> "recipes"
            // Travel
            Regex("airbnb|booking\\.com|kayak|expedia|tripadvisor|hotels\\.com|skyscanner|lonelyplanet")
                .containsMatchIn(haystack) -> "travel"
            // Long-form reads — major article platforms + news
            Regex("medium\\.com|substack\\.com|longreads|nytimes\\.com|theguardian\\.com|wired\\.com|theatlantic|newyorker\\.com|technologyreview")
                .containsMatchIn(haystack) -> "read_later"
            // Fitness
            Regex("strava\\.com|peloton|fitbod|myfitnesspal").containsMatchIn(haystack) ||
                "workout" in haystack || "fitness " in haystack -> "fitness"
            // Finance
            Regex("bloomberg|investing\\.com|marketwatch|finance\\.yahoo|wsj\\.com")
                .containsMatchIn(haystack) -> "finance"
            else -> null
        }
    }

    private fun suggestCategoryIdFromText(haystack: String, source: SourceApp): String? =
        when {
            source == SourceApp.MAPS ||
                haystack.hasAny("restaurant", "cafe", "coffee shop", "menu", "brunch", "dinner spot", "lunch spot") ||
                haystack.matchesAny("yelp|zomato|opentable|resy|doordash|ubereats|swiggy|maps\\.app\\.goo\\.gl|google\\.com/maps") -> "food_spots"
            source == SourceApp.SPOTIFY ||
                haystack.matchesAny("spotify\\.com|soundcloud|music\\.apple|bandcamp|song|album|playlist|lyrics") -> "music"
            source == SourceApp.YOUTUBE ||
                haystack.matchesAny("youtube\\.com|youtu\\.be|netflix|primevideo|hotstar|hulu|disneyplus|imdb|letterboxd|trailer|movie|series|episode|watch later") -> "watch"
            haystack.matchesAny("goodreads|kindle|audible|bookshop\\.org|barnesandnoble|storytel") ||
                haystack.hasAny("book", "novel", "author", "reading list") -> "books"
            haystack.matchesAny("allrecipes|foodnetwork|food52|epicurious|seriouseats|smittenkitchen|nytimes\\.com/recipes|cooking\\.nytimes|bonappetit\\.com|tasty\\.co") ||
                haystack.hasAny("recipe", "ingredients", "cook time", "bake", "meal prep") -> "recipes"
            haystack.matchesAny("airbnb|booking\\.com|kayak|expedia|tripadvisor|hotels\\.com|skyscanner|lonelyplanet|makemytrip|cleartrip") ||
                haystack.hasAny("itinerary", "flight", "hotel", "visa", "things to do in") -> "travel"
            source == SourceApp.AMAZON ||
                haystack.matchesAny("amazon\\.|flipkart|etsy|ebay|walmart|target\\.com|bestbuy|myntra|ajio") ||
                haystack.hasAny("buy", "cart", "wishlist", "price drop", "coupon") -> "shopping"
            haystack.matchesAny("github\\.com|stackoverflow|developer\\.android|dev\\.to|npmjs|android\\.com|kotlinlang|jetbrains") ||
                haystack.hasAny("api", "sdk", "github", "debug", "programming", "kotlin", "android development") -> "tech"
            haystack.matchesAny("coursera|udemy|edx|skillshare|khanacademy|duolingo|masterclass") ||
                haystack.hasAny("course", "tutorial", "lesson", "learn ", "study", "certification") -> "learning"
            haystack.hasAny("gift", "birthday", "anniversary", "present idea", "christmas gift", "wishlist") -> "gifts"
            haystack.matchesAny("headspace|calm\\.com|strava\\.com|peloton|fitbod|myfitnesspal") ||
                haystack.hasAny("meditation", "yoga", "sleep", "wellness", "workout", "fitness ", "health") -> "health"
            haystack.matchesAny("pinterest\\.|behance|dribbble|figma\\.com") ||
                haystack.hasAny("inspiration", "moodboard", "idea", "ideas", "design") -> "ideas"
            haystack.matchesAny("bloomberg|investing\\.com|marketwatch|finance\\.yahoo|wsj\\.com|moneycontrol|zerodha|groww") ||
                haystack.hasAny("stock", "mutual fund", "budget", "invoice", "tax", "investment") -> "finance"
            haystack.matchesAny("vogue|gq\\.com|hm\\.com|zara\\.com|uniqlo|nike\\.com|adidas") ||
                haystack.hasAny("outfit", "style", "fashion", "shoes", "dress") -> "style"
            haystack.matchesAny("medium\\.com|substack\\.com|longreads|nytimes\\.com|theguardian\\.com|wired\\.com|theatlantic|newyorker\\.com|technologyreview") ||
                haystack.hasAny("article", "newsletter", "essay", "blog post") -> "read_later"
            else -> null
        }

    private fun String.matchesAny(pattern: String): Boolean =
        Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(this)

    private fun String.hasAny(vararg needles: String): Boolean =
        needles.any { it in this }

    fun setMode(m: SaveMode) = update { it.copy(mode = m) }
    fun setTitle(t: String) = update { it.copy(title = t) }
    fun setNotes(n: String) = update { it.copy(notes = n) }
    fun setTags(t: String) = update { it.copy(tagsInput = t) }
    fun toggleFavorite() = update { it.copy(isFavorite = !it.isFavorite) }
    fun selectCategory(id: String?) = update {
        it.copy(selectedCategory = if (it.selectedCategory == id) null else id)
    }

    fun saveToCategory(id: String) {
        selectCategory(id)
    }

    /**
     * Sets the destination collection without toggling. Used by the search
     * results list, where a second tap on the same row should keep the pick
     * (it shows a check), not silently clear it — clearing was letting saves
     * commit with no collection. To deselect, the user clears the search and
     * taps the highlighted chip in the default row.
     */
    fun pickCategory(id: String) = update { it.copy(selectedCategory = id) }

    fun createCollection(nc: NewCollection) = viewModelScope.launch {
        val id = "user_" + UUID.randomUUID().toString().take(8)
        val maxOrder = (_state.value.categories.maxOfOrNull { it.sortOrder } ?: 0) + 1
        repo.upsertCategory(
            CategoryEntity(
                id = id,
                name = nc.name,
                emoji = nc.emoji,
                colorHex = nc.colorHex,
                sortOrder = maxOrder,
                userCreated = true
            )
        )
        _state.value = _state.value.copy(selectedCategory = id)
    }

    private fun update(block: (SaveSheetState) -> SaveSheetState) {
        _state.value = block(_state.value)
    }

    fun setRemindAt(at: Long?) = update { it.copy(remindAt = at) }

    /** "Save anyway" — dismiss the duplicate nudge and let the normal save proceed. */
    fun dismissDuplicate() = update { it.copy(duplicateOf = null) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        if (s.title.isBlank() && s.url.isNullOrBlank() && s.localUri.isNullOrBlank()) return@launch

        // Copy any foreign content URIs into our own filesDir so they survive
        // permission revocation and process death. http(s) URLs and existing
        // file:// URIs are passed through unchanged.
        val ownLocalUri = s.localUri?.let { ingestIfForeign(it) }
        val ownAttachments = s.attachments.map { ingestIfForeign(it) ?: it }

        // A quick note left on the placeholder title is named after its
        // first line, so the shelf shows "Call the dentist", not "Quick save".
        val title = if (s.contentType == ContentType.NOTE && s.title == QUICK_SAVE_TITLE && s.notes.isNotBlank()) {
            TextUtils.smartTitle(s.notes, fallback = QUICK_SAVE_TITLE)
        } else {
            s.title.ifBlank { "Untitled" }
        }

        val entity = SaveItemEntity(
            title = title,
            url = s.url,
            localUri = ownLocalUri,
            thumbnailUri = s.previewImage,
            contentType = s.contentType.name,
            sourceApp = s.sourceApp.name,
            categoryId = s.selectedCategory,
            notes = s.notes.ifBlank { null },
            // Links have no OCR; this column is what search reads beyond
            // the title, so channel / site / description go here.
            ocrText = if (s.contentType == ContentType.LINK) s.linkDetails else null,
            isFavorite = s.isFavorite,
            remindAt = s.remindAt
        )
        val id = repo.insert(entity)
        // Schedule the reminder worker now that we have a real row id —
        // a remindAt set on the SaveSheet means the user explicitly asked
        // to be nudged about this save later. Notification permission is
        // requested at the UI layer before we ever get here.
        s.remindAt?.let { at ->
            com.ghostgramlabs.pettibox.data.reminders.ReminderScheduler.schedule(appContext, id, at)
        }
        val tagNames = parseTagInput(s.tagsInput)
        if (tagNames.isNotEmpty()) repo.setTagsForItem(id, tagNames)

        val attachmentRows = ownAttachments.mapIndexed { i, uri ->
            AttachmentEntity(
                itemId = id,
                uri = uri,
                kind = (s.attachmentKinds.getOrNull(i) ?: s.contentType).name,
                sortOrder = i
            )
        }
        val attachmentIds = repo.insertAttachments(attachmentRows)

        if (ocrPreferences.autoScan.first()) {
            if (attachmentRows.isEmpty()) {
                if (s.contentType == ContentType.IMAGE && !ownLocalUri.isNullOrBlank()) {
                    OcrWorker.enqueueForItem(appContext, id, ownLocalUri)
                }
                if (s.contentType == ContentType.PDF && !ownLocalUri.isNullOrBlank()) {
                    PdfTextWorker.enqueue(appContext, id, ownLocalUri)
                }
            } else {
                scanAttachments(id, attachmentRows, attachmentIds)
            }
        }
        // Offline reading copy — downloads in the background, never
        // slows the save itself.
        if (s.contentType == ContentType.LINK) articleRepository.onLinkSaved(id, s.url)
        // Keep the preview image on the phone so the card isn't blank offline.
        if (s.previewImage?.startsWith("http") == true) ThumbnailWorker.enqueue(appContext, id)

        _state.value = s.copy(isSaved = true)
    }

    /** Text recognition for each image attachment, text extraction for each PDF. */
    private fun scanAttachments(itemId: Long, rows: List<AttachmentEntity>, ids: List<Long>) {
        rows.zip(ids).forEach { (row, attId) ->
            when (row.kind) {
                ContentType.IMAGE.name -> OcrWorker.enqueueForAttachment(appContext, itemId, attId, row.uri)
                ContentType.PDF.name -> PdfTextWorker.enqueue(appContext, itemId, row.uri, attId)
            }
        }
    }

    private suspend fun ingestIfForeign(uriString: String): String? {
        return runCatching {
            val uri = Uri.parse(uriString)
            when (uri.scheme) {
                "content" -> attachmentStore.ingest(uri) ?: uriString
                else -> uriString // http, https, file — keep as-is
            }
        }.getOrNull()
    }

    /**
     * Adds the current share's content as attachments + appended note onto an
     * existing item, instead of creating a new one. Used by the "Add to
     * existing" flow on the Save sheet.
     */
    fun saveToExisting(targetItemId: Long) = viewModelScope.launch {
        val s = _state.value
        val target = repo.getById(targetItemId) ?: return@launch

        val ownLocalUri = s.localUri?.let { ingestIfForeign(it) }
        val ownAttachments = s.attachments.map { ingestIfForeign(it) ?: it }

        val urisToAttach = buildList {
            if (s.attachments.isEmpty() && !ownLocalUri.isNullOrBlank()) add(ownLocalUri)
            else addAll(ownAttachments)
        }

        val existing = repo.attachmentsFor(target.id)
        // A single-file save keeps its file only in localUri. Once something
        // is appended, the gallery shows attachments only, so the original
        // file would vanish from view; give it a row of its own first.
        val targetType = runCatching { ContentType.valueOf(target.contentType) }.getOrNull()
        if (existing.isEmpty() && !target.localUri.isNullOrBlank() &&
            targetType in setOf(ContentType.IMAGE, ContentType.PDF, ContentType.FILE)
        ) {
            repo.insertAttachments(
                listOf(AttachmentEntity(itemId = target.id, uri = target.localUri, kind = targetType!!.name, sortOrder = 0))
            )
        }
        val baseSort = (repo.attachmentsFor(target.id).maxOfOrNull { it.sortOrder } ?: -1) + 1
        val rows = urisToAttach.mapIndexed { i, uri ->
            AttachmentEntity(
                itemId = target.id,
                uri = uri,
                kind = (s.attachmentKinds.getOrNull(i) ?: s.contentType).name,
                sortOrder = baseSort + i
            )
        }
        val attIds = repo.insertAttachments(rows)

        // Merge any incoming notes into the existing item.
        val mergedNotes = listOfNotNull(target.notes, s.notes.ifBlank { null }, s.url)
            .joinToString("\n\n")
            .ifBlank { null }
        if (mergedNotes != target.notes) {
            repo.update(target.copy(notes = mergedNotes, updatedAt = System.currentTimeMillis()))
        }

        if (ocrPreferences.autoScan.first()) scanAttachments(target.id, rows, attIds)

        _state.value = s.copy(isSaved = true)
    }

    private fun parseTagInput(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        return input.split(Regex("[,\\n]+"))
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotBlank() && it.length <= 24 }
            .distinct()
    }
}
