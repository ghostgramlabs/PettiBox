package com.ghostgramlabs.pettibox.data.sync

/*
 * The data model and merge rules of "Sync between devices". Pure Kotlin, no
 * Android: everything here is unit-tested, and extension/sync/model.js is a
 * line-for-line port checked against the same vectors (sync-spec/).
 *
 * Every record (a save or a collection) carries a causal context `vv`: for
 * each device, the newest of its writes this copy has seen. Each field keeps
 * the set of values written concurrently (usually one). Merging two copies
 * keeps a value if both copies have it, or if the other copy hasn't seen it
 * yet; a value one copy has seen and dropped was replaced on purpose. That
 * makes merging order-independent and repeatable, and it never drops an edit
 * that no one has seen: two devices editing the same field both survive until
 * someone picks one.
 */

/** One write: which device, and when (hybrid-clock ms; unique per device). */
data class Dot(val dev: String, val ts: Long) : Comparable<Dot> {
    override fun compareTo(other: Dot): Int =
        compareValuesBy(this, other, { it.ts }, { it.dev })
}

/** Causal context: device -> newest write of that device that has been seen. */
typealias Ctx = Map<String, Long>

fun Ctx.covers(dot: Dot): Boolean = (this[dot.dev] ?: 0L) >= dot.ts

fun maxCtx(a: Ctx, b: Ctx): Ctx {
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    val out = HashMap<String, Long>(a)
    for ((dev, ts) in b) out[dev] = maxOf(out[dev] ?: 0L, ts)
    return out
}

/**
 * A field value and the write that set it. [ctx] is only on "trash" values:
 * what the device had seen when it deleted, so an edit it hadn't seen wins.
 * Values are String, Long, Boolean or null.
 */
data class Head(val value: Any?, val dot: Dot, val ctx: Ctx? = null)

/** A tag on a save, added by [dot]. Shown as [name]; keyed by its lowercase form. */
data class TagDot(val name: String, val dot: Dot)

/** A file on a save (keyed by its sha256), added by [dot]. */
data class FileDot(val kind: String, val order: Long, val dot: Dot)

