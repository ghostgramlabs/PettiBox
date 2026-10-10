package com.ghostgramlabs.pettibox.data.sync

import android.content.Context
import android.net.Uri
import com.ghostgramlabs.pettibox.data.local.AttachmentDao
import com.ghostgramlabs.pettibox.data.local.AttachmentEntity
import com.ghostgramlabs.pettibox.data.local.CategoryDao
import com.ghostgramlabs.pettibox.data.local.CategoryEntity
import com.ghostgramlabs.pettibox.data.local.ItemTagCrossRef
import com.ghostgramlabs.pettibox.data.local.SaveDao
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity
import com.ghostgramlabs.pettibox.data.local.SyncDao
import com.ghostgramlabs.pettibox.data.local.SyncFileEntity
import com.ghostgramlabs.pettibox.data.local.TagDao
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.FILE_OCR_PREFIX
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_ARCH
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_CAT
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_COLOR
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_EMOJI
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_FAV
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_LOCKED
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_MAIN
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_THUMB
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_META
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_NAME
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_NOTES
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_OCR
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_OPENED
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_ORDER
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_PARENT
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_PIN
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_REMIND
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_SRC
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_TITLE
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_TRASH
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_TYPE
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_URL
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_USER
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.KIND_COLLECTION
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.KIND_ITEM
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A file a save refers to that isn't on this phone yet. */
const val PENDING_FILE_SCHEME = "pettibox-sync:"

/**
 * Between Room and sync records: reads this device's current state of a
 * record ([snapshot]) and shows a merged record on this phone ([apply]).
 *
 * [apply] never deletes a save. A save removed for good on another device
 * goes to Recently deleted here, for this phone's own 30 days.
 */
