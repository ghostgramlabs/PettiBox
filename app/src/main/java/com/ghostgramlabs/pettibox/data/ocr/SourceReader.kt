package com.ghostgramlabs.pettibox.data.ocr

import android.net.Uri
import android.util.Log
import com.ghostgramlabs.pettibox.data.local.OcrStatusEntity
import com.ghostgramlabs.pettibox.data.preferences.OcrPreferences
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the text of one file and records how it went, the same way for a
 * new save, a file arriving from another phone, Try again, and re-reading
 * after a language was turned on:
 * - the text is stored only if it loses nothing ([TextReader.improves],
 *   checked again as it's written);
 * - the outcome is kept per file ([OcrStatusEntity]): no text, couldn't
 *   open, or (on the last try) couldn't read; any text it had stays;
 * - a PDF's progress is saved after every page, and a stopped reading
 *   carries on from the next page, under the same languages and page limit.
 */
@Singleton
class SourceReader @Inject constructor(
    private val repository: SaveRepository,
    private val reader: TextReader,
    private val prefs: OcrPreferences
) {
    enum class Outcome {
        /** Read, or there's nothing to read; recorded either way. */
        DONE,
        /** Failed this time, and it isn't the last try: try again later (progress kept). */
        RETRY,
        /** A PDF stopped between pages because [read]'s `stop` said so; progress saved. */
        PAUSED
    }

    /**
     * Reads one file: the save's own ([attachmentId] null) or one added to
     * it. On failure, [lastTry] decides between recording "couldn't read"
     * and [Outcome.RETRY]. [stop] is asked after each PDF page.
     */
    suspend fun read(
        itemId: Long, attachmentId: Long?, uri: String, pdf: Boolean,
        lastTry: Boolean, stop: () -> Boolean = { false }
    ): Outcome {
        suspend fun state(value: String?) = repository.setReadingState(itemId, attachmentId, uri, pdf, value)
        return try {
            val readable = reader.readableScripts()
            val reading = if (pdf) readPdf(itemId, attachmentId, uri, stop) else reader.readImage(Uri.parse(uri))
            repository.acceptSourceText(itemId, attachmentId, reading, readable)
            state(if (repository.sourceHasText(itemId, attachmentId)) null else OcrStatusEntity.NO_TEXT)
            Outcome.DONE
        } catch (e: CancellationException) {
            throw e // a stopped job; a PDF's progress is already saved
        } catch (e: ReadingPausedException) {
            Outcome.PAUSED
        } catch (e: UnreadableSourceException) {
            Log.i(TAG, "nothing to read: ${e.message}")
            state(OcrStatusEntity.UNREADABLE)
            Outcome.DONE
        } catch (e: Exception) {
            Log.w(TAG, "reading text failed", e)
            if (lastTry) {
                state(OcrStatusEntity.FAILED)
                Outcome.DONE
            } else {
                Outcome.RETRY
            }
        }
    }

    private suspend fun readPdf(itemId: Long, attachmentId: Long?, uri: String, stop: () -> Boolean): Reading {
        val pages = prefs.pdfPageLimit.first()
        val config = reader.configuration(pages)
        // Carry on where a stopped reading of this same file, read the same way, got to.
        val from = repository.readingStateOf(itemId, attachmentId)
            ?.takeIf { it.state == OcrStatusEntity.READING && it.config == config && it.uri == uri && it.pagesDone > 0 }
            ?.let { PdfProgress(it.pagesDone, Reading(it.partialText.orEmpty(), it.partialReplaced.orEmpty())) }
        return reader.readPdf(
            Uri.parse(uri), pages, from,
            saved = { progress -> repository.saveReadingProgress(itemId, attachmentId, uri, config, progress) },
            stop = stop
        )
    }

    private companion object {
        const val TAG = "PettiBoxOcr"
    }
}
