package com.ghostgramlabs.pettibox.data.sync

import org.json.JSONArray
import org.json.JSONObject

/** A device file or record that doesn't match the format. Nothing from it is applied. */
class SyncFormatException(message: String) : Exception(message)

/** One of a device's 16 files in Drive, decoded. */
data class DeviceShard(
    val format: Int,
    val device: String,
    val name: String,
    val shard: Int,
    val generated: Long,
    val records: List<SyncRecord>,
    /** The reset marker it was written under ("" before any reset). */
    val epoch: String = ""
)

/**
 * JSON for records and device files. The format is documented in
 * sync-spec/README.md and shared with the Chrome extension.
 */
object SyncCodec {
    const val FORMAT = 1

    /** Timestamps further ahead than this are corrupt, not a fast clock. */
    private const val MAX_FUTURE_MS = 10L * 365 * 24 * 60 * 60 * 1000

    /** Bounds shared with codec.js: larger is corrupt, not a big library. */
    const val MAX_SHARD_RECORDS = 200_000
    const val MAX_UNPACKED_BYTES = 256L * 1024 * 1024
    private const val MAX_ID = 200
    /** The largest whole number JavaScript holds exactly; the extension must read every time we write. */
    private const val MAX_STAMP = 9_007_199_254_740_991L

    /**
     * The type each known field must have (null is always allowed). Unknown
     * fields may hold any plain value and pass through untouched.
     */
    private val FIELD_TYPES: Map<String, Class<*>> = buildMap {
        for (f in listOf(
            SyncRecord.F_TITLE, SyncRecord.F_URL, SyncRecord.F_NOTES, SyncRecord.F_OCR, SyncRecord.F_META,
            SyncRecord.F_CAT, SyncRecord.F_TYPE, SyncRecord.F_SRC, SyncRecord.F_MAIN, SyncRecord.F_THUMB,
            SyncRecord.F_NAME, SyncRecord.F_EMOJI, SyncRecord.F_PARENT
        )) put(f, String::class.java)
        for (f in listOf(SyncRecord.F_FAV, SyncRecord.F_PIN, SyncRecord.F_ARCH, SyncRecord.F_USER, SyncRecord.F_LOCKED)) {
            put(f, java.lang.Boolean::class.java)
        }
        for (f in listOf(SyncRecord.F_REMIND, SyncRecord.F_OPENED, SyncRecord.F_TRASH, SyncRecord.F_COLOR, SyncRecord.F_ORDER)) {
            put(f, java.lang.Long::class.java)
        }
    }

    private fun typeOf(field: String): Class<*>? =
        FIELD_TYPES[field] ?: if (field.startsWith(SyncRecord.FILE_OCR_PREFIX)) String::class.java else null

    /**
     * [epoch]: the reset marker this file is written under ("" before any
     * reset). Readers skip files from another epoch, so nothing written before
     * a "Delete synced data" can come back after it.
     */
    fun encodeShard(device: String, name: String, shard: Int, generated: Long, records: List<SyncRecord>, epoch: String = ""): String =
        JSONObject()
            .put("format", FORMAT)
            .put("device", device)
            .put("name", name)
            .put("shard", shard)
            .put("generated", generated)
            .apply { if (epoch.isNotEmpty()) put("epoch", epoch) }
            .put("records", JSONArray().apply { records.sortedBy { it.kind + ":" + it.id }.forEach { put(encode(it)) } })
            .toString()

