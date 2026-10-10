package com.ghostgramlabs.pettibox.data.sync

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton


/**
 * How often to sync in the background (15 minutes is Android's shortest). [MANUAL]
 * syncs only on "Sync now". Whatever the choice, the app also syncs every
 * minute while it's open, and about a minute after an edit.
 */
enum class SyncInterval(val label: String, val minutes: Long) {
    MINUTES_15("15 min", 15),
    MINUTES_30("30 min", 30),
    HOUR_1("1 hour", 60),
    HOURS_3("3 hours", 180),
    DAILY("Daily", 24 * 60),
    WEEKLY("Weekly", 7 * 24 * 60),
    // No background syncing; the app still syncs while open and after edits.
    MANUAL("Only while in use", 0);
}

data class SyncStatus(
    val enabled: Boolean,
    val interval: SyncInterval,
    val filesOnWifiOnly: Boolean,
    val deviceName: String,
    val accountEmail: String,
    val lastSyncAt: Long,
    /** Plain-words reason the last round failed, "" when it didn't. */
    val lastError: String,
    val lastErrorAt: Long,
    val needsReconnect: Boolean,
    /** Saves another device wants to remove or archive, waiting for the user's OK (0 = none). */
    val pendingReview: Int,
    /** Progress of a running round, "" when idle. */
    val progress: String,
    /** Why photos and files aren't all synced, "" when they are. */
    val filesProblem: String = ""
)

@Singleton
class SyncPreferences(context: Context, fileName: String) {

    /**
     * Holds this install's device id where Android never backs up or moves
     * files (no_backup). Phone-to-phone transfer copies the settings but not
     * this, so a copy can tell it is one (see [deviceId]).
     */
    private val installMark = java.io.File(context.noBackupFilesDir, "$fileName.install")

    @Inject constructor(@ApplicationContext context: Context) : this(context, "sync_preferences")

