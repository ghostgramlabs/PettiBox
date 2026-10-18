package com.ghostgramlabs.pettibox.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.ghostgramlabs.pettibox.data.ocr.OcrLanguages
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ocrDataStore by preferencesDataStore(name = "ocr_preferences")

@Singleton
class OcrPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val autoScanKey = booleanPreferencesKey("auto_scan_images_and_pdfs")
    private val pdfPageLimitKey = intPreferencesKey("pdf_page_limit")
    private val languagesKey = stringSetPreferencesKey("languages")
    private val readWithKey = stringSetPreferencesKey("read_with")
    private val rereadCursorKey = longPreferencesKey("reread_cursor")
    private val rereadRequestKey = longPreferencesKey("reread_request")
    private val rereadPdfOnlyKey = booleanPreferencesKey("reread_pdf_only")
    /** The request the round under way serves (requests counted by [rereadRequestKey] may be newer). */
    private val rereadActiveKey = longPreferencesKey("reread_active")
    private val rereadNextAllKey = booleanPreferencesKey("reread_next_all")
    private val failedLanguagesKey = stringSetPreferencesKey("failed_languages")

    /** Languages whose model couldn't be fetched after several tries (offered a Retry). */
    val failedLanguages: Flow<Set<String>> = context.ocrDataStore.data.map { it[failedLanguagesKey].orEmpty() }

    suspend fun setFailedLanguages(codes: Set<String>) {
        context.ocrDataStore.edit { it[failedLanguagesKey] = codes }
    }

    /**
     * Languages read besides the Latin alphabet ([OcrLanguages] codes).
     * Until the person chooses, the ones the phone is set to.
     */
    val languages: Flow<Set<String>> = context.ocrDataStore.data.map { prefs ->
        prefs[languagesKey] ?: OcrLanguages.defaultsFor(phoneLanguages())
    }

    suspend fun setLanguages(codes: Set<String>) {
        context.ocrDataStore.edit { it[languagesKey] = codes }
    }

    /** The languages saved images and PDFs were last all read with (ready models only). */
    suspend fun readWith(): Set<String> = context.ocrDataStore.data.first()[readWithKey].orEmpty()

    suspend fun setReadWith(codes: Set<String>) {
        context.ocrDataStore.edit { it[readWithKey] = codes }
    }

    /**
     * Re-reading saved images and PDFs: the save id to continue below, or
     * null when nothing is being re-read. [Long.MAX_VALUE] starts from the newest.
     */
    suspend fun rereadCursor(): Long? = context.ocrDataStore.data.first()[rereadCursorKey]
    val rereading: Flow<Boolean> = context.ocrDataStore.data.map { it[rereadCursorKey] != null }

    /**
     * A re-read under way: where it is, the request its round serves (not
     * necessarily the newest: a job restarted midway goes on with the round
     * it was in, and newer requests get their own round after), and whether
     * it's PDFs only.
     */
    data class Reread(val cursor: Long, val request: Long, val pdfOnly: Boolean)

    suspend fun reread(): Reread? {
        val p = context.ocrDataStore.data.first()
        val cursor = p[rereadCursorKey] ?: return null
        return Reread(cursor, p[rereadActiveKey] ?: p[rereadRequestKey] ?: 0L, p[rereadPdfOnlyKey] ?: false)
    }

    /**
     * Asks for saved files to be read again: every image and PDF, or with
     * [pdfOnly] just PDFs (more pages). Safe while a re-read runs: it sees
     * the new request when it reaches the end and goes round once more.
     */
    suspend fun requestReread(pdfOnly: Boolean = false) {
        context.ocrDataStore.edit {
            val request = (it[rereadRequestKey] ?: 0L) + 1
            it[rereadRequestKey] = request
            if (it[rereadCursorKey] == null) {
                it[rereadCursorKey] = Long.MAX_VALUE
                it[rereadActiveKey] = request
                it[rereadPdfOnlyKey] = pdfOnly
                it[rereadNextAllKey] = false
            } else if (!pdfOnly) {
                it[rereadNextAllKey] = true
            }
        }
    }

    /**
     * A re-read serving [request] reached the end. In one step: if no newer
     * request came, it's over (null); otherwise the next round starts from
     * the newest save, covering what the newer requests asked for.
     */
    suspend fun finishReread(request: Long): Reread? {
        var next: Reread? = null
        context.ocrDataStore.edit {
            val now = it[rereadRequestKey] ?: 0L
            if (now == request) {
                it.remove(rereadCursorKey)
                it.remove(rereadActiveKey)
            } else {
                val pdfOnly = it[rereadNextAllKey] != true
                it[rereadCursorKey] = Long.MAX_VALUE
                it[rereadActiveKey] = now
                it[rereadPdfOnlyKey] = pdfOnly
                it[rereadNextAllKey] = false
                next = Reread(Long.MAX_VALUE, now, pdfOnly)
            }
        }
        return next
    }

    suspend fun setRereadCursor(cursor: Long?) {
        context.ocrDataStore.edit { if (cursor == null) it.remove(rereadCursorKey) else it[rereadCursorKey] = cursor }
    }

    /** Every language set on the phone (not just the app's), as ISO codes. */
    private fun phoneLanguages(): List<String> {
        val locales = android.content.res.Resources.getSystem().configuration.locales
        return (0 until locales.size()).map { locales[it].language }
    }

    val autoScan: Flow<Boolean> = context.ocrDataStore.data.map { prefs ->
        prefs[autoScanKey] ?: true
    }

    val pdfPageLimit: Flow<Int> = context.ocrDataStore.data.map { prefs ->
        prefs[pdfPageLimitKey]?.coerceIn(MIN_PDF_PAGES, MAX_PDF_PAGES) ?: DEFAULT_PDF_PAGES
    }

    suspend fun setAutoScan(enabled: Boolean) {
        context.ocrDataStore.edit { prefs ->
            prefs[autoScanKey] = enabled
        }
    }

    suspend fun setPdfPageLimit(limit: Int) {
        context.ocrDataStore.edit { prefs ->
            prefs[pdfPageLimitKey] = limit.coerceIn(MIN_PDF_PAGES, MAX_PDF_PAGES)
        }
    }

    companion object {
        const val MIN_PDF_PAGES = 10
        const val DEFAULT_PDF_PAGES = 30
        const val MAX_PDF_PAGES = 50
        val PDF_PAGE_LIMIT_OPTIONS = listOf(10, 30, 50)
    }
}
