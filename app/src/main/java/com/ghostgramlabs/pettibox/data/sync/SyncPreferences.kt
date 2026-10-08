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
    MANUAL("Manual only", 0);
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
    val progress: String
)

@Singleton
class SyncPreferences(context: Context, fileName: String) {

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
            progress = p[progressKey].orEmpty()
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
    suspend fun deviceId(): String {
        store.data.first()[deviceIdKey]?.let { return it }
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val id = bytes.joinToString("") { "%02x".format(it) }
        var kept = id
        store.edit { p -> kept = p[deviceIdKey] ?: id.also { p[deviceIdKey] = it } }
        return kept
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