data class SyncRecord(
    /** [KIND_ITEM] or [KIND_COLLECTION]. */
    val kind: String,
    val id: String,
    val created: Long,
    val vv: Ctx,
    /** Field name -> concurrent values, sorted by dot. Never empty lists. */
    val fields: Map<String, List<Head>>,
    val tags: Map<String, List<TagDot>> = emptyMap(),
    val files: Map<String, List<FileDot>> = emptyMap(),
    /** Set when the record was permanently removed: what the remover had seen. */
    val gone: Ctx? = null
) {
    /** The value shown: the newest of the concurrent values. */
    fun value(field: String): Any? = fields[field]?.maxByOrNull { it.dot }?.value

    fun has(field: String): Boolean = fields[field]?.isNotEmpty() == true

    /** Other values of [field] written concurrently with the shown one, oldest first. */
    fun alternatives(field: String): List<Head> {
        val heads = fields[field] ?: return emptyList()
        val shown = heads.maxByOrNull { it.dot } ?: return emptyList()
        return heads.filter { it.dot != shown.dot && it.value != shown.value }
            .distinctBy { it.value }
    }

    /** Tags present now: lowercase key -> display name (from the newest add). */
    fun presentTags(): Map<String, String> =
        tags.filterValues { it.isNotEmpty() }.mapValues { (_, dots) -> dots.maxBy { it.dot }.name }

    /** Files present now: sha256 -> (kind, order) from the newest add. */
    fun presentFiles(): Map<String, FileDot> =
        files.filterValues { it.isNotEmpty() }.mapValues { (_, dots) -> dots.maxBy { it.dot } }

    /**
     * Every write that reflects something a person did, as opposed to work a
     * phone does by itself (text recognition, link details, read state).
     * These decide whether a delete still stands.
     */
    private fun userDots(excludeTrash: Boolean): Sequence<Dot> = sequence {
        for ((name, heads) in fields) {
            if (name in DERIVED_FIELDS || name.startsWith(FILE_OCR_PREFIX)) continue
            if (excludeTrash && name == F_TRASH) continue
            for (h in heads) yield(h.dot)
        }
        for (dots in tags.values) for (t in dots) yield(t.dot)
        for (dots in files.values) for (f in dots) yield(f.dot)
    }

    /** Permanently removed, and nothing has been done to it since that the remover hadn't seen. */
    val isGone: Boolean
        get() = gone != null && userDots(excludeTrash = false).all { gone.covers(it) }

    /**
     * In Recently deleted: the shown "trash" value is a deletion, and no edit
     * the deleting device hadn't seen has happened since. An edit made on
     * another device without knowing of the delete brings the save back.
     */
    val isTrashed: Boolean
        get() {
            val head = fields[F_TRASH]?.maxByOrNull { it.dot } ?: return false
            if (head.value == null) return false
            val ctx = head.ctx ?: return true
            return userDots(excludeTrash = true).all { ctx.covers(it) }
        }

    val trashedAt: Long? get() = if (isTrashed) value(F_TRASH) as? Long else null

    /** Newest write of any kind; drives the hybrid clock. */
    val maxTs: Long get() = vv.values.maxOrNull() ?: 0L

    /** True when some field has concurrent values that differ (shown as "Edited on 2 devices"). */
    val hasConflict: Boolean get() = fields.keys.any { alternatives(it).isNotEmpty() }

    companion object {
        const val KIND_ITEM = "item"
        const val KIND_COLLECTION = "col"

        // Save fields.
        const val F_TITLE = "title"
        const val F_URL = "url"
        const val F_NOTES = "notes"
        const val F_OCR = "ocr"
        const val F_META = "meta"
        const val F_CAT = "cat"
        const val F_TYPE = "type"
        const val F_SRC = "src"
        const val F_FAV = "fav"
        const val F_PIN = "pin"
        const val F_ARCH = "arch"
        const val F_REMIND = "remind"
        const val F_OPENED = "opened"
        const val F_TRASH = "trash"
        const val F_MAIN = "main"
        /** sha256 of the card picture (link preview, page screenshot), shared so other devices needn't make their own. */
        const val F_THUMB = "thumb"

        /** Per-file recognized text: "ocr:<sha256>". */
        const val FILE_OCR_PREFIX = "ocr:"

        // Collection fields.
        const val F_NAME = "name"
        const val F_EMOJI = "emoji"
        const val F_COLOR = "color"
        const val F_ORDER = "order"
        const val F_PARENT = "parent"
        const val F_USER = "user"
        const val F_LOCKED = "locked"

        /** Written by a phone on its own, so they never undo a delete. */
        val DERIVED_FIELDS = setOf(F_OCR, F_META, F_OPENED, F_THUMB)
    }
}

/**
 * Merges two copies of the same record. Commutative, associative and
 * idempotent: devices converge whatever order they sync in, and syncing
 * twice changes nothing.
 */
fun merge(a: SyncRecord, b: SyncRecord): SyncRecord {
    require(a.kind == b.kind && a.id == b.id) { "merging different records" }
    if (a == b) return a
    val fields = mergeKeyed(a.fields, a.vv, b.fields, b.vv, { it.dot }, ::tieKey)
    val tags = mergeKeyed(a.tags, a.vv, b.tags, b.vv, { it.dot }) { "t:" + it.name }
    val files = mergeKeyed(a.files, a.vv, b.files, b.vv, { it.dot }) { "f:" + it.kind + "|" + it.order }
    val gone = when {
        a.gone == null -> b.gone
        b.gone == null -> a.gone
        else -> maxCtx(a.gone, b.gone)
    }
    return SyncRecord(
        kind = a.kind,
        id = a.id,
        created = minOf(a.created, b.created),
        vv = maxCtx(a.vv, b.vv),
        fields = fields,
        tags = tags,
        files = files,
        gone = gone
    )
}

/**
 * For each key: keep an element if the other copy has it too, or if the
 * other copy hasn't seen its write. An element the other copy has seen but
 * dropped was replaced or removed there.
 */
private fun <T : Any> mergeKeyed(
    a: Map<String, List<T>>,
    aSeen: Ctx,
    b: Map<String, List<T>>,
    bSeen: Ctx,
    dotOf: (T) -> Dot,
    tie: (T) -> String
): Map<String, List<T>> {
    val out = HashMap<String, List<T>>()
    for (key in a.keys + b.keys) {
        val xs = a[key].orEmpty()
        val ys = b[key].orEmpty()
        val yDots = ys.mapTo(HashSet(), dotOf)
        val xDots = xs.mapTo(HashSet(), dotOf)
        val kept = LinkedHashMap<Dot, T>()
        for (x in xs) if (dotOf(x) in yDots || !bSeen.covers(dotOf(x))) kept[dotOf(x)] = x
        for (y in ys) {
            if (dotOf(y) in xDots || !aSeen.covers(dotOf(y))) {
                val dot = dotOf(y)
                val existing = kept[dot]
                // The same write can only carry one value; if two copies
                // disagree (corruption), pick one the same way everywhere,
                // including in the extension (see sync-spec: tie key).
                kept[dot] = if (existing == null) y else minOf(existing, y, compareBy(tie))
            }
        }
        if (kept.isNotEmpty()) out[key] = kept.values.sortedBy(dotOf)
    }
    return out
}

