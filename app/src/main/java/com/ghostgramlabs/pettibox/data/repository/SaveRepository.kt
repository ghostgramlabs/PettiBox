package com.ghostgramlabs.pettibox.data.repository

import androidx.paging.PagingSource
import androidx.room.withTransaction
import com.ghostgramlabs.pettibox.data.local.AppDatabase
import com.ghostgramlabs.pettibox.data.local.ArticleCopyEntity
import com.ghostgramlabs.pettibox.data.local.ArticleCopyStatus
import com.ghostgramlabs.pettibox.data.local.ArticleDao
import com.ghostgramlabs.pettibox.data.local.AttachmentDao
import com.ghostgramlabs.pettibox.data.local.AttachmentEntity
import com.ghostgramlabs.pettibox.data.local.CategoryCount
import com.ghostgramlabs.pettibox.data.local.CategoryDao
import com.ghostgramlabs.pettibox.data.local.CategoryEntity
import com.ghostgramlabs.pettibox.data.local.ItemTagCrossRef
import com.ghostgramlabs.pettibox.data.local.SaveDao
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity
import com.ghostgramlabs.pettibox.data.local.SourceCount
import com.ghostgramlabs.pettibox.data.local.TagDao
import com.ghostgramlabs.pettibox.data.local.TagEntity
import com.ghostgramlabs.pettibox.data.local.TagWithCount
import com.ghostgramlabs.pettibox.data.backup.BackupSummaryCalculator
import com.ghostgramlabs.pettibox.data.bookmarks.BookmarkCsvWriter
import com.ghostgramlabs.pettibox.data.bookmarks.ImportedBookmark
import com.ghostgramlabs.pettibox.data.util.AttachmentStore
import com.ghostgramlabs.pettibox.domain.model.CategoryPalette
import com.ghostgramlabs.pettibox.domain.model.ContentType
import com.ghostgramlabs.pettibox.domain.model.SourceApp
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveRepository @Inject constructor(
    private val database: AppDatabase,
    private val saveDao: SaveDao,
    private val categoryDao: CategoryDao,
    private val attachmentDao: AttachmentDao,
    private val tagDao: TagDao,
    private val articleDao: ArticleDao,
    private val attachmentStore: AttachmentStore
) {


    data class BackupImportResult(
        val categories: Int,
        val saves: Int,
        val attachments: Int,
        val tags: Int,
        val skippedDuplicates: Int = 0
    )

    data class BackupExportResult(
        val saves: Int,
        val attachments: Int,
        val embeddedFiles: Int,
        val favorites: Int,
        val archived: Int,
        val tags: Int,
        val tagLinks: Int
    )

    data class BookmarkImportResult(
        val imported: Int,
        val skippedDuplicates: Int,
        val newCollections: Int
    )

    data class StarterSweepResult(val removed: Int, val keptWithSaves: Int)

    private companion object {
        const val ARTICLE_ENTRY_PREFIX = "articles/"
    }

    /**
     * Seed the starter collections. Runs ONCE per install (the caller
     * gates on [com.ghostgramlabs.pettibox.data.preferences.OnboardingPreferences.categoriesSeeded]).
     * Starters are fully user-editable and deletable now, so this must
     * never re-assert them on later launches — that would resurrect
     * deleted collections and clobber renames. The IGNORE insert keeps
     * the one legacy case safe: existing installs run this once more to
     * pick up the seeded flag without touching their current rows.
     */
    suspend fun seedDefaultCategories() {
        val defaults = CategoryPalette.Defaults.mapIndexed { i, p ->
            CategoryEntity(
                id = p.id,
                name = p.name,
                emoji = p.emoji,
                colorHex = p.colorHex,
                sortOrder = i
            )
        }
        categoryDao.insertAll(defaults)
        // Sweep up a legacy "clipboard" preset from the earlier WIP that
        // briefly seeded clipboard as a collection. We moved it to a
        // source-app-tagged hub instead, so the category should not be
        // sitting in the user's collection grid. Delete only if it's
        // still a non-user-created row — never clobber a stash the user
        // actually created themselves.
        categoryDao.getById("clipboard")?.let { legacy ->
            if (!legacy.userCreated) {
                categoryDao.delete("clipboard")
            }
        }
    }

    // ── Save items ────────────────────────────────────────────────────────

    suspend fun insert(item: SaveItemEntity): Long = saveDao.insert(item)
    suspend fun update(item: SaveItemEntity) = saveDao.update(item)
    suspend fun getById(id: Long) = saveDao.getById(id)
    fun observeById(id: Long) = saveDao.observeById(id)
    /** Existing live save with this exact URL, if any — for duplicate-on-save detection. */
    suspend fun findByUrl(url: String): SaveItemEntity? = saveDao.findByUrl(url)

    /**
     * Cleans up local files (attachments + the item's own localUri) before
     * letting the row's CASCADE fire. Without this, files in filesDir/
     * accumulate forever as users delete saves.
     */
    suspend fun delete(id: Long) {
        val uris = attachmentDao.urisForItem(id)
        saveDao.delete(id) // cascades attachments + item_tags
        attachmentStore.deleteByUris(uris)
    }

    /**
     * Sweep any rows left in the `is_pending_delete` state from a
     * previous session (force-stop, process death, OS kill during the
     * Undo window). Called once on app start. Files for those rows are
     * cleaned up too — without this they'd accumulate forever.
     */
    suspend fun sweepOrphanedPendingDeletes() {
        val uris = saveDao.urisForPendingDeletes()
        saveDao.permanentlyDeletePending()
        if (uris.isNotEmpty()) attachmentStore.deleteByUris(uris)
    }

    fun observeRecent(limit: Int = 20) = saveDao.observeRecent(limit)
    fun observeFavorites() = saveDao.observeFavorites()
    fun observePinned() = saveDao.observePinned()
    suspend fun browseForSearch(): List<SaveItemEntity> = saveDao.browseForSearch()

    // Paged browses for large lists.
    fun pagedAll(includeArchived: Boolean = false, sort: String = "NEWEST"): PagingSource<Int, SaveItemEntity> =
        saveDao.pagedAll(includeArchived, sort)
    fun pagedByCategory(
        categoryId: String,
        includeArchived: Boolean = false,
        sort: String = "NEWEST"
    ): PagingSource<Int, SaveItemEntity> =
        saveDao.pagedByCategory(categoryId, includeArchived, sort)
    fun pagedBySource(sourceApp: String): PagingSource<Int, SaveItemEntity> =
        saveDao.pagedBySource(sourceApp)

    fun pagedFavorites(sort: String = "NEWEST"): PagingSource<Int, SaveItemEntity> = saveDao.pagedFavorites(sort)

    fun pagedArchived(sort: String = "UPDATED"): PagingSource<Int, SaveItemEntity> = saveDao.pagedArchived(sort)

    fun pagedByTag(name: String, sort: String = "NEWEST"): PagingSource<Int, SaveItemEntity> =
        saveDao.pagedByTag(name, sort)

    fun pagedUpcomingReminders(sort: String = "REMINDER"): PagingSource<Int, SaveItemEntity> =
        saveDao.pagedUpcomingReminders(sort = sort)

    // Aggregates — never load full rows just to count.
    fun observeSourceCounts(): Flow<List<SourceCount>> = saveDao.observeSourceCounts()
    fun observeCategoryCounts(): Flow<List<CategoryCount>> = saveDao.observeCategoryCounts()
    /** Collection ids, most-recently-used first — orders the Save sheet chip row. */
    fun observeRecentCategoryIds(limit: Int = 12): Flow<List<String>> =
        saveDao.observeRecentCategoryIds(limit)
    fun observeTotal(): Flow<Int> = saveDao.observeTotal()
    fun observeArchivedTotal(): Flow<Int> = saveDao.observeArchivedTotal()
    fun observeFavoriteTotal(): Flow<Int> = saveDao.observeFavoriteTotal()
    fun observeUpcomingReminderTotal(): Flow<Int> = saveDao.observeUpcomingReminderTotal()

    suspend fun setFavorite(id: Long, fav: Boolean) = saveDao.setFavorite(id, fav)
    suspend fun setPinned(id: Long, pin: Boolean) = saveDao.setPinned(id, pin)
    suspend fun setArchived(id: Long, archived: Boolean) = saveDao.setArchived(id, archived)
    /**
     * Set the row's [SaveItemEntity.isPendingDelete] flag. Used by the
     * "Delete with Undo" flow to hide a row from every listing query —
     * including Archive — without losing it so Undo can clear the flag.
     */
    suspend fun setPendingDelete(id: Long, pending: Boolean) = saveDao.setPendingDelete(id, pending)
    suspend fun setRemindAt(id: Long, at: Long?) = saveDao.setRemindAt(id, at)
    suspend fun dueReminders(): List<SaveItemEntity> = saveDao.dueReminders()
    /** Reschedule helper: every item that still has a reminder pointed at it. */
    suspend fun dueOrPendingReminders(): List<SaveItemEntity> = saveDao.pendingReminders()
    suspend fun touchOpened(id: Long) = saveDao.touchOpened(id)
    suspend fun setOcrText(id: Long, text: String) = saveDao.setOcrText(id, text)
    suspend fun appendOcrText(id: Long, text: String) = saveDao.appendOcrText(id, text)

    suspend fun imageItemsNeedingOcr(): List<SaveItemEntity> = saveDao.imageItemsNeedingOcr()
    suspend fun pdfItemsNeedingOcr(): List<SaveItemEntity> = saveDao.pdfItemsNeedingOcr()

    suspend fun search(rawQuery: String): List<SaveItemEntity> {
        val q = SearchQuerySanitizer.sanitizeFtsQuery(rawQuery)
        if (q.isBlank()) return emptyList()
        return runCatching { saveDao.search(q) }.getOrDefault(emptyList())
    }

    // ── Categories ────────────────────────────────────────────────────────

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()
    suspend fun upsertCategory(c: CategoryEntity) = categoryDao.upsert(c)
    suspend fun getCategory(id: String) = categoryDao.getById(id)
    suspend fun deleteCategory(id: String) = categoryDao.delete(id)

    data class CsvExportResult(val csv: String, val links: Int)

    /**
     * Every save that has a URL, as portable bookmark CSV (see
     * [BookmarkCsvWriter] for the column contract). Links only — notes,
     * images, and files have no URL for other apps to open; the zip
     * backup remains the full-fidelity export.
     */
    suspend fun exportBookmarksCsv(): CsvExportResult {
        val categoryNames = categoryDao.allForExport().associate { it.id to it.name }
        val tagNames = tagDao.allForExport().associate { it.id to it.name }
        val tagsByItem = tagDao.linksForExport()
            .groupBy({ it.itemId }, { tagNames[it.tagId] })
            .mapValues { (_, names) -> names.filterNotNull() }
        val bookmarks = saveDao.allForExport()
            .filter { !it.url.isNullOrBlank() }
            .map { s ->
                ImportedBookmark(
                    url = s.url.orEmpty(),
                    title = s.title,
                    folder = s.categoryId?.let { categoryNames[it] },
                    tags = tagsByItem[s.id].orEmpty(),
                    notes = s.notes,
                    createdAt = s.createdAt,
                    isFavorite = s.isFavorite,
                    isArchived = s.isArchived
                )
            }
        return CsvExportResult(csv = BookmarkCsvWriter.write(bookmarks), links = bookmarks.size)
    }

    /**
     * One-tap cleanup for users who don't want the prefilled starters.
     * Deletes every starter (non-user-created) collection that holds no
     * saves at all — archived and Undo-staged rows count as "holding", so
     * nothing is ever silently unfiled. Starters with saves are kept and
     * counted so the UI can tell the user why they stayed. Deleted
     * starters never come back: seeding runs once per install.
     */
    suspend fun removeEmptyStarterCollections(): StarterSweepResult = database.withTransaction {
        var removed = 0
        var keptWithSaves = 0
        categoryDao.allForExport()
            .filter { !it.userCreated }
            .forEach { category ->
                if (saveDao.countAllForCategory(category.id) == 0) {
                    categoryDao.delete(category.id)
                    removed++
                } else {
                    keptWithSaves++
                }
            }
        StarterSweepResult(removed = removed, keptWithSaves = keptWithSaves)
    }

    suspend fun exportBackupJson(): String {
        val categories = categoryDao.allForExport()
        val saves = saveDao.allForExport()
        val attachments = attachmentDao.allForExport()
        val tags = tagDao.allForExport()
        val itemTags = tagDao.linksForExport()
        val root = JSONObject()
            .put("schema", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("summary", backupSummary(saves, attachments, tags, itemTags))
        root.put("categories", JSONArray().apply {
            categories.forEach { c ->
                put(JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("emoji", c.emoji)
                    .put("colorHex", c.colorHex)
                    .put("sortOrder", c.sortOrder)
                    .put("parentId", c.parentId)
                    .put("userCreated", c.userCreated)
                    .put("createdAt", c.createdAt))
            }
        })
        root.put("saves", JSONArray().apply {
            saves.forEach { s ->
                put(JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("url", s.url)
                    .put("localUri", s.localUri)
                    .put("thumbnailUri", s.thumbnailUri)
                    .put("contentType", s.contentType)
                    .put("sourceApp", s.sourceApp)
                    .put("categoryId", s.categoryId)
                    .put("notes", s.notes)
                    .put("ocrText", s.ocrText)
                    .put("metadataJson", s.metadataJson)
                    .put("isFavorite", s.isFavorite)
                    .put("isPinned", s.isPinned)
                    .put("isArchived", s.isArchived)
                    .put("favorite", s.isFavorite)
                    .put("pinned", s.isPinned)
                    .put("archived", s.isArchived)
                    .put("remindAt", s.remindAt)
                    .put("createdAt", s.createdAt)
                    .put("updatedAt", s.updatedAt)
                    .put("openedAt", s.openedAt))
            }
        })
        root.put("attachments", JSONArray().apply {
            attachments.forEach { a ->
                put(JSONObject()
                    .put("id", a.id)
                    .put("itemId", a.itemId)
                    .put("uri", a.uri)
                    .put("kind", a.kind)
                    .put("ocrText", a.ocrText)
                    .put("sortOrder", a.sortOrder)
                    .put("createdAt", a.createdAt))
            }
        })
        root.put("tags", JSONArray().apply {
            tags.forEach { t ->
                put(JSONObject().put("id", t.id).put("name", t.name).put("createdAt", t.createdAt))
            }
        })
        root.put("itemTags", JSONArray().apply {
            itemTags.forEach { ref ->
                put(JSONObject().put("itemId", ref.itemId).put("tagId", ref.tagId))
            }
        })
        return root.toString(2)
    }

    suspend fun exportBackupZip(targetFile: File): BackupExportResult {
        val categories = categoryDao.allForExport()
        val saves = saveDao.allForExport()
        val attachments = attachmentDao.allForExport()
        val tags = tagDao.allForExport()
        val itemTags = tagDao.linksForExport()
        var embeddedFiles = 0

        val root = JSONObject()
            .put("schema", 2)
            .put("exportedAt", System.currentTimeMillis())
            .put("summary", backupSummary(saves, attachments, tags, itemTags))

        targetFile.parentFile?.mkdirs()
        ZipOutputStream(targetFile.outputStream().buffered()).use { zip ->
            root.put("categories", JSONArray().apply {
                categories.forEach { c ->
                    put(JSONObject()
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("emoji", c.emoji)
                        .put("colorHex", c.colorHex)
                        .put("sortOrder", c.sortOrder)
                        .put("parentId", c.parentId)
                        .put("userCreated", c.userCreated)
                        .put("createdAt", c.createdAt))
                }
            })

            root.put("saves", JSONArray().apply {
                saves.forEach { s ->
                    val localBackupPath = s.localUri?.let { uri ->
                        val path = "files/save_${s.id}_${safeExt(uri)}"
                        if (attachmentStore.copyUriToZip(uri, zip, path)) {
                            embeddedFiles++
                            path
                        } else null
                    }
                    put(JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("url", s.url)
                        .put("localUri", s.localUri)
                        .put("localBackupPath", localBackupPath)
                        .put("thumbnailUri", s.thumbnailUri)
                        .put("contentType", s.contentType)
                        .put("sourceApp", s.sourceApp)
                        .put("categoryId", s.categoryId)
                        .put("notes", s.notes)
                        .put("ocrText", s.ocrText)
                        .put("metadataJson", s.metadataJson)
                        .put("isFavorite", s.isFavorite)
                        .put("isPinned", s.isPinned)
                        .put("isArchived", s.isArchived)
                        .put("favorite", s.isFavorite)
                        .put("pinned", s.isPinned)
                        .put("archived", s.isArchived)
                        .put("remindAt", s.remindAt)
                        .put("createdAt", s.createdAt)
                        .put("updatedAt", s.updatedAt)
                        .put("openedAt", s.openedAt))
                }
            })

            root.put("attachments", JSONArray().apply {
                attachments.forEach { a ->
                    val backupPath = a.uri.let { uri ->
                        val path = "files/attachment_${a.id}_${safeExt(uri)}"
                        if (attachmentStore.copyUriToZip(uri, zip, path)) {
                            embeddedFiles++
                            path
                        } else null
                    }
                    put(JSONObject()
                        .put("id", a.id)
                        .put("itemId", a.itemId)
                        .put("uri", a.uri)
                        .put("backupPath", backupPath)
                        .put("kind", a.kind)
                        .put("ocrText", a.ocrText)
                        .put("sortOrder", a.sortOrder)
                        .put("createdAt", a.createdAt))
                }
            })

            root.put("tags", JSONArray().apply {
                tags.forEach { t ->
                    put(JSONObject().put("id", t.id).put("name", t.name).put("createdAt", t.createdAt))
                }
            })
            root.put("itemTags", JSONArray().apply {
                itemTags.forEach { ref ->
                    put(JSONObject().put("itemId", ref.itemId).put("tagId", ref.tagId))
                }
            })
            zip.putNextEntry(java.util.zip.ZipEntry("backup.json"))
            zip.write(root.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // Offline article copies, one entry each and one row in memory
            // at a time — folding them into backup.json would build a
            // string as big as every article combined.
            for (saveId in articleDao.readyIds()) {
                val copy = runCatching { articleDao.get(saveId) }.getOrNull() ?: continue
                zip.putNextEntry(java.util.zip.ZipEntry("$ARTICLE_ENTRY_PREFIX$saveId.json"))
                zip.write(articleJson(copy).toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }

        return BackupExportResult(
            saves = saves.size,
            attachments = attachments.size,
            embeddedFiles = embeddedFiles,
            favorites = saves.count { it.isFavorite },
            archived = saves.count { it.isArchived },
            tags = tags.size,
            tagLinks = itemTags.size
        )
    }

    suspend fun importBackupZip(input: InputStream): BackupImportResult {
        var backupJson: String? = null
        val fileUrisByPath = mutableMapOf<String, String>()
        // Article entries are spooled to temp files (keyed by the backup's
        // save id) and inserted after the saves exist, one at a time.
        val articleFiles = mutableMapOf<Long, File>()
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    // Guard against zip-slip: a malicious or corrupted backup
                    // with entries like "files/../../../etc/passwd" would
                    // otherwise escape the attachments sandbox via the
                    // originalName/extension. We only accept entries strictly
                    // inside files/ with no parent-traversal segments.
                    val safeName = !entry.name.contains("..") && !entry.name.startsWith("/")
                    when {
                        safeName && entry.name == "backup.json" -> {
                            backupJson = zip.readBytes().toString(Charsets.UTF_8)
                        }
                        safeName && entry.name.startsWith(ARTICLE_ENTRY_PREFIX) -> {
                            entry.name.removePrefix(ARTICLE_ENTRY_PREFIX).removeSuffix(".json").toLongOrNull()
                                ?.let { oldSaveId ->
                                    val temp = File.createTempFile("article_", ".json")
                                    temp.outputStream().use { NonClosingInputStream(zip).copyTo(it) }
                                    articleFiles[oldSaveId] = temp
                                }
                        }
                        safeName && entry.name.startsWith("files/") -> {
                            attachmentStore.ingestBackupFile(
                                input = NonClosingInputStream(zip),
                                originalName = entry.name.substringAfterLast('/')
                            )?.let { uri ->
                                fileUrisByPath[entry.name] = uri
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val json = backupJson ?: error("Missing backup.json")
            return importBackupJson(json, fileUrisByPath, articleFiles)
        } finally {
            articleFiles.values.forEach { it.delete() }
        }
    }

    suspend fun importBackupJson(json: String): BackupImportResult =
        importBackupJson(json, emptyMap(), emptyMap())

    private suspend fun importBackupJson(
        json: String,
        fileUrisByPath: Map<String, String>,
        articleFiles: Map<Long, File>
    ): BackupImportResult = database.withTransaction {
        // Wrapping the whole import in a transaction means a mid-flight
        // failure (process death, OOM, malformed entry) rolls everything
        // back — the user retries from the same backup file and doesn't
        // end up with half their library duplicated.
        val root = JSONObject(json)
        val categories = root.optJSONArray("categories") ?: JSONArray()
        val saves = root.optJSONArray("saves") ?: JSONArray()
        val attachments = root.optJSONArray("attachments") ?: JSONArray()
        val tags = root.optJSONArray("tags") ?: JSONArray()
        val itemTags = root.optJSONArray("itemTags") ?: JSONArray()

        var categoryCount = 0
        for (i in 0 until categories.length()) {
            val c = categories.getJSONObject(i)
            val category = CategoryEntity(
                id = c.getString("id"),
                name = c.optString("name", "Imported"),
                emoji = c.optString("emoji", "\uD83D\uDCE6"),
                colorHex = c.optLong("colorHex", 0xFF8B5CF6.toLong()),
                sortOrder = c.optInt("sortOrder", i),
                parentId = c.optNullableString("parentId"),
                userCreated = c.optBoolean("userCreated", true),
                createdAt = c.optLong("createdAt", System.currentTimeMillis())
            )
            categoryDao.upsert(category)
            categoryCount++
        }

        // Restoring on top of an existing shelf must not duplicate it —
        // the "phone + tablet, restore on both" case. Links match on URL;
        // URL-less items on the (contentType, createdAt, title) triple.
        // Skipped saves drop their attachments and tag links too, because
        // their old ids never enter itemIdMap.
        val existingUrls = mutableSetOf<String>()
        val existingFingerprints = mutableSetOf<String>()
        for (key in saveDao.dedupeKeys()) {
            val url = key.url?.trim()
            if (!url.isNullOrEmpty()) existingUrls.add(url.lowercase())
            else existingFingerprints.add("${key.contentType}|${key.createdAt}|${key.title}")
        }

        val itemIdMap = mutableMapOf<Long, Long>()
        var saveCount = 0
        var skippedCount = 0
        for (i in 0 until saves.length()) {
            val s = saves.getJSONObject(i)
            val oldId = s.optLong("id", -1L)
            val imported = SaveItemEntity(
                title = s.optString("title", "Imported save").ifBlank { "Imported save" },
                url = s.optNullableString("url"),
                localUri = s.optNullableString("localBackupPath")?.let { fileUrisByPath[it] }
                    ?: s.optNullableString("localUri"),
                thumbnailUri = s.optNullableString("thumbnailUri"),
                contentType = s.optString("contentType", "NOTE"),
                sourceApp = s.optString("sourceApp", "UNKNOWN"),
                categoryId = s.optNullableString("categoryId"),
                notes = s.optNullableString("notes"),
                ocrText = s.optNullableString("ocrText"),
                metadataJson = s.optNullableString("metadataJson"),
                isFavorite = s.optBoolean("isFavorite", s.optBoolean("favorite", false)),
                isPinned = s.optBoolean("isPinned", s.optBoolean("pinned", false)),
                isArchived = s.optBoolean("isArchived", s.optBoolean("archived", false)),
                remindAt = s.optNullableLong("remindAt"),
                createdAt = s.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = s.optLong("updatedAt", System.currentTimeMillis()),
                openedAt = s.optNullableLong("openedAt")
            )
            val urlKey = imported.url?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
            val fingerprint = if (urlKey == null) {
                "${imported.contentType}|${imported.createdAt}|${imported.title}"
            } else null
            val isDuplicate =
                if (urlKey != null) !existingUrls.add(urlKey)
                else !existingFingerprints.add(fingerprint!!)
            if (isDuplicate) {
                skippedCount++
                continue
            }
            val newId = saveDao.insert(imported)
            if (oldId > 0) itemIdMap[oldId] = newId
            saveCount++
        }

        var attachmentCount = 0
        val attachmentRows = buildList {
            for (i in 0 until attachments.length()) {
                val a = attachments.getJSONObject(i)
                val newItemId = itemIdMap[a.optLong("itemId", -1L)] ?: continue
                add(
                    AttachmentEntity(
                        itemId = newItemId,
                        uri = a.optNullableString("backupPath")?.let { fileUrisByPath[it] }
                            ?: a.optString("uri"),
                        kind = a.optString("kind", "FILE"),
                        ocrText = a.optNullableString("ocrText"),
                        sortOrder = a.optInt("sortOrder", i),
                        createdAt = a.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }
        if (attachmentRows.isNotEmpty()) {
            attachmentDao.insertAll(attachmentRows)
            attachmentCount = attachmentRows.size
        }

        val tagIdMap = mutableMapOf<Long, Long>()
        var tagCount = 0
        for (i in 0 until tags.length()) {
            val t = tags.getJSONObject(i)
            val name = t.optString("name").trim()
            if (name.isBlank()) continue
            val newId = tagDao.upsert(name)
            if (newId > 0) {
                val oldId = t.optLong("id", -1L)
                if (oldId > 0) tagIdMap[oldId] = newId
                tagCount++
            }
        }

        for (i in 0 until itemTags.length()) {
            val ref = itemTags.getJSONObject(i)
            val newItemId = itemIdMap[ref.optLong("itemId", -1L)] ?: continue
            val newTagId = tagIdMap[ref.optLong("tagId", -1L)] ?: continue
            tagDao.link(ItemTagCrossRef(newItemId, newTagId))
        }

        // Offline article copies travel with the backup: the point of a
        // copy is outliving the page, so a new phone must not lose it.
        for ((oldSaveId, file) in articleFiles) {
            val newItemId = itemIdMap[oldSaveId] ?: continue
            val a = runCatching { JSONObject(file.readText()) }.getOrNull() ?: continue
            val html = a.optNullableString("html") ?: continue
            articleDao.upsert(
                ArticleCopyEntity(
                    saveId = newItemId,
                    status = ArticleCopyStatus.READY.name,
                    html = html,
                    textContent = a.optNullableString("text"),
                    byline = a.optNullableString("byline"),
                    wordCount = a.optInt("wordCount", 0),
                    fetchedAt = a.optLong("fetchedAt", System.currentTimeMillis())
                )
            )
        }

        BackupImportResult(
            categories = categoryCount,
            saves = saveCount,
            attachments = attachmentCount,
            tags = tagCount,
            skippedDuplicates = skippedCount
        )
    }

    /**
     * Import bookmarks parsed from another app's export file (browser HTML,
     * Raindrop/Pocket CSV, plain URL list — see
     * [com.ghostgramlabs.pettibox.data.bookmarks.BookmarkFileParser]).
     *
     * Source folders map onto collections by name (case-insensitive); unknown
     * folders become new user-editable collections. Links whose URL is
     * already on the shelf — or repeated within the file — are skipped, so
     * re-running an import is safe and never duplicates the library.
     */
    suspend fun importBookmarks(bookmarks: List<ImportedBookmark>): BookmarkImportResult =
        database.withTransaction {
            val existing = categoryDao.allForExport()
            val categoriesByName = existing
                .associateBy { it.name.trim().lowercase() }
                .toMutableMap()
            var maxSortOrder = existing.maxOfOrNull { it.sortOrder } ?: 0
            var imported = 0
            var skipped = 0
            var newCollections = 0
            val seenUrls = mutableSetOf<String>()

            for (bookmark in bookmarks) {
                val url = bookmark.url.trim()
                if (!seenUrls.add(url.lowercase()) || saveDao.findByUrl(url) != null) {
                    skipped++
                    continue
                }

                val categoryId = bookmark.folder?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { folderName ->
                        val key = folderName.lowercase()
                        val category = categoriesByName.getOrPut(key) {
                            CategoryEntity(
                                id = "user_" + UUID.randomUUID().toString().take(8),
                                // Same 28-char cap the collection editor enforces.
                                name = folderName.take(28),
                                emoji = "🔖",
                                colorHex = CategoryPalette
                                    .Defaults[newCollections % CategoryPalette.Defaults.size]
                                    .colorHex,
                                sortOrder = ++maxSortOrder,
                                userCreated = true
                            ).also {
                                categoryDao.upsert(it)
                                newCollections++
                            }
                        }
                        category.id
                    }

                val now = System.currentTimeMillis()
                val itemId = saveDao.insert(
                    SaveItemEntity(
                        title = bookmark.title.trim().ifBlank { url },
                        url = url,
                        contentType = ContentType.LINK.name,
                        sourceApp = SourceApp.fromUrl(url).name,
                        categoryId = categoryId,
                        notes = bookmark.notes?.trim()?.ifBlank { null },
                        isFavorite = bookmark.isFavorite,
                        isArchived = bookmark.isArchived,
                        createdAt = bookmark.createdAt ?: now,
                        updatedAt = now
                    )
                )
                for (tag in bookmark.tags) {
                    val tagId = tagDao.upsert(tag)
                    if (tagId > 0) tagDao.link(ItemTagCrossRef(itemId, tagId))
                }
                imported++
            }

            BookmarkImportResult(
                imported = imported,
                skippedDuplicates = skipped,
                newCollections = newCollections
            )
        }

    // ── Attachments ───────────────────────────────────────────────────────

    suspend fun insertAttachments(items: List<AttachmentEntity>): List<Long> =
        if (items.isEmpty()) emptyList() else attachmentDao.insertAll(items)

    fun observeAttachments(itemId: Long) = attachmentDao.observeForItem(itemId)
    suspend fun attachmentsFor(itemId: Long) = attachmentDao.forItem(itemId)
    suspend fun imageAttachmentsNeedingOcr(): List<AttachmentEntity> =
        attachmentDao.imageAttachmentsNeedingOcr()
    suspend fun pdfAttachmentsNeedingOcr(): List<AttachmentEntity> =
        attachmentDao.pdfAttachmentsNeedingOcr()
    suspend fun setAttachmentOcr(id: Long, text: String) = attachmentDao.setOcrText(id, text)
    suspend fun deleteAttachment(id: Long) {
        val attachment = attachmentDao.getById(id) ?: return
        attachmentDao.delete(id)
        attachmentStore.deleteByUris(listOf(attachment.uri))
    }

    // ── Tags ──────────────────────────────────────────────────────────────

    suspend fun setTagsForItem(itemId: Long, tagNames: List<String>) {
        tagDao.unlinkAll(itemId)
        for (raw in tagNames) {
            val tagId = tagDao.upsert(raw)
            if (tagId > 0) tagDao.link(ItemTagCrossRef(itemId, tagId))
        }
    }

    suspend fun addTag(itemId: Long, name: String) {
        val tagId = tagDao.upsert(name)
        if (tagId > 0) tagDao.link(ItemTagCrossRef(itemId, tagId))
    }

    suspend fun removeTag(itemId: Long, name: String) {
        val id = tagDao.findIdByName(name) ?: return
        tagDao.unlink(itemId, id)
    }

    fun observeTagsForItem(itemId: Long): Flow<List<TagEntity>> =
        tagDao.observeTagsForItem(itemId)

    fun observeTopTags(limit: Int = 20): Flow<List<TagWithCount>> =
        tagDao.observeTopTags(limit)

    suspend fun itemIdsForTag(name: String): List<Long> = tagDao.itemIdsForTag(name)

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun articleJson(a: ArticleCopyEntity): JSONObject = JSONObject()
        .put("html", a.html)
        .put("text", a.textContent)
        .put("byline", a.byline)
        .put("wordCount", a.wordCount)
        .put("fetchedAt", a.fetchedAt)

    private fun backupSummary(
        saves: List<SaveItemEntity>,
        attachments: List<AttachmentEntity>,
        tags: List<TagEntity>,
        itemTags: List<ItemTagCrossRef>
    ): JSONObject = BackupSummaryCalculator.summarize(saves, attachments, tags, itemTags)

    private fun safeExt(uri: String): String {
        val clean = uri.substringBefore('?').substringAfterLast('/', uri).substringAfterLast('.', "bin")
        return clean.takeIf { it.length in 1..8 && it.all { ch -> ch.isLetterOrDigit() } } ?: "bin"
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).ifBlank { null }

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name)

    private class NonClosingInputStream(
        private val delegate: InputStream
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() = Unit
    }
}
