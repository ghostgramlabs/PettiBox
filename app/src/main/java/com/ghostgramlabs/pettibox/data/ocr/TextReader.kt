package com.ghostgramlabs.pettibox.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import com.ghostgramlabs.pettibox.data.preferences.OcrPreferences
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import javax.inject.Singleton

/** The file can't be opened or decoded (missing, or not an image or PDF): retrying won't help. */
class UnreadableSourceException(message: String) : Exception(message)

/** A PDF reading stopped between pages on request ([TextReader.readPdf]'s `stop`); its progress is saved. */
class ReadingPausedException : Exception("paused between pages")

/**
 * What reading a file came to: its [text], and [replaced]: what the engines
 * also made of the same places but was dropped for a better line there (a
 * Latin guess under a Russian line, a reading too unsure to keep). An old
 * word found only in [replaced] was a misreading, so a new reading may drop
 * it ([TextReader.improves]).
 */
data class Reading(val text: String, val replaced: String = "")

/** Where a PDF reading got to: [nextPage] is the first not yet read; [reading] is the text so far. */
data class PdfProgress(val nextPage: Int, val reading: Reading)

/**
 * Reads the text in an image or PDF, in the Latin alphabet and every
 * language turned on in Settings (see [OcrLanguages]) whose model is here.
 *
 * Each image is read by the Latin model and by each language's model, line
 * by line. A line from a language's model is kept when most of its letters
 * are in that language's script and the model is sure enough of it (a model
 * shown a script it doesn't know makes up letters, and is unsure of them).
 * Where two kept lines cover the same place, the surer, longer one stays;
 * Latin lines fill in wherever no language line was kept. So a short label
 * ("天气", "Привет") is kept, and a page in two scripts keeps both.
 */