/** Implementation-independent order for two values claiming the same write (spec: tie key). */
private fun tieKey(h: Head): String {
    val v = when (val x = h.value) {
        null -> "null"
        is String -> "s:$x"
        is Boolean -> "b:$x"
        else -> "n:$x"
    }
    val ctx = h.ctx?.entries?.sortedBy { it.key }?.joinToString(",") { "${it.key}=${it.value}" } ?: "-"
    return "h:$v|$ctx"
}

/**
 * This device's own edits, applied to its merged copy. Each returns a new
 * record; [dot] is a fresh write of this device (from [HybridClock]).
 */
object SyncWrite {

    /** Sets [field] to [value], replacing every value this copy holds for it. */
    fun field(r: SyncRecord, field: String, value: Any?, dot: Dot, ctx: Ctx? = null): SyncRecord =
        r.copy(
            fields = r.fields + (field to listOf(Head(value, dot, ctx))),
            vv = r.vv + (dot.dev to maxOf(r.vv[dot.dev] ?: 0L, dot.ts))
        )

    /** Moves to (or out of) Recently deleted, remembering what this copy had seen. */
    fun trash(r: SyncRecord, deletedAt: Long?, dot: Dot): SyncRecord =
        field(r, SyncRecord.F_TRASH, deletedAt, dot, ctx = if (deletedAt != null) r.vv else null)

    /** Adds tag [key] (its lowercase form), shown as [name]. */
    fun addTag(r: SyncRecord, key: String, name: String, dot: Dot): SyncRecord {
        return r.copy(
            tags = r.tags + (key to (r.tags[key].orEmpty() + TagDot(name, dot)).sortedBy { it.dot }),
            vv = r.vv + (dot.dev to maxOf(r.vv[dot.dev] ?: 0L, dot.ts))
        )
    }

    /** Removes every add this copy has seen; an add elsewhere it hasn't seen survives. */
    fun removeTag(r: SyncRecord, key: String, dot: Dot): SyncRecord = r.copy(
        tags = r.tags - key,
        vv = r.vv + (dot.dev to maxOf(r.vv[dot.dev] ?: 0L, dot.ts))
    )

    fun addFile(r: SyncRecord, sha: String, kind: String, order: Long, dot: Dot): SyncRecord = r.copy(
        files = r.files + (sha to listOf(FileDot(kind, order, dot))),
        vv = r.vv + (dot.dev to maxOf(r.vv[dot.dev] ?: 0L, dot.ts))
    )

    fun removeFile(r: SyncRecord, sha: String, dot: Dot): SyncRecord = r.copy(
        files = r.files - sha,
        vv = r.vv + (dot.dev to maxOf(r.vv[dot.dev] ?: 0L, dot.ts))
    )

    /** Permanently removed here. */
    fun gone(r: SyncRecord, dot: Dot): SyncRecord {
        val vv = r.vv + (dot.dev to maxOf(r.vv[dot.dev] ?: 0L, dot.ts))
        return r.copy(vv = vv, gone = maxCtx(r.gone ?: emptyMap(), vv))
    }
}

/**
 * Hybrid logical clock: a new write is always later than the wall clock and
 * than every write this device has seen, so an edit made after seeing a
 * change wins over it even when this phone's clock runs behind.
 */
class HybridClock(private var last: Long, private val now: () -> Long = System::currentTimeMillis) {
    val current: Long get() = last

    fun observe(ts: Long) {
        if (ts > last) last = ts
    }

    fun next(): Long {
        last = maxOf(now(), last + 1)
        return last
    }
}

/**
 * What this device currently has for one record, as plain values. [fields]
 * covers every field this app version knows; fields only a newer version
 * knows are left alone.
 */