    // One instance per file per process (DataStore's rule): Hilt keeps this a
    // singleton; tests pass their own file names.
    private val store = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(fileName) }
    )
    private val enabledKey = booleanPreferencesKey("enabled")
    private val intervalKey = stringPreferencesKey("interval")
    private val wifiOnlyKey = booleanPreferencesKey("files_wifi_only")
    private val deviceIdKey = stringPreferencesKey("device_id")
    private val deviceNameKey = stringPreferencesKey("device_name")
    private val accountKey = stringPreferencesKey("account_email")
    private val clockKey = longPreferencesKey("clock")
    private val lastSyncKey = longPreferencesKey("last_sync_at")
    private val lastErrorKey = stringPreferencesKey("last_error")
    private val lastErrorAtKey = longPreferencesKey("last_error_at")
    private val reconnectKey = booleanPreferencesKey("needs_reconnect")
    private val reviewKey = intPreferencesKey("pending_review")
    private val reviewDecisionKey = stringPreferencesKey("review_decision")
    private val progressKey = stringPreferencesKey("progress")
    private val md5Key = stringPreferencesKey("seen_md5")
    private val backedUpKey = booleanPreferencesKey("first_backup_done")
    private val knownResetKey = stringPreferencesKey("known_reset")

    /**
     * The reset marker as it was when sync was turned on here (null: not read
     * yet). A different marker later means someone deleted the synced data.
     * Compared by content, not clocks, so a phone's wrong time can't fool it.
     */
    suspend fun knownReset(): String? = store.data.first()[knownResetKey]

    suspend fun setKnownReset(marker: String) = store.edit { it[knownResetKey] = marker }

    suspend fun clearKnownReset() = store.edit { it.remove(knownResetKey) }

    val status: Flow<SyncStatus> = store.data.map { p ->
        SyncStatus(
            enabled = p[enabledKey] ?: false,
            interval = p[intervalKey]?.let { runCatching { SyncInterval.valueOf(it) }.getOrNull() } ?: SyncInterval.HOURS_3,
            filesOnWifiOnly = p[wifiOnlyKey] ?: true,
            deviceName = p[deviceNameKey] ?: defaultDeviceName(),
            accountEmail = p[accountKey].orEmpty(),
            lastSyncAt = p[lastSyncKey] ?: 0L,
            lastError = p[lastErrorKey].orEmpty(),
            lastErrorAt = p[lastErrorAtKey] ?: 0L,
            needsReconnect = p[reconnectKey] ?: false,
            pendingReview = p[reviewKey] ?: 0,
            progress = p[progressKey].orEmpty(),
            filesProblem = p[filesProblemKey].orEmpty()
        )
    }

    val enabled: Flow<Boolean> = store.data.map { it[enabledKey] ?: false }

    suspend fun isEnabled(): Boolean = enabled.first()

    suspend fun setEnabled(on: Boolean) = store.edit { it[enabledKey] = on }

    suspend fun setInterval(interval: SyncInterval) = store.edit { it[intervalKey] = interval.name }

    suspend fun setFilesOnWifiOnly(on: Boolean) = store.edit { it[wifiOnlyKey] = on }

    suspend fun setDeviceName(name: String) = store.edit { it[deviceNameKey] = name.trim().take(40) }

    suspend fun setAccountEmail(email: String) = store.edit { it[accountKey] = email }

    /** This install's id, made once: 16 random hex characters. */
    suspend fun deviceId(): String = ownDeviceId().first

    /**
     * This device's id, and whether it was just replaced because these
     * settings were copied from another phone (a phone-to-phone transfer).
     * Two devices writing under one id would overwrite each other's files in
     * Drive, so a copy takes a new id and carries on as a device of its own.
     */
    suspend fun ownDeviceId(): Pair<String, Boolean> {
        val p = store.data.first()
        val id = p[deviceIdKey]
        val mark = runCatching { installMark.readText().trim() }.getOrNull()
        if (id != null && mark == id) return id to false
        if (id != null && mark == null && p[installMarkedKey] != true) {
            // Set up before installs were marked: this is the original. Mark it.
            writeMark(id)
            store.edit { it[installMarkedKey] = true }
            return id to false
        }
        val fresh = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        store.edit {
            it[deviceIdKey] = fresh
            it[installMarkedKey] = true
        }
        writeMark(fresh)
        return fresh to (id != null)
    }

    private fun writeMark(id: String) {
        runCatching {
            installMark.parentFile?.mkdirs()
            installMark.writeText(id)
        }
    }

    suspend fun deviceName(): String = store.data.first()[deviceNameKey] ?: defaultDeviceName()

    /** Newest write timestamp this device has made or seen (the hybrid clock). */
    suspend fun clock(): Long = store.data.first()[clockKey] ?: 0L

    suspend fun saveClock(ts: Long) = store.edit { p -> p[clockKey] = maxOf(p[clockKey] ?: 0L, ts) }

    suspend fun recordSuccess(at: Long = System.currentTimeMillis(), keepError: Boolean = false) = store.edit {
        it[lastSyncKey] = at
        if (!keepError) {
            it[lastErrorKey] = ""
            it[lastErrorAtKey] = 0L
        }
        it[reconnectKey] = false
    }

    private val choiceKey = stringPreferencesKey("needs_choice")
    private val choiceIndexedKey = booleanPreferencesKey("needs_choice_indexed")

    /** Sync ids of saves edited on two devices, waiting for someone to pick a version. */
    val needingChoice: Flow<Set<String>> = store.data.map { p -> parseIds(p[choiceKey]) }

    /** Adds and removes ids in one write. */
    suspend fun updateNeedingChoice(add: Collection<String>, remove: Collection<String>) {
        if (add.isEmpty() && remove.isEmpty()) return
        store.edit { p ->
            val ids = parseIds(p[choiceKey]).toMutableSet()
            ids.removeAll(remove.toSet())
            ids.addAll(add)
            p[choiceKey] = org.json.JSONArray(ids.toList()).toString()
        }
    }

    suspend fun needingChoiceIndexed(): Boolean = store.data.first()[choiceIndexedKey] ?: false

    suspend fun markNeedingChoiceIndexed() = store.edit { it[choiceIndexedKey] = true }

    private fun parseIds(raw: String?): Set<String> = runCatching {
        val a = org.json.JSONArray(raw ?: return emptySet())
        (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    private val cleanRoundKey = longPreferencesKey("clean_round_at")
    private val unusedFilesKey = stringPreferencesKey("unused_files")
    private val fileCleanupKey = longPreferencesKey("file_cleanup_at")
    private val quarantinedKey = stringPreferencesKey("quarantined_files")

    /** Files in files-removed: sha256 -> when they went in. */
    suspend fun quarantinedFiles(): Map<String, Long> = runCatching {
        val o = JSONObject(store.data.first()[quarantinedKey] ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())

    suspend fun setQuarantinedFiles(files: Map<String, Long>) = store.edit { it[quarantinedKey] = JSONObject(files).toString() }

    /** When a round last read every device's copy without skipping any (0: the last one didn't). */
    suspend fun cleanRoundAt(): Long = store.data.first()[cleanRoundKey] ?: 0L
    suspend fun setCleanRoundAt(at: Long) = store.edit { it[cleanRoundKey] = at }

    /** Files in Drive nothing uses: sha256 -> first time seen unused. */
    suspend fun unusedFiles(): Map<String, Long> = runCatching {
        val o = JSONObject(store.data.first()[unusedFilesKey] ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())

    suspend fun setUnusedFiles(files: Map<String, Long>) = store.edit { it[unusedFilesKey] = JSONObject(files).toString() }

    suspend fun fileCleanupAt(): Long = store.data.first()[fileCleanupKey] ?: 0L
    suspend fun setFileCleanupAt(at: Long) = store.edit { it[fileCleanupKey] = at }

    private val uploadSessionsKey = stringPreferencesKey("upload_sessions")
    private val filesProblemKey = stringPreferencesKey("files_problem")

    /** Why photos and files aren't all synced right now, in plain words ("" when they are). */
    suspend fun setFilesProblem(text: String) = store.edit { if (text.isEmpty()) it.remove(filesProblemKey) else it[filesProblemKey] = text }

    /** A big file's unfinished resumable upload (sha256 -> session address), so it carries on. */
    suspend fun uploadSession(sha: String): String? = runCatching {
        JSONObject(store.data.first()[uploadSessionsKey] ?: return null).optString(sha).ifBlank { null }
    }.getOrNull()

    suspend fun setUploadSession(sha: String, uri: String?) = store.edit { p ->
        val o = runCatching { JSONObject(p[uploadSessionsKey] ?: "{}") }.getOrDefault(JSONObject())
        if (uri == null) o.remove(sha) else o.put(sha, uri)
        p[uploadSessionsKey] = o.toString()
    }

    private val installMarkedKey = booleanPreferencesKey("install_marked")
    private val reviewKeyKey = stringPreferencesKey("review_key")
    private val followUpsKey = stringPreferencesKey("pending_follow_ups")

    /** Which saves the pending removal review is about (sorted ids), so a choice applies to that set only. */
    suspend fun reviewKey(): String = store.data.first()[reviewKeyKey].orEmpty()
    suspend fun setReviewKey(key: String) = store.edit { it[reviewKeyKey] = key }

    /** Saves whose reminders and link pictures still need setting up after a round applied them. */
    suspend fun pendingFollowUps(): List<String> = parseIds(store.data.first()[followUpsKey]).toList()
    suspend fun setPendingFollowUps(ids: List<String>) = store.edit {
        if (ids.isEmpty()) it.remove(followUpsKey) else it[followUpsKey] = org.json.JSONArray(ids).toString()
    }

    private val devicesKey = stringPreferencesKey("devices")

    /** Other devices seen in Drive: id -> (name, last time their files changed). */
    val devices: Flow<Map<String, Pair<String, Long>>> = store.data.map { p -> parseDevices(p[devicesKey]) }

    suspend fun rememberDevices(seen: Map<String, Pair<String, Long>>) = store.edit { p ->
        val all = parseDevices(p[devicesKey]).toMutableMap()
        for ((id, v) in seen) {
            val old = all[id]
            all[id] = v.first.ifBlank { old?.first.orEmpty() } to maxOf(v.second, old?.second ?: 0L)
        }
        p[devicesKey] = JSONObject().apply {
            for ((id, v) in all) put(id, JSONObject().put("name", v.first).put("seen", v.second))
        }.toString()
    }

    suspend fun forgetDevice(id: String) = store.edit { p ->
        val all = parseDevices(p[devicesKey]) - id
        p[devicesKey] = JSONObject().apply {
            for ((k, v) in all) put(k, JSONObject().put("name", v.first).put("seen", v.second))
        }.toString()
    }

    private fun parseDevices(raw: String?): Map<String, Pair<String, Long>> = runCatching {
        val o = JSONObject(raw ?: return emptyMap())
        o.keys().asSequence().associateWith { k ->
            val d = o.getJSONObject(k)
            d.optString("name") to d.optLong("seen")
        }
    }.getOrDefault(emptyMap())

    suspend fun recordError(message: String, at: Long = System.currentTimeMillis()) = store.edit {
        it[lastErrorKey] = message
        it[lastErrorAtKey] = at
    }

    suspend fun setNeedsReconnect(on: Boolean) = store.edit { it[reconnectKey] = on }

    suspend fun setProgress(text: String) = store.edit { it[progressKey] = text }

    suspend fun setPendingReview(count: Int) = store.edit { it[reviewKey] = count }

    /** "apply" or "keep" from the review prompt, consumed by the next round. */
    suspend fun setReviewDecision(decision: String) = store.edit { it[reviewDecisionKey] = decision }

    suspend fun takeReviewDecision(): String? {
        var decision: String? = null
        store.edit { p ->
            decision = p[reviewDecisionKey]?.takeIf { it.isNotBlank() }
            p[reviewDecisionKey] = ""
        }
        return decision
    }

    /** Drive md5 of every other device's file as last read, so unchanged files aren't downloaded again. */
    suspend fun seenMd5(): Map<String, String> {
        val raw = store.data.first()[md5Key] ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    suspend fun saveSeenMd5(map: Map<String, String>) = store.edit { it[md5Key] = JSONObject(map).toString() }

    suspend fun firstBackupDone(): Boolean = store.data.first()[backedUpKey] ?: false

    suspend fun markFirstBackupDone() = store.edit { it[backedUpKey] = true }

    /** Turning sync off forgets what was read from Drive, so turning it on again reads everything. */
    suspend fun resetForDisable() = store.edit {
        it.remove(md5Key)
        // Turning sync on again (perhaps with another Google account) starts a
        // new binding: nothing from the old one (upload sessions included) carries over.
        it.remove(accountKey)
        it.remove(reviewKeyKey)
        it.remove(uploadSessionsKey)
        it.remove(filesProblemKey)
        it.remove(cleanRoundKey)
        it.remove(unusedFilesKey)
        it[reviewKey] = 0
        it[reviewDecisionKey] = ""
        it[progressKey] = ""
    }

    private fun defaultDeviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }
}