@Singleton
class TextReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: OcrModels,
    private val prefs: OcrPreferences
) {
    private var readyCache: Pair<Long, List<OcrLanguage>>? = null

    /** The languages turned on whose models are here, checked at most once a minute. */
    suspend fun readyLanguages(): List<OcrLanguage> {
        readyCache?.let { (at, langs) -> if (System.currentTimeMillis() - at < 60_000) return langs }
        val langs = models.ready(prefs.languages.first()).mapNotNull { OcrLanguages.of(it) }
        readyCache = System.currentTimeMillis() to langs
        return langs
    }

    /** The scripts that can be read right now: Latin, plus those of the ready languages. */
    suspend fun readableScripts(): Set<Character.UnicodeScript> =
        readyLanguages().flatMapTo(HashSet()) { it.scripts } + Character.UnicodeScript.LATIN

    /**
     * What a reading depends on besides the file: the languages ready and
     * the page limit. Saved PDF progress only carries on under the same.
     */
    suspend fun configuration(pageLimit: Int): String =
        readyLanguages().map { it.code }.sorted().joinToString(",") + "|" + pageLimit

    /** Forget the cached list (a model just arrived, or the choice changed). */
    fun refresh() { readyCache = null }

    /**
     * The text in an image. Throws [UnreadableSourceException] when the file
     * can't be read, and the engine's error when no engine could read it
     * (worth retrying).
     */
    suspend fun readImage(uri: Uri): Reading {
        val langs = readyLanguages()
        // The permit is taken before decoding: waiting jobs hold no pictures.
        return gate.withPermit {
            val bmp = try {
                OcrImageLoader.loadBitmap(context, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: throw UnreadableSourceException("can't open $uri")
            try { readNow(bmp, langs) } finally { bmp.recycle() }
        }
    }

    /**
     * Text of the first [pageLimit] pages, a blank line between pages. Pages
     * are drawn [PDF_WIDTH] wide in slices at most [PDF_SLICE] tall, so a
     * long receipt never needs one huge bitmap.
     *
     * Carries on from [from] (saved progress) if given. After each page,
     * [saved] is told where it got to, then [stop] is asked whether to pause
     * there ([ReadingPausedException]); a stopped job resumes from the next
     * page instead of the first.
     */
    suspend fun readPdf(
        uri: Uri,
        pageLimit: Int,
        from: PdfProgress? = null,
        saved: suspend (PdfProgress) -> Unit = {},
        stop: () -> Boolean = { false }
    ): Reading = withContext(Dispatchers.IO) {
        val langs = readyLanguages()
        val text = StringBuilder(from?.reading?.text.orEmpty())
        val replaced = StringBuilder(from?.reading?.replaced.orEmpty())
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: throw UnreadableSourceException("can't open $uri")
        pfd.use { descriptor ->
            val pdf = try {
                PdfRenderer(descriptor)
            } catch (e: Exception) {
                throw UnreadableSourceException("not a PDF: $uri")
            }
            pdf.use {
                val last = minOf(pdf.pageCount, pageLimit)
                for (i in (from?.nextPage ?: 0) until last) {
                    coroutineContext.ensureActive()
                    val page = readPage(pdf, i, langs)
                    if (page.text.isNotBlank()) text.append(page.text).append("\n\n")
                    if (page.replaced.isNotBlank()) replaced.append(page.replaced).append("\n")
                    saved(PdfProgress(i + 1, Reading(text.toString(), replaced.toString())))
                    if (i + 1 < last && stop()) throw ReadingPausedException()
                }
            }
        }
        Reading(text.toString().trim(), replaced.toString().trim())
    }

    private suspend fun readPage(pdf: PdfRenderer, index: Int, langs: List<OcrLanguage>): Reading = pdf.openPage(index).use { page ->
        val scale = PDF_WIDTH.toFloat() / page.width.coerceAtLeast(1)
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        var top = 0
        val lines = ArrayList<String>()
        val replaced = StringBuilder()
        while (top < height) {
            val sliceHeight = minOf(PDF_SLICE, height - top)
            val sliceTop = top
            // The permit is taken before the slice is drawn: waiting jobs hold no bitmaps.
            val slice = gate.withPermit {
                val bmp = Bitmap.createBitmap(width, sliceHeight, Bitmap.Config.ARGB_8888)
                try {
                    bmp.eraseColor(Color.WHITE)
                    val matrix = Matrix().apply { setScale(scale, scale); postTranslate(0f, -sliceTop.toFloat()) }
                    page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    readNow(bmp, langs)
                } finally {
                    bmp.recycle()
                }
            }
            slice.text.lines().filter { it.isNotBlank() }.forEach { line ->
                // Slices overlap a little, so a line on the edge isn't cut in two; don't keep it twice.
                if (lines.lastOrNull() != line) lines += line
            }
            if (slice.replaced.isNotBlank()) replaced.append(slice.replaced).append("\n")
            if (top + sliceHeight >= height) break
            top += sliceHeight - PDF_SLICE_OVERLAP
        }
        Reading(lines.joinToString("\n"), replaced.toString().trim())
    }

    /** One line of a reading, where it is on the image, and how sure the engine is (0–1). */
    private data class Line(val text: String, val box: Rect, val confidence: Float)

    /**
     * At most two pictures in memory for reading at once, however many jobs
     * run: taken before decoding or drawing, released after the engines.
     */
    private val gate = Semaphore(2)

    private suspend fun readNow(bmp: Bitmap, langs: List<OcrLanguage>): Reading {
        var failure: Exception? = null
        var anyRan = false
        suspend fun attempt(what: String, block: suspend () -> List<Line>): List<Line>? = try {
            block().also { anyRan = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "reading with $what failed", e)
            failure = e
            null
        }

        // Each engine on its own: one failing doesn't stop the others.
        val latin = attempt("Latin") { mlKitLines(null, bmp) }.orEmpty()
        val kept = ArrayList<Pair<Line, Float>>() // a line and its weight
        val unsure = ArrayList<Line>() // lines in an engine's script it was too unsure of
        val runs = langs.filter { it.engine != OcrEngine.TESSERACT }.groupBy { it.engine }
            .map { (engine, ls) -> Triple(engine, null as String?, ls.flatMap { it.scripts }.toSet()) } +
            langs.filter { it.engine == OcrEngine.TESSERACT }.map { Triple(it.engine, it.tessCode, it.scripts) }
        for ((engine, tess, scripts) in runs) {
            val lines = attempt(tess ?: engine.name) {
                if (tess != null) tesseractLines(tess, bmp) else mlKitLines(engine, bmp)
            } ?: continue
            val min = if (tess != null) MIN_TESS_LINE_CONFIDENCE else MIN_MLKIT_LINE_CONFIDENCE
            for (line in lines) {
                val inScript = lineInScript(line.text, scripts)
                if (inScript > 0 && line.confidence >= min) kept += line to inScript * line.confidence
                else if (inScript > 0) unsure += line
            }
        }
        if (!anyRan) throw failure ?: IllegalStateException("no text engine available")

        // Where two language lines cover the same place, the surer and longer one stays.
        val chosen = ArrayList<Line>()
        val dropped = ArrayList<Line>()
        for ((line, _) in kept.sortedByDescending { it.second }) {
            if (chosen.none { overlap(it.box, line.box) > SAME_PLACE }) chosen += line else dropped += line
        }
        // Latin lines wherever no language line was kept; under one, they were a guess at it.
        val languageLines = chosen.toList()
        for (line in latin) {
            if (languageLines.none { overlap(it.box, line.box) > SAME_PLACE }) chosen += line else dropped += line
        }
        val text = chosen.sortedWith(compareBy({ it.box.centerY() / LINE_BAND }, { it.box.left }))
            .joinToString("\n") { it.text.trim() }.trim()
        return Reading(text, (dropped + unsure).joinToString("\n") { it.text.trim() })
    }

    private suspend fun mlKitLines(engine: OcrEngine?, bmp: Bitmap): List<Line> =
        models.recognizer(engine).process(InputImage.fromBitmap(bmp, 0)).await().textBlocks
            .flatMap { it.lines }
            .mapNotNull { l -> l.boundingBox?.let { Line(l.text, it, l.confidence) } }

    /** Tesseract isn't thread-safe per instance, and its start-up is cheap next to reading; one per image. */
    private suspend fun tesseractLines(model: String, bmp: Bitmap): List<Line> = withContext(Dispatchers.Default) {
        val api = TessBaseAPI()
        try {
            if (!api.init(models.tessRoot.absolutePath, "$model+${OcrLanguages.TESS_ENGLISH}", TessBaseAPI.OEM_LSTM_ONLY)) {
                throw IllegalStateException("Tesseract couldn't load $model")
            }
            api.setImage(bmp)
            api.getUTF8Text() // runs recognition
            val iterator = api.resultIterator ?: return@withContext emptyList()
            val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
            val out = ArrayList<Line>()
            try {
                iterator.begin()
                do {
                    val text = iterator.getUTF8Text(level)?.trim()?.let(::normalizeIndic)
                    if (!text.isNullOrBlank()) out += Line(text, iterator.getBoundingRect(level), iterator.confidence(level) / 100f)
                } while (iterator.next(level))
            } finally {
                iterator.delete()
            }
            out
        } finally {
            api.recycle()
        }
    }

    companion object {
        private const val TAG = "PettiBoxOcr"
        private const val PDF_WIDTH = 1400
        /** Tallest slice of a PDF page drawn at once: 1400 × 2000 pixels, about 11 MB. */
        private const val PDF_SLICE = 2000
        private const val PDF_SLICE_OVERLAP = 120

        /**
         * How sure Tesseract must be of a line (0–1). On test pictures, real
         * readings scored 0.70–0.96 and ones made up from a script the model
         * doesn't know 0.31–0.62.
         */
        private const val MIN_TESS_LINE_CONFIDENCE = 0.65f
        private const val MIN_MLKIT_LINE_CONFIDENCE = 0.5f
        /** A line's letters that must be in the language's script: at least 2, and this share. */
        private const val MIN_LINE_SHARE = 0.6
        /** Two lines whose boxes share this much of the smaller one are the same text read twice. */
        private const val SAME_PLACE = 0.4f
        /** Lines whose centres are this close vertically are on the same row (read left to right). */
        private const val LINE_BAND = 24

        private fun overlap(a: Rect, b: Rect): Float {
            val r = Rect()
            if (!r.setIntersect(a, b)) return 0f
            val smaller = minOf(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
            return r.width() * r.height() / smaller.toFloat()
        }

        /** How many of [text]'s letters are in [scripts], or 0 when too few or too small a share to count. */
        internal fun lineInScript(text: String, scripts: Set<Character.UnicodeScript>): Int {
            var inScript = 0
            var letters = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                if (!isLetterOrMark(cp)) continue
                letters++
                if (Character.UnicodeScript.of(cp) in scripts) inScript++
            }
            return if (inScript >= 2 && inScript >= letters * MIN_LINE_SHARE) inScript else 0
        }

        private fun isLetterOrMark(cp: Int): Boolean = Character.isLetter(cp) ||
            Character.getType(cp) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(cp) == Character.COMBINING_SPACING_MARK.toInt()

        /**
         * Whether a new reading of a file should replace its [old] text.
         * Nothing someone could search for may be lost, so:
         * - none before: yes, if the new one found any;
         * - the old text has a script that can't be read now (its language
         *   was turned off): no;
         * - otherwise only if every word of the old text is accounted for
         *   ([unaccountedWords]): still there, there with a small correction
         *   ("Grocerv" → "Grocery"), or something the engines made of the
         *   same place this time and dropped for a better line
         *   ([Reading.replaced]: the old word was a misreading).
         * A lone label can't wipe out a page, fewer PDF pages can't shrink
         * one, and neither can junk of any length.
         */
        internal fun improves(new: Reading, old: String?, readable: Set<Character.UnicodeScript>): Boolean {
            if (new.text.isBlank()) return false
            if (old.isNullOrBlank()) return true
            if (lettersByScript(old).any { (script, n) -> script !in readable && n >= 2 }) return false
            return unaccountedWords(old, new).isEmpty()
        }

        /**
         * When [improves] says no, but the new reading finds words the [old]
         * text lacks (a photo read the right way up now, a better engine):
         * the new reading plus the old lines it doesn't account for, so the
         * new words become searchable and no old word is lost. Null when
         * there's nothing new, or when the old text has a script that can't
         * be read now (merging would mix guesses at it in). Merging again
         * with the same reading gives the same text.
         */
        internal fun merged(new: Reading, old: String?, readable: Set<Character.UnicodeScript>): String? {
            if (new.text.isBlank() || old.isNullOrBlank()) return null
            if (lettersByScript(old).any { (script, n) -> script !in readable && n >= 2 }) return null
            if ((tokens(new.text) - tokens(old)).isEmpty()) return null
            val missing = unaccountedWords(old, new).toSet()
            val kept = old.lines().filter { line -> words(line).any { it in missing } }
            return if (kept.isEmpty()) new.text else new.text + "\n\n" + kept.joinToString("\n")
        }

        /** [improves] for a reading that dropped nothing (tests, and text with no reading behind it). */
        internal fun improves(new: String, old: String?, readable: Set<Character.UnicodeScript>): Boolean =
            improves(Reading(new), old, readable)

        /** The words (3+ letters or digits, ignoring case) of [old] that [new] doesn't account for. */
        internal fun unaccountedWords(old: String, new: Reading): List<String> {
            val now = words(new.text)
            val replaced = words(new.replaced)
            val byLength = now.groupBy { it.length }
            return words(old).filter { w ->
                w !in now && w !in replaced &&
                    (w.length - 2..w.length + 2).none { n -> byLength[n].orEmpty().any { closeTo(w, it) } }
            }
        }

        private fun words(text: String): Set<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filterTo(HashSet()) { it.length >= 3 }

        /** [words], plus two-letter ones in scripts written without spaces (天气 is a whole word). */
        private fun tokens(text: String): Set<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filterTo(HashSet()) {
                it.length >= 3 || (it.length == 2 && Character.UnicodeScript.of(it.codePointAt(0)) in UNSPACED)
            }

        private val UNSPACED = setOf(
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL
        )

        /** The same word read slightly differently: at most one change per four letters. */
        private fun closeTo(a: String, b: String): Boolean {
            val allowed = maxOf(1, minOf(a.length, b.length) / 4)
            if (kotlin.math.abs(a.length - b.length) > allowed) return false
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                var best = cur[0]
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                    best = minOf(best, cur[j])
                }
                if (best > allowed) return false
                prev = cur
            }
            return prev[b.length] <= allowed
        }

        /** Letters (and vowel signs) of [text] in each script. */
        internal fun lettersByScript(text: String): Map<Character.UnicodeScript, Int> {
            val out = HashMap<Character.UnicodeScript, Int>()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                if (!isLetterOrMark(cp)) continue
                val script = Character.UnicodeScript.of(cp)
                if (script == Character.UnicodeScript.INHERITED || script == Character.UnicodeScript.COMMON) continue
                out[script] = (out[script] ?: 0) + 1
            }
            return out
        }

        /** Malayalam letter + virama + ZWJ: the old way of writing the chillu letters keyboards now type as one. */
        private val CHILLU = mapOf(
            "ണ്‍" to "ൺ", "ന്‍" to "ൻ", "ര്‍" to "ർ",
            "ല്‍" to "ൽ", "ള്‍" to "ൾ", "ക്‍" to "ൿ"
        )
        /** A zero-width (non-)joiner after an Indic letter or sign: invisible, and it keeps a word from matching what people type. */
        private val INDIC_JOINER = Regex("(?<=[\\u0900-\\u0DFF])[\\u200C\\u200D]")

        /**
         * Tesseract's Indic output in the form keyboards produce, so a search
         * for a word finds it: Malayalam chillus as single letters, and no
         * zero-width joiners inside Indic words (Persian and Urdu keep theirs).
         */
        internal fun normalizeIndic(text: String): String {
            var t = text
            for ((old, new) in CHILLU) t = t.replace(old, new)
            return INDIC_JOINER.replace(t, "")
        }
    }
}
