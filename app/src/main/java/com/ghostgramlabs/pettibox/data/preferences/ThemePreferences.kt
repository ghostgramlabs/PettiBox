package com.ghostgramlabs.pettibox.data.preferences

import com.ghostgramlabs.pettibox.util.AppText

import com.ghostgramlabs.pettibox.R

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    fun label(): String = AppText.getString(
        when (this) {
            SYSTEM -> R.string.theme_system
            LIGHT -> R.string.theme_light
            DARK -> R.string.theme_dark
        }
    )
}

private val Context.themeDataStore by preferencesDataStore(name = "theme_preferences")

@Singleton
class ThemePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val themeModeKey = stringPreferencesKey("theme_mode")

    val mode: Flow<ThemeMode> = context.themeDataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeModeKey] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    suspend fun setMode(mode: ThemeMode) {
        context.themeDataStore.edit { prefs ->
            prefs[themeModeKey] = mode.name
        }
    }
}
