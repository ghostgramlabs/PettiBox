package com.ghostgramlabs.pettibox.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appLockDataStore by preferencesDataStore(name = "app_lock_preferences")

/** Whether PettiBox asks for fingerprint / face / screen-lock PIN on open. Off by default. */
@Singleton
class AppLockPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val enabledKey = booleanPreferencesKey("app_lock_enabled")
    private val lockAfterKey = longPreferencesKey("app_lock_after_ms")

    val enabled: Flow<Boolean> = context.appLockDataStore.data.map { it[enabledKey] ?: false }

    /** How long PettiBox may sit in the background before it locks again. */
    val lockAfterMs: Flow<Long> = context.appLockDataStore.data.map { it[lockAfterKey] ?: DEFAULT_LOCK_AFTER_MS }

    suspend fun setEnabled(enabled: Boolean) {
        context.appLockDataStore.edit { it[enabledKey] = enabled }
    }

    suspend fun setLockAfterMs(ms: Long) {
        context.appLockDataStore.edit { it[lockAfterKey] = ms }
    }

    private val widgetTitlesUntilKey = longPreferencesKey("widget_titles_visible_until")

    /**
     * Wall-clock time until which the widget may show titles while App lock
     * is on: Long.MAX_VALUE while PettiBox is unlocked and open, the relock
     * deadline once it's been left, 0 when locked. Only a display hint for
     * the widget; the app's own lock never reads it.
     */
    val widgetTitlesVisibleUntil: Flow<Long> =
        context.appLockDataStore.data.map { it[widgetTitlesUntilKey] ?: 0L }

    suspend fun setWidgetTitlesVisibleUntil(until: Long) {
        context.appLockDataStore.edit { it[widgetTitlesUntilKey] = until }
    }

    companion object {
        const val DEFAULT_LOCK_AFTER_MS = 60_000L

        /** Label → delay. "Immediately" still survives the unlock prompt itself. */
        val LOCK_AFTER_OPTIONS: List<Pair<String, Long>> = listOf(
            "Immediately" to 0L,
            "1 min" to 60_000L,
            "5 min" to 5 * 60_000L,
            "15 min" to 15 * 60_000L
        )
    }
}
