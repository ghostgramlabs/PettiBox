package com.ghostgramlabs.pettibox.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.articleDataStore by preferencesDataStore(name = "article_preferences")

@Singleton
class ArticlePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keepOfflineCopiesKey = booleanPreferencesKey("keep_offline_copies")
    private val readerTextZoomKey = intPreferencesKey("reader_text_zoom")

    /** On by default: saving a link is already asking to read it later. */
    val keepOfflineCopies: Flow<Boolean> = context.articleDataStore.data.map { prefs ->
        prefs[keepOfflineCopiesKey] ?: true
    }

    /** Reader font size as a WebView text zoom percentage. */
    val readerTextZoom: Flow<Int> = context.articleDataStore.data.map { prefs ->
        prefs[readerTextZoomKey]?.coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM) ?: DEFAULT_TEXT_ZOOM
    }

    suspend fun setKeepOfflineCopies(enabled: Boolean) {
        context.articleDataStore.edit { it[keepOfflineCopiesKey] = enabled }
    }

    suspend fun setReaderTextZoom(zoom: Int) {
        context.articleDataStore.edit { it[readerTextZoomKey] = zoom.coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM) }
    }

    companion object {
        const val MIN_TEXT_ZOOM = 80
        const val DEFAULT_TEXT_ZOOM = 100
        const val MAX_TEXT_ZOOM = 160
        const val TEXT_ZOOM_STEP = 10
    }
}