    /**
     * Checks a record from another device before anything of it is kept:
     * known fields have their type, and its clock covers every write in it
     * (so no write can hide past the future-date check, and merges stay sound).
     */
    fun validate(r: SyncRecord) {
        if (r.id.length > MAX_ID) throw SyncFormatException("id too long")
        if (r.created !in 0..MAX_STAMP) throw SyncFormatException("${r.id}: bad created time")
        fun covered(dot: Dot, where: String) {
            val seen = r.vv[dot.dev] ?: -1L
            if (seen < dot.ts) throw SyncFormatException("${r.id}: $where written outside its clock")
        }
        for ((name, heads) in r.fields) {
            val type = typeOf(name)
            for (h in heads) {
                covered(h.dot, name)
                val v = h.value
                if (type != null && v != null && !type.isInstance(v)) {
                    throw SyncFormatException("${r.id}.$name: not a ${type.simpleName.lowercase()}")
                }
            }
        }
        for ((key, dots) in r.tags) {
            if (key.length > MAX_ID) throw SyncFormatException("${r.id}: tag too long")
            for (t in dots) covered(t.dot, "tag $key")
        }
        for ((sha, dots) in r.files) {
            if (!Regex("^[0-9a-f]{1,64}$").matches(sha)) throw SyncFormatException("${r.id}: bad file name")
            for (f in dots) covered(f.dot, "file")
        }
    }