@Singleton
class LocalLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val saveDao: SaveDao,
    private val categoryDao: CategoryDao,
    private val tagDao: TagDao,
    private val attachmentDao: AttachmentDao,
    private val syncDao: SyncDao
) {

    /** What happened when a record was shown here, for the caller's follow-up work. */
    data class Applied(val itemId: Long? = null, val isNewLink: Boolean = false, val remindChanged: Boolean = false)

    // ── Reading ──────────────────────────────────────────────────────────

    suspend fun snapshot(kind: String, id: String, meta: SyncRecord?): LocalRecord? = when (kind) {
        KIND_ITEM -> snapshotItem(id, meta)
        KIND_COLLECTION -> snapshotCollection(id, meta)
        else -> null
    }

    /** Last-edited time of a record here, used for its first sync (so old copies don't win). */
    suspend fun editedAt(kind: String, id: String): Long? = when (kind) {
        KIND_ITEM -> syncDao.itemBySyncId(id)?.updatedAt
        KIND_COLLECTION -> categoryDao.getById(id)?.createdAt
        else -> null
    }

    private suspend fun snapshotItem(syncId: String, meta: SyncRecord?): LocalRecord? {
        val s = syncDao.itemBySyncId(syncId) ?: return null
        val atts = attachmentDao.forItem(s.id)
        // A collection removed elsewhere un-files saves here (the database
        // does that). That's not an edit of the save, so report the value
        // the merged copy still has.
        val cat = s.categoryId ?: (meta?.value(F_CAT) as? String)?.takeIf { categoryDao.getById(it) == null }
        val fields = linkedMapOf<String, Any?>(
            F_TITLE to s.title,
            F_URL to s.url,
            F_NOTES to s.notes,
            F_OCR to s.ocrText,
            F_META to s.metadataJson,
            F_CAT to cat,
            F_TYPE to s.contentType,
            F_SRC to s.sourceApp,
            F_FAV to s.isFavorite,
            F_PIN to s.isPinned,
            F_ARCH to s.isArchived,
            F_REMIND to s.remindAt,
            F_OPENED to s.openedAt,
            F_TRASH to if (s.isPendingDelete) (s.deletedAt ?: s.updatedAt) else null,
            // A file this phone can't read right now is not a removal: report
            // what the merged copy has, so nothing is taken off other devices.
            F_MAIN to s.localUri?.let { shaOf(it) ?: meta?.value(F_MAIN) },
            // Only a picture this phone holds as a file; a web address is still
            // being downloaded (ThumbnailWorker), and a missing one is kept as is.
            F_THUMB to (s.thumbnailUri?.takeIf { !it.startsWith("http") }?.let { shaOf(it) } ?: meta?.value(F_THUMB))
        )
        val files = LinkedHashMap<String, Pair<String, Long>>()
        var unreadable = false
        for (a in atts) {
            val sha = shaOf(a.uri)
            if (sha == null) {
                unreadable = true
                continue
            }
            files[sha] = a.kind to a.sortOrder.toLong()
            fields[FILE_OCR_PREFIX + sha] = a.ocrText
        }
        // A save with files shows those; a main file outside them is one that
        // was removed (older versions left it behind), so the first one stands in.
        if (files.isNotEmpty() && !unreadable && fields[F_MAIN] !in files.keys) {
            fields[F_MAIN] = files.entries.minByOrNull { it.value.second }!!.key
        }
        if (unreadable && meta != null) {
            files.clear()
            for ((sha, f) in meta.presentFiles()) files[sha] = f.kind to f.order
        }
        val tags = tagDao.tagsForItem(s.id).associate { it.name.lowercase() to it.name }
        return LocalRecord(KIND_ITEM, syncId, s.createdAt, fields, tags, files)
    }

    private suspend fun snapshotCollection(id: String, meta: SyncRecord?): LocalRecord? {
        val c = categoryDao.getById(id) ?: return null
        // A parent that isn't on this phone yet (or was removed elsewhere) is
        // not an edit: report what the merged copy has, as for a save's collection.
        val parent = c.parentId ?: (meta?.value(F_PARENT) as? String)?.takeIf { categoryDao.getById(it) == null }
        return LocalRecord(
            KIND_COLLECTION, id, c.createdAt,
            linkedMapOf(
                F_NAME to c.name,
                F_EMOJI to c.emoji,
                F_COLOR to c.colorHex,
                F_ORDER to c.sortOrder.toLong(),
                F_PARENT to parent,
                F_USER to c.userCreated,
                F_LOCKED to c.isLocked
            )
        )
    }

    /**
     * Content hash of a file this phone has, remembered per file (saved files
     * never change). A file still waiting to download carries its hash in
     * its placeholder. Null when the file is missing or unreadable.
     */
    suspend fun shaOf(uri: String): String? {
        if (uri.startsWith(PENDING_FILE_SCHEME)) return uri.removePrefix(PENDING_FILE_SCHEME)
        syncDao.fileByUri(uri)?.let { return it.sha256 }
        val sha = runCatching {
            openFile(uri)?.use { input ->
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
        }.getOrNull() ?: return null
        syncDao.putFile(SyncFileEntity(uri, sha))
        return sha
    }

    private fun openFile(uri: String) = when {
        uri.startsWith("file:") -> Uri.parse(uri).path?.let { File(it) }?.takeIf { it.exists() }?.inputStream()
        uri.startsWith("content:") -> context.contentResolver.openInputStream(Uri.parse(uri))
        else -> null
    }

    // ── Showing ──────────────────────────────────────────────────────────

    /** Writes a merged record into Room. Call inside the apply transaction (sync_applying set). */
    suspend fun apply(r: SyncRecord, now: Long): Applied = when (r.kind) {
        KIND_ITEM -> applyItem(r, now)
        KIND_COLLECTION -> {
            applyCollection(r); Applied()
        }
        else -> Applied()
    }

    private suspend fun applyItem(r: SyncRecord, now: Long): Applied {
        val existing = syncDao.itemBySyncId(r.id)
        if (r.isGone) {
            // Removed for good elsewhere. Never delete here: if it's live,
            // it waits in Recently deleted for this phone's 30 days.
            if (existing != null && !existing.isPendingDelete) {
                saveDao.update(existing.copy(isPendingDelete = true, deletedAt = now))
                return Applied(existing.id, remindChanged = existing.remindAt != null)
            }
            return Applied(existing?.id)
        }
        val cat = (r.value(F_CAT) as? String)?.takeIf { categoryDao.getById(it) != null }
        val mainSha = r.value(F_MAIN) as? String
        val localUri = mainSha?.let { localFileFor(it) }
        val thumbSha = r.value(F_THUMB) as? String
        val thumbnail = when {
            // No shared picture: keep whatever this phone made.
            thumbSha == null -> existing?.thumbnailUri
            existing?.thumbnailUri != null && !existing.thumbnailUri.startsWith("http") &&
                shaOf(existing.thumbnailUri) == thumbSha -> existing.thumbnailUri
            else -> localFileFor(thumbSha)
        }
        val trashedAt = r.trashedAt
        val base = existing ?: SaveItemEntity(
            title = "",
            contentType = r.value(SyncRecord.F_TYPE) as? String ?: "NOTE",
            sourceApp = r.value(F_SRC) as? String ?: "UNKNOWN",
            syncId = r.id,
            createdAt = r.created
        )
        val updated = base.copy(
            title = (r.value(F_TITLE) as? String).orEmpty().ifBlank { "Untitled" },
            url = r.value(F_URL) as? String,
            notes = r.value(F_NOTES) as? String,
            ocrText = r.value(F_OCR) as? String,
            metadataJson = r.value(F_META) as? String,
            categoryId = cat,
            contentType = r.value(SyncRecord.F_TYPE) as? String ?: base.contentType,
            sourceApp = r.value(F_SRC) as? String ?: base.sourceApp,
            isFavorite = r.value(F_FAV) as? Boolean ?: false,
            isPinned = r.value(F_PIN) as? Boolean ?: false,
            isArchived = r.value(F_ARCH) as? Boolean ?: false,
            remindAt = r.value(F_REMIND) as? Long,
            openedAt = r.value(F_OPENED) as? Long,
            isPendingDelete = trashedAt != null,
            deletedAt = trashedAt,
            // Keep this phone's own copy if it's the same file.
            localUri = if (mainSha == null) null
            else if (existing?.localUri != null && shaOf(existing.localUri) == mainSha) existing.localUri
            else localUri,
            thumbnailUri = thumbnail,
            createdAt = minOf(base.createdAt, r.created),
            updatedAt = minOf(r.editedAt, now)
        )
        val itemId = if (existing == null) saveDao.insert(updated) else {
            if (updated != existing) saveDao.update(updated)
            existing.id
        }

        // Tags: exactly the merged set.
        val want = r.presentTags()
        val have = tagDao.tagsForItem(itemId).associateBy { it.name.lowercase() }
        if (want.keys != have.keys) {
            for ((key, tag) in have) if (key !in want) tagDao.unlink(itemId, tag.id)
            for ((key, name) in want) if (key !in have) {
                val tagId = tagDao.upsert(name)
                if (tagId > 0) tagDao.link(ItemTagCrossRef(itemId, tagId))
            }
        }

        // Files: exactly the merged set, in order. Missing ones get a
        // placeholder until the file sync brings them down.
        val wantFiles = r.presentFiles().entries.sortedWith(compareBy({ it.value.order }, { it.key }))
        val current = attachmentDao.forItem(itemId).map { it to shaOf(it.uri) }
        val bySha = current.mapNotNull { (a, sha) -> sha?.let { it to a } }.toMap()
        for ((a, sha) in current) if (sha == null || sha !in r.presentFiles()) syncDao.deleteAttachmentRow(a.id)
        wantFiles.forEachIndexed { index, (sha, f) ->
            val ocr = r.value(FILE_OCR_PREFIX + sha) as? String
            val row = bySha[sha]
            if (row == null) {
                attachmentDao.insert(
                    AttachmentEntity(itemId = itemId, uri = localFileFor(sha), kind = f.kind, ocrText = ocr, sortOrder = index)
                )
            } else if (row.sortOrder != index || row.kind != f.kind || row.ocrText != ocr) {
                syncDao.updateAttachment(row.id, f.kind, index, ocr)
            }
        }
        return Applied(
            itemId = itemId,
            isNewLink = existing == null && updated.url != null && updated.contentType == "LINK",
            remindChanged = existing?.remindAt != updated.remindAt
        )
    }

    private suspend fun applyCollection(r: SyncRecord) {
        val existing = categoryDao.getById(r.id)
        if (r.isGone) {
            // Deleting a collection only un-files its saves; nothing is lost.
            if (existing != null) categoryDao.delete(r.id)
            return
        }
        val updated = CategoryEntity(
            id = r.id,
            name = (r.value(F_NAME) as? String)?.ifBlank { null } ?: existing?.name ?: "Collection",
            emoji = r.value(F_EMOJI) as? String ?: existing?.emoji ?: "📦",
            colorHex = r.value(F_COLOR) as? Long ?: existing?.colorHex ?: 0xFF8B5CF6,
            sortOrder = (r.value(F_ORDER) as? Long)?.toInt() ?: existing?.sortOrder ?: 0,
            parentId = (r.value(F_PARENT) as? String)?.takeIf { it != r.id && categoryDao.getById(it) != null },
            userCreated = r.value(F_USER) as? Boolean ?: true,
            createdAt = r.created,
            isLocked = r.value(F_LOCKED) as? Boolean ?: false
        )
        if (existing == null) categoryDao.upsert(updated)
        else if (updated != existing) categoryDao.update(updated)
    }

    /** This phone's copy of a file, or a placeholder until it arrives. */
    private suspend fun localFileFor(sha: String): String =
        syncDao.fileBySha(sha)?.localUri?.takeIf { uriExists(it) } ?: (PENDING_FILE_SCHEME + sha)

    private fun uriExists(uri: String): Boolean =
        !uri.startsWith("file:") || Uri.parse(uri).path?.let { File(it).exists() } == true
}
