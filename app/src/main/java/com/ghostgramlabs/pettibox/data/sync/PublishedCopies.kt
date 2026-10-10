package com.ghostgramlabs.pettibox.data.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What each device file in Drive said when last read (see
 * [DriveFileCleanup.Published]), kept in a file on this phone so the daily
 * clean-up only reads files that changed. Losing it costs a re-read, nothing more.
 */
class PublishedCopies(context: Context) {
    private val file = File(context.filesDir, "sync-published.json")

    fun load(): Map<String, DriveFileCleanup.Published> = runCatching {
        val root = JSONObject(file.readText())
        root.keys().asSequence().associateWith { name ->
            val o = root.getJSONObject(name)
            DriveFileCleanup.Published(o.getString("md5"), o.getJSONArray("refs").strings(), o.getJSONArray("live").strings())
        }
    }.getOrDefault(emptyMap())

    fun save(copies: Map<String, DriveFileCleanup.Published>) {
        val root = JSONObject()
        for ((name, p) in copies) {
            root.put(name, JSONObject().put("md5", p.md5).put("refs", JSONArray(p.refs.toList())).put("live", JSONArray(p.live.toList())))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun JSONArray.strings(): Set<String> = (0 until length()).map { getString(it) }.toSet()
}
