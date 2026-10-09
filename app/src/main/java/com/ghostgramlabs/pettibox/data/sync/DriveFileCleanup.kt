package com.ghostgramlabs.pettibox.data.sync

/**
 * Which photos, PDFs and files in "PettiBox/Sync/files/" nothing uses any more,
 * so they can be removed from Google Drive. Pure rules, no Drive or database.
 *
 * A file goes only when all of these hold:
 * - No save in the merged library uses it: not its files, its main file or its
 *   card picture, in any version (an "edited on 2 devices" value counts too),
 *   and saves in Recently deleted count.
 * - That library came from a round that read every device's copy cleanly
 *   (the caller checks).
 * - It has stayed unused for [GRACE_MS] in a row, so a restore, or a device
 *   that hasn't synced for a while, has time to claim it again.
 *
 * Phones keep their own copies and upload any file Drive lacks, so a file
 * removed here comes back if a save turns out to use it after all.
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

    data class Plan(
        /** Drive file ids to remove now. */
        val remove: List<String>,
        /** sha256 -> first time seen unused, for the files still waiting. */
        val unusedSince: Map<String, Long>
    )

    /**
     * [stored]: Drive file name -> id. [unusedSince]: from the last run. A file
     * used again starts over; one that has disappeared from Drive is forgotten.
     */
    fun plan(stored: Map<String, String>, used: Set<String>, unusedSince: Map<String, Long>, now: Long): Plan {
        val remove = ArrayList<String>()
        val waiting = HashMap<String, Long>()
        for ((name, id) in stored) {
            val sha = shaOfName(name) ?: continue
            if (sha in used) continue
            val since = unusedSince[sha]?.coerceAtMost(now) ?: now
            if (now - since >= GRACE_MS) remove += id else waiting[sha] = since
        }
        return Plan(remove, waiting)
    }
}