    /**
     * Decodes and validates a device file. Returns null for a newer format
     * (left alone for a newer app); throws [SyncFormatException] if it's
     * broken, so the caller skips the whole file.
     */
    fun decodeShard(text: String, now: Long = System.currentTimeMillis()): DeviceShard? {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw SyncFormatException("not JSON")
        }
        val format = root.optInt("format", -1)
        if (format > FORMAT) return null
        if (format != FORMAT) throw SyncFormatException("bad format $format")
        val device = root.optString("device").takeIf { it.isNotBlank() } ?: throw SyncFormatException("no device")
        val arr = root.optJSONArray("records") ?: throw SyncFormatException("no records")
        if (arr.length() > MAX_SHARD_RECORDS) throw SyncFormatException("too many records")
        val shard = (root.opt("shard") as? Int)?.takeIf { it in 0..15 } ?: throw SyncFormatException("bad shard ${root.opt("shard")}")
        val records = ArrayList<SyncRecord>(arr.length())
        val limit = now + MAX_FUTURE_MS
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw SyncFormatException("record $i not an object")
            val r = decode(o)
            validate(r)
            if (r.maxTs > limit) throw SyncFormatException("record ${r.id} is dated far in the future")
            if (!seen.add(r.kind + ":" + r.id)) throw SyncFormatException("record ${r.id} appears twice")
            records += r
        }
        return DeviceShard(
            format = format,
            device = device,
            name = root.optString("name", ""),
            shard = shard,
            generated = root.optLong("generated", 0L),
            records = records,
            epoch = root.optString("epoch", "")
        )
    }

    fun encode(r: SyncRecord): JSONObject {
        val o = JSONObject()
            .put("k", r.kind)
            .put("id", r.id)
            .put("c", r.created)
            .put("vv", ctx(r.vv))
        o.put("f", JSONObject().apply {
            for ((name, heads) in r.fields) put(name, JSONArray().apply {
                for (h in heads) put(JSONObject().apply {
                    put("v", h.value ?: JSONObject.NULL)
                    put("t", h.dot.ts)
                    put("d", h.dot.dev)
                    if (h.ctx != null) put("x", ctx(h.ctx))
                })
            })
        })
        if (r.tags.isNotEmpty()) o.put("tags", JSONObject().apply {
            for ((key, dots) in r.tags) put(key, JSONArray().apply {
                for (t in dots) put(JSONObject().put("n", t.name).put("t", t.dot.ts).put("d", t.dot.dev))
            })
        })
        if (r.files.isNotEmpty()) o.put("att", JSONObject().apply {
            for ((sha, dots) in r.files) put(sha, JSONArray().apply {
                for (f in dots) put(
                    JSONObject().put("k", f.kind).put("o", f.order).put("t", f.dot.ts).put("d", f.dot.dev)
                )
            })
        })
        if (r.gone != null) o.put("gone", ctx(r.gone))
        return o
    }

    fun encodeString(r: SyncRecord): String = encode(r).toString()

    fun decodeString(text: String): SyncRecord = decode(JSONObject(text))

    fun decode(o: JSONObject): SyncRecord {
        val kind = o.optString("k")
        if (kind != SyncRecord.KIND_ITEM && kind != SyncRecord.KIND_COLLECTION) {
            throw SyncFormatException("unknown kind '$kind'")
        }
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: throw SyncFormatException("record without id")
        val vv = ctxOf(o.optJSONObject("vv") ?: throw SyncFormatException("$id: no vv"))
        val fields = HashMap<String, List<Head>>()
        o.optJSONObject("f")?.let { f ->
            for (name in f.keys()) {
                val arr = f.optJSONArray(name) ?: throw SyncFormatException("$id.$name: not a list")
                val heads = (0 until arr.length()).map { i ->
                    val h = arr.optJSONObject(i) ?: throw SyncFormatException("$id.$name[$i]")
                    Head(
                        value = valueOf(h.opt("v"), "$id.$name"),
                        dot = dotOf(h, "$id.$name"),
                        ctx = h.optJSONObject("x")?.let(::ctxOf)
                    )
                }
                if (heads.isNotEmpty()) fields[name] = heads.sortedBy { it.dot }
            }
        }
        val tags = HashMap<String, List<TagDot>>()
        o.optJSONObject("tags")?.let { t ->
            for (key in t.keys()) {
                val arr = t.optJSONArray(key) ?: throw SyncFormatException("$id tag $key")
                val dots = (0 until arr.length()).map { i ->
                    val e = arr.optJSONObject(i) ?: throw SyncFormatException("$id tag $key[$i]")
                    TagDot(e.optString("n", key), dotOf(e, "$id tag $key"))
                }
                if (dots.isNotEmpty()) tags[key] = dots.sortedBy { it.dot }
            }
        }
        val files = HashMap<String, List<FileDot>>()
        o.optJSONObject("att")?.let { a ->
            for (sha in a.keys()) {
                val arr = a.optJSONArray(sha) ?: throw SyncFormatException("$id file $sha")
                val dots = (0 until arr.length()).map { i ->
                    val e = arr.optJSONObject(i) ?: throw SyncFormatException("$id file $sha[$i]")
                    FileDot(e.optString("k", "FILE"), e.optLong("o", 0L), dotOf(e, "$id file $sha"))
                }
                if (dots.isNotEmpty()) files[sha] = dots.sortedBy { it.dot }
            }
        }
        return SyncRecord(
            kind = kind,
            id = id,
            created = o.optLong("c", 0L),
            vv = vv,
            fields = fields,
            tags = tags,
            files = files,
            gone = o.optJSONObject("gone")?.let(::ctxOf)
        )
    }

    private fun ctx(c: Ctx): JSONObject = JSONObject().apply { for ((dev, ts) in c) put(dev, ts) }

    /** A time: a whole, non-negative number both apps can hold exactly; null if not. */
    private fun stampOf(v: Any?): Long? = when (v) {
        is Int, is Long -> (v as Number).toLong()
        is Double -> if (v % 1.0 == 0.0) v.toLong() else null
        else -> null
    }?.takeIf { it in 0..MAX_STAMP }

    private fun ctxOf(o: JSONObject): Ctx {
        val out = HashMap<String, Long>()
        for (dev in o.keys()) {
            val ts = stampOf(o.opt(dev))
            if (ts == null || dev.isBlank()) throw SyncFormatException("bad clock for $dev")
            out[dev] = ts
        }
        return out
    }

    private fun dotOf(o: JSONObject, where: String): Dot {
        val ts = stampOf(o.opt("t"))
        val dev = o.optString("d")
        if (ts == null || dev.isBlank()) throw SyncFormatException("$where: bad write stamp")
        return Dot(dev, ts)
    }

    /** Field values are strings, whole numbers, booleans or null. */
    private fun valueOf(v: Any?, where: String): Any? = when (v) {
        null, JSONObject.NULL -> null
        is String, is Boolean -> v
        // Whole numbers only, within what the extension holds exactly.
        is Int, is Long -> (v as Number).toLong().also { if (it !in -MAX_STAMP..MAX_STAMP) throw SyncFormatException("$where: number too large") }
        is Double -> if (v % 1.0 == 0.0 && kotlin.math.abs(v) <= MAX_STAMP.toDouble()) v.toLong() else throw SyncFormatException("$where: not a safe whole number")
        is Number -> throw SyncFormatException("$where: number too large")
        else -> throw SyncFormatException("$where: unsupported value")
    }
}
