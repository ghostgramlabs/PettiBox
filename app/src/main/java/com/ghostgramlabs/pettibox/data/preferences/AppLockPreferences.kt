package com.ghostgramlabs.pettibox.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

    val enabled: Flow<Boolean> = context.appLockDataStore.data.map { it[enabledKey] ?: false }

    suspend fun setEnabled(enabled: Boolean) {
        context.appLockDataStore.edit { it[enabledKey] = enabled }
    }
}