data class LocalRecord(
    val kind: String,
    val id: String,
    val created: Long,
    val fields: Map<String, Any?>,
    /** Lowercase key -> display name. */
    val tags: Map<String, String> = emptyMap(),
    /** sha256 -> (kind, order). */
    val files: Map<String, Pair<String, Long>> = emptyMap()
) {
    val trashed: Boolean get() = fields[SyncRecord.F_TRASH] != null
}

object SyncDiff {

    /**
     * Folds this device's current state of one record into its merged copy.
     * Returns the new copy, or null if nothing changed. [dot] is used for
     * every write it makes.
     *
     * - New here: every field, tag and file is written.
     * - Gone here (and not already gone in the copy): marked permanently removed.
     * - Copy says gone, but it's here and not in Recently deleted: someone
     *   restored it, so everything is written again and it comes back.
     * - Otherwise only what differs from the shown values is written, so a
     *   conflict the user hasn't settled stays open.
     */
    fun apply(meta: SyncRecord?, local: LocalRecord?, dot: Dot): SyncRecord? {
        if (local == null) {
            if (meta == null || meta.isGone) return null
            return SyncWrite.gone(meta, dot)
        }
        if (meta == null) return writeAll(empty(local), local, dot)
        if (meta.isGone) {
            // Moved to Recently deleted here because another device removed
            // it: expected, nothing to say.
            if (local.trashed) return null
            return writeAll(meta, local, dot)
        }
        var r: SyncRecord = meta
        var changed = false
        for ((name, value) in local.fields) {
            if (name == SyncRecord.F_TRASH) {
                // Compared with what's in effect: a delete undone by an
                // unseen edit reads as "not deleted" here too.
                if (meta.trashedAt == value) continue
                r = SyncWrite.trash(r, value as Long?, dot)
            } else {
                // A field this copy has never had counts as null.
                if (if (meta.has(name)) meta.value(name) == value else value == null) continue
                r = SyncWrite.field(r, name, value, dot)
            }
            changed = true
        }
        val present = meta.presentTags()
        for ((key, name) in local.tags) if (key !in present) {
            r = SyncWrite.addTag(r, key, name, dot); changed = true
        }
        for (key in present.keys) if (key !in local.tags) {
            r = SyncWrite.removeTag(r, key, dot); changed = true
        }
        val files = meta.presentFiles()
        for ((sha, kindOrder) in local.files) {
            val now = files[sha]
            if (now == null || now.kind != kindOrder.first || now.order != kindOrder.second) {
                r = SyncWrite.addFile(r, sha, kindOrder.first, kindOrder.second, dot); changed = true
            }
        }
        for (sha in files.keys) if (sha !in local.files) {
            r = SyncWrite.removeFile(r, sha, dot); changed = true
        }
        if (!changed) return null
        // Live only because an edit beat a delete: settle that now. Otherwise
        // undoing that edit here (say, removing the tag that was added) would
        // let the old delete take effect again.
        if (meta.gone != null) return writeAll(r, local, dot)
        if (meta.value(SyncRecord.F_TRASH) != null && !meta.isTrashed && !local.trashed && r.value(SyncRecord.F_TRASH) != null) {
            r = SyncWrite.trash(r, null, dot)
        }
        return r
    }

    private fun empty(local: LocalRecord) = SyncRecord(
        kind = local.kind, id = local.id, created = local.created, vv = emptyMap(), fields = emptyMap()
    )

    private fun writeAll(base: SyncRecord, local: LocalRecord, dot: Dot): SyncRecord {
        var r = base
        for ((name, value) in local.fields) {
            r = if (name == SyncRecord.F_TRASH) SyncWrite.trash(r, value as Long?, dot)
            else SyncWrite.field(r, name, value, dot)
        }
        for ((key, name) in local.tags) r = SyncWrite.addTag(r.copy(tags = r.tags - key), key, name, dot)
        for (key in r.tags.keys - local.tags.keys) r = SyncWrite.removeTag(r, key, dot)
        for ((sha, kindOrder) in local.files) r = SyncWrite.addFile(r, sha, kindOrder.first, kindOrder.second, dot)
        for (sha in r.files.keys - local.files.keys) r = SyncWrite.removeFile(r, sha, dot)
        return r
    }
}

/** Which of a device's 16 Drive files a record lives in (FNV-1a of the id). */
fun shardOf(id: String): Int {
    var h = 0x811c9dc5.toInt()
    for (b in id.toByteArray(Charsets.UTF_8)) {
        h = h xor (b.toInt() and 0xff)
        h *= 0x01000193
    }
    return (h ushr 28) and 0xf
}
