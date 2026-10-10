package com.ghostgramlabs.pettibox.data.sync

/**
 * Removing shared photos and files nothing uses any more, and stripping
 * long-settled deletions down to their bare markers. Pure rules, no Drive or
 * database.
 *
 * The rule both rest on: **only what every device has said it no longer
 * needs.** Every device publishes its whole copy of the library in Drive, so
 * each device's own copy is read separately (not just the merged view). A
 * device that is offline, behind, or hasn't heard of a deletion still lists
 * the file or the save in its copy, and that holds the removal back for as
 * long as it is listed in Settings. Removing a device there is the only way
 * its copy stops counting.
 *
 * Files then go in two reversible steps:
 * 1. Unused by every copy (and by this phone) for [GRACE_MS] in a row: moved
 *    into PettiBox/Sync/files-removed (not Drive's trash, which a person might
 *    empty). Any app that needs it moves it straight back.
 * 2. In there for another [GRACE_MS], still unused by every copy: deleted.
 */
object DriveFileCleanup {
    const val GRACE_MS = 30L * 24 * 60 * 60 * 1000

    /** Shared files are named "<sha256>.<ext>"; anything else is left alone. */
    private val NAME = Regex("^([0-9a-f]{64})(\\.[A-Za-z0-9]{1,10})?$")

    fun shaOfName(name: String): String? = NAME.matchEntire(name)?.groupValues?.get(1)

    /** Every file any save still uses. */
    fun referenced(records: Sequence<SyncRecord>): Set<String> {
        val out = HashSet<String>()
        for (r in records) {
            if (r.kind != SyncRecord.KIND_ITEM || r.isGone) continue
            out += r.files.filterValues { it.isNotEmpty() }.keys
            for (field in listOf(SyncRecord.F_MAIN, SyncRecord.F_THUMB)) {
                r.fields[field].orEmpty().mapNotNullTo(out) { it.value as? String }
            }
        }
        return out
    }

    /** What one device file in Drive says, remembered by its name and checksum. */
    data class Published(
        val md5: String,
        /** Files its records use. */
        val refs: Set<String>,
        /** Records it still holds as not removed for good. */
        val live: Set<String>
    )

    fun publishedOf(md5: String, records: List<SyncRecord>) = Published(
        md5 = md5,
        refs = referenced(records.asSequence()),
        live = records.filter { !it.isGone }.map { it.kind + ":" + it.id }.toSet()
    )

    data class Plan(
        /** Drive file ids to move from files/ into files-removed. */
        val quarantine: List<String>,
        /** Drive file ids in files-removed to move back: something uses them again. */
        val restore: List<String>,
        /** Drive file ids in files-removed to delete. */
        val purge: List<String>,
        /** sha256 -> first time seen unused, for files still in files/. */
        val unusedSince: Map<String, Long>,
        /** sha256 -> when it went into files-removed. */
        val quarantinedAt: Map<String, Long>
    )

    /**
     * [stored] and [removed]: Drive file id -> name in files/ and in
     * files-removed. [used]: every file any device's copy (or this phone)
     * uses. A file used again starts over; one gone from Drive is forgotten.
     */
    fun plan(
        stored: Map<String, String>,
        removed: Map<String, String>,
        used: Set<String>,
        unusedSince: Map<String, Long>,
        quarantinedAt: Map<String, Long>,
        now: Long
    ): Plan {
        val quarantine = ArrayList<String>()
        val restore = ArrayList<String>()
        val purge = ArrayList<String>()
        val waiting = HashMap<String, Long>()
        val held = HashMap<String, Long>()
        for ((id, name) in stored) {
            val sha = shaOfName(name) ?: continue
            if (sha in used) continue
            val since = unusedSince[sha]?.coerceAtMost(now) ?: now
            if (now - since >= GRACE_MS) {
                quarantine += id
                held[sha] = now
            } else {
                waiting[sha] = since
            }
        }
        for ((id, name) in removed) {
            val sha = shaOfName(name) ?: continue
            if (sha in used) {
                restore += id
                continue
            }
            val since = quarantinedAt[sha]?.coerceAtMost(now) ?: now
            if (now - since >= GRACE_MS) purge += id else held[sha] = since
        }
        return Plan(quarantine, restore, purge, waiting, held)
    }

    /**
     * Whether a record removed for good can be stripped to its bare marker
     * (no title, note, link or files left in anyone's Drive): it has been gone
     * for [GRACE_MS], and no other device's copy of its file still holds it as
     * not removed. [othersHolding]: the "kind:id" of records some other
     * device's copy still holds, for the record's file.
     */
    fun compactable(r: SyncRecord, othersHolding: Set<String>, now: Long): Boolean {
        if (!r.isGone) return false
        if (r.fields.isEmpty() && r.tags.isEmpty() && r.files.isEmpty()) return false // already bare
        val goneAt = r.gone?.values?.maxOrNull() ?: return false
        if (now - goneAt < GRACE_MS) return false
        return (r.kind + ":" + r.id) !in othersHolding
    }
}
