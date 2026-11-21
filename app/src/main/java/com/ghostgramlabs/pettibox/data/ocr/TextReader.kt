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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
            try {
                // In slices, as a PDF page is: on a photo of a whole newspaper page
                // both engines read several times as many words that way.
                readSlices(bmp.height) { top, height ->
                    if (top == 0 && height == bmp.height) readNow(bmp, langs)
                    else {
                        val slice = Bitmap.createBitmap(bmp, 0, top, bmp.width, height)
                        try { readNow(slice, langs) } finally { if (slice !== bmp) slice.recycle() }
                    }
                }
            } finally {
                bmp.recycle()
            }
        }
    }

    /**
     * Text of the first [pageLimit] pages (fewer once the text reaches
     * [MAX_PDF_TEXT_CHARS]), a blank line between pages. Pages
     * are drawn at about [PDF_DPI] in slices at most [PDF_SLICE] tall, so a
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
                    // As much text as a save can hold: a longer PDF ("All pages") stops here.
                    if (text.length >= MAX_PDF_TEXT_CHARS) break
                    val page = readPage(pdf, i, langs)
                    if (page.text.isNotBlank()) text.append(page.text).append("\n\n")
                    if (page.replaced.isNotBlank() && replaced.length < MAX_PDF_TEXT_CHARS) replaced.append(page.replaced).append("\n")
                    saved(PdfProgress(i + 1, Reading(text.toString(), replaced.toString())))
                    if (i + 1 < last && text.length < MAX_PDF_TEXT_CHARS && stop()) throw ReadingPausedException()
                }
            }
        }
        Reading(text.toString().trim(), replaced.toString().trim())
    }

    private suspend fun readPage(pdf: PdfRenderer, index: Int, langs: List<OcrLanguage>): Reading = pdf.openPage(index).use { page ->
        // Drawn at about [PDF_DPI] (page sizes are in points, 72 to the inch), within [PDF_MIN_WIDTH, PDF_MAX_WIDTH].
        val pdfWidth = (page.width * PDF_DPI / 72).coerceIn(PDF_MIN_WIDTH, PDF_MAX_WIDTH)
        val scale = pdfWidth.toFloat() / page.width.coerceAtLeast(1)
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        readSlices(height) { sliceTop, sliceHeight ->
            // The permit is taken before the slice is drawn: waiting jobs hold no bitmaps.
            gate.withPermit {
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
        }
    }

    /**
     * Reads a page [height] pixels tall as slices at most [PDF_SLICE] tall,
     * [PDF_SLICE_OVERLAP] apart so a line on an edge is whole in one of them:
     * no huge bitmap for a long receipt, and the engines read small print in
     * a dense page far better a slice at a time.
     */
    private suspend fun readSlices(height: Int, read: suspend (top: Int, height: Int) -> Reading): Reading {
        var top = 0
        val lines = ArrayList<String>()
        val replaced = StringBuilder()
        while (top < height) {
            val sliceHeight = minOf(PDF_SLICE, height - top)
            val slice = read(top, sliceHeight)
            slice.text.lines().filter { it.isNotBlank() }.forEach { line ->
                // Slices overlap a little, so a line on the edge isn't cut in two; don't keep it twice.
                if (lines.lastOrNull() != line) lines += line
            }
            if (slice.replaced.isNotBlank()) replaced.append(slice.replaced).append("\n")
            if (top + sliceHeight >= height) break
            top += sliceHeight - PDF_SLICE_OVERLAP
        }
        return Reading(lines.joinToString("\n"), replaced.toString().trim())
    }

    /** Where something is on the image, in pixels. */
    internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val centerY get() = (top + bottom) / 2
    }

    /** One line of a reading, where it is on the image, and how sure the engine is (0–1). */
    internal data class Line(val text: String, val box: Box, val confidence: Float)

    /**
     * At most two pictures in memory for reading at once, however many jobs
     * run: taken before decoding or drawing, released after the engines.
     */
    private val gate = Semaphore(2)

    private suspend fun readNow(bmp: Bitmap, langs: List<OcrLanguage>): Reading = coroutineScope {
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

        // Each engine on its own: one failing doesn't stop the others. The Latin
        // model reads beside Tesseract (it runs on its own thread), whose first
        // pass doesn't need its lines.
        val latinRun = async {
            try { Result.success(mlKitLines(null, bmp)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
        }
        var latinLines: List<Line>? = null
        suspend fun latin(): List<Line> = latinLines ?: attempt("Latin") { latinRun.await().getOrThrow() }.orEmpty().also { latinLines = it }
        val kept = ArrayList<Pair<Line, Float>>() // a line and its weight
        val unsure = ArrayList<Line>() // lines in an engine's script it was too unsure of
        val overGuess = ArrayList<Line>() // of those, ones still better than the Latin model's guess at the same place
        val runs = langs.filter { it.engine != OcrEngine.TESSERACT }.groupBy { it.engine }
            .map { (engine, ls) -> Triple(engine, null as String?, ls.flatMap { it.scripts }.toSet()) } +
            langs.filter { it.engine == OcrEngine.TESSERACT }.map { Triple(it.engine, it.tessCode, it.scripts) }
        for ((engine, tess, scripts) in runs) {
            val lines = attempt(tess ?: engine.name) {
                if (tess != null) tesseractLines(tess, bmp, { latin() }, scripts) else mlKitLines(engine, bmp)
            } ?: continue
            val cyrillic = Character.UnicodeScript.CYRILLIC in scripts
            val fixed = if (cyrillic) lines.map { it.copy(text = cyrillicLookalikes(it.text)) } else lines
            // On a page plainly in the language (several sure lines), Tesseract's
            // less sure lines there are worn print, not a script it doesn't
            // know, and far better than the Latin model's guess at them.
            val min = when {
                tess == null -> MIN_MLKIT_LINE_CONFIDENCE
                fixed.count { lineInScript(it.text, scripts) > 0 && it.confidence >= MIN_TESS_LINE_CONFIDENCE } >= PAGE_IN_SCRIPT ->
                    MIN_TESS_LINE_CONFIDENCE_ON_ITS_PAGE
                else -> MIN_TESS_LINE_CONFIDENCE
            }
            val itsPage = tess != null && min == MIN_TESS_LINE_CONFIDENCE_ON_ITS_PAGE
            for (line in fixed) {
                val inScript = lineInScript(line.text, scripts)
                if (inScript > 0 && line.confidence >= min) kept += line to inScript * line.confidence
                else if (inScript > 0) {
                    unsure += line
                    if (itsPage && line.confidence >= MIN_TESS_LINE_CONFIDENCE_OVER_A_GUESS) overGuess += line
                }
            }
        }
        val latin = latin()
        if (!anyRan) throw failure ?: IllegalStateException("no text engine available")

        // Where two language lines cover the same place, the surer and longer one stays.
        val chosen = ArrayList<Line>()
        val dropped = ArrayList<Line>()
        for ((line, _) in kept.sortedByDescending { it.second }) {
            if (chosen.none { overlap(it.box, line.box) > SAME_PLACE }) chosen += line else dropped += line
        }
        // Latin lines wherever no language line was kept; under one, they were a guess at it.
        val languageLines = chosen.toList()
        val usedOverGuess = HashSet<Line>()
        for (line in latin) {
            if ((languageLines + usedOverGuess).any { overlap(it.box, line.box) > SAME_PLACE }) { dropped += line; continue }
            // On a page plainly in Tesseract's script, the Latin model's guess at that
            // script ("6aHOBa MBana") loses to Tesseract's less sure reading of the place.
            var replacedIt = false
            if (looksLikeLatinGuess(line.text)) {
                for (t in overGuess.filter { overlap(it.box, line.box) > SAME_PLACE }.sortedByDescending { it.confidence }) {
                    if (chosen.any { overlap(it.box, t.box) > SAME_PLACE }) continue
                    chosen += t; usedOverGuess += t; replacedIt = true
                }
            }
            if (replacedIt) dropped += line else chosen += line
        }
        unsure.removeAll(usedOverGuess)
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            chosen.forEach { Log.d(TAG, "chosen ${it.box} ${"%.2f".format(it.confidence)} ${it.text}") }
            unsure.forEach { Log.d(TAG, "unsure ${it.box} ${"%.2f".format(it.confidence)} ${it.text}") }
        }
        val text = readingOrder(chosen).joinToString("\n") { it.text.trim() }.trim()
        Reading(text, (dropped + unsure).joinToString("\n") { it.text.trim() })
    }

    private suspend fun mlKitLines(engine: OcrEngine?, bmp: Bitmap): List<Line> =
        models.recognizer(engine).process(InputImage.fromBitmap(bmp, 0)).await().textBlocks
            .flatMap { it.lines }
            .mapNotNull { l -> l.boundingBox?.let { Line(l.text, it.toBox(), l.confidence) } }

    /**
     * Tesseract isn't thread-safe per instance, and its start-up is cheap next to reading; one per image.
     *
     * Read word by word, then put back into Tesseract's lines, each cut where
     * a wide gap parts its words ([splitAtGaps]): Tesseract runs a line
     * straight across two columns (a letterhead and the addressee beside it),
     * and one sure line shouldn't be dropped for the unsure one beside it.
     *
     * Running across columns, Tesseract also misreads the half that isn't
     * level with where it thinks the line is. So on a page where it did read
     * its script, each line the Latin model found ([latin], asked for only then) that no sure
     * line of Tesseract's covers is read again by itself, its own area only.
     */
    private suspend fun tesseractLines(
        model: String,
        bmp: Bitmap,
        latin: suspend () -> List<Line>,
        scripts: Set<Character.UnicodeScript>
    ): List<Line> = withContext(Dispatchers.Default) {
        val api = TessBaseAPI()
        try {
            if (!api.init(models.tessRoot.absolutePath, "$model+${OcrLanguages.TESS_ENGLISH}", TessBaseAPI.OEM_LSTM_ONLY)) {
                throw IllegalStateException("Tesseract couldn't load $model")
            }
            api.setImage(bmp)
            val page = tesseractWords(api)
            // Only lines read surely count as covering a place: a less sure one is worth a second look.
            val sure = page.filter { lineInScript(it.text, scripts) > 0 && it.confidence >= MIN_TESS_LINE_CONFIDENCE }
            if (sure.isEmpty()) return@withContext page
            // Not covered, or only partly ("еждения" for "учреждения"): under 80% of its width.
            val again = latin().filter { l ->
                val over = sure.filter { overlap(it.box, l.box) > SAME_PLACE }
                over.isEmpty() || over.sumOf { minOf(it.box.right, l.box.right) - maxOf(it.box.left, l.box.left) } < l.box.width * 0.8
            }.sortedByDescending { looksLikeLatinGuess(it.text) }.take(MAX_REREADS) // guesses at the script first
            if (again.isEmpty()) return@withContext page
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            page + again.flatMap { l ->
                coroutineContext.ensureActive()
                // Within the image: ML Kit's boxes can run past its edges, and Tesseract reads outside it (a crash).
                val pad = l.box.height / 4
                val left = (l.box.left - pad).coerceIn(0, bmp.width)
                val top = (l.box.top - pad).coerceIn(0, bmp.height)
                val right = (l.box.right + pad).coerceIn(0, bmp.width)
                val bottom = (l.box.bottom + pad).coerceIn(0, bmp.height)
                if (right - left < 8 || bottom - top < 8) return@flatMap emptyList<Line>()
                api.setRectangle(left, top, right - left, bottom - top)
                tesseractWords(api).also { got ->
                    if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "reread ${l.text} -> ${got.joinToString(" / ") { "${it.text} ${"%.2f".format(it.confidence)} ${it.box}" }}")
                }
            }
        } finally {
            api.recycle()
        }
    }

    /** Runs recognition on [api]'s image (or rectangle of it) and gives its lines, cut at wide gaps. */
    private fun tesseractWords(api: TessBaseAPI): List<Line> {
        api.getUTF8Text() // runs recognition
        val iterator = api.resultIterator ?: return emptyList()
        val word = TessBaseAPI.PageIteratorLevel.RIL_WORD
        val lines = ArrayList<MutableList<Line>>()
        try {
            iterator.begin()
            do {
                if (lines.isEmpty() || iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) lines += ArrayList<Line>()
                val text = iterator.getUTF8Text(word)?.trim()?.let(::normalizeIndic)
                if (!text.isNullOrBlank()) lines.last() += Line(text, iterator.getBoundingRect(word).toBox(), iterator.confidence(word) / 100f)
            } while (iterator.next(word))
        } finally {
            iterator.delete()
        }
        return lines.flatMap(::splitAtGaps)
    }

    companion object {
        private const val TAG = "PettiBoxOcr"
        /**
         * Android can't load a database row past about 2 MB (CursorWindow), and
         * a save's text is in its row: at up to 3 bytes a character (Chinese,
         * Japanese), a save holds at most [MAX_SAVE_TEXT_CHARS], and one PDF
         * is read until its text reaches [MAX_PDF_TEXT_CHARS] (some hundreds of
         * pages of a letter, 50–100 of a newspaper).
         */
        const val MAX_PDF_TEXT_CHARS = 200_000
        const val MAX_SAVE_TEXT_CHARS = 400_000

        /** [text] cut, at a line end where there is one, to at most [max] characters. */
        fun capped(text: String, max: Int = MAX_SAVE_TEXT_CHARS): String {
            if (text.length <= max) return text
            val cut = text.lastIndexOf('\n', max).takeIf { it >= max / 2 }
                ?: if (Character.isHighSurrogate(text[max - 1])) max - 1 else max
            return text.substring(0, cut).trimEnd()
        }
        /**
         * Scans are read at about this resolution: an A4 page about 2070
         * pixels wide. At the old fixed 1400 (170 dpi for A4) small print in
         * scanned letters and newspapers blurred into the Latin model's guesses.
         */
        private const val PDF_DPI = 250
        private const val PDF_MIN_WIDTH = 1400
        private const val PDF_MAX_WIDTH = 2400
        /** Tallest slice of a PDF page drawn at once: at most 2400 × 2000 pixels, about 19 MB. */
        private const val PDF_SLICE = 2000
        private const val PDF_SLICE_OVERLAP = 120

        /**
         * How sure Tesseract must be of a line (0–1). On test pictures, real
         * readings scored 0.70–0.96 and ones made up from a script the model
         * doesn't know 0.31–0.62.
         */
        private const val MIN_TESS_LINE_CONFIDENCE = 0.65f
        /**
         * The same, on a page with [PAGE_IN_SCRIPT] lines at the above. On real
         * scans (worn newsprint, old typefaces, show-through) good lines
         * scored 0.35–0.65 and junk 0.00–0.26.
         */
        private const val MIN_TESS_LINE_CONFIDENCE_ON_ITS_PAGE = 0.35f
        private const val PAGE_IN_SCRIPT = 3
        /** On such a page, the least sure Tesseract line that still beats the Latin model's guess at it ([looksLikeLatinGuess]). */
        private const val MIN_TESS_LINE_CONFIDENCE_OVER_A_GUESS = 0.1f
        private const val MIN_MLKIT_LINE_CONFIDENCE = 0.5f
        /** A line's letters that must be in the language's script: at least 2, and this share. */
        private const val MIN_LINE_SHARE = 0.6
        /** Two lines whose boxes share this much of the smaller one are the same text read twice. */
        private const val SAME_PLACE = 0.4f
        /** Scripts written without spaces between words: Tesseract's words in them join with none. */
        private val NO_SPACES = setOf(
            Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER,
            Character.UnicodeScript.MYANMAR, Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA
        )

        /** At most this many lines of one image read again by themselves ([tesseractLines]). */
        private const val MAX_REREADS = 40

        private val COLUMN_RULE = Regex("[|¦│]+")

        /** A gap between words this many times the line's height parts it in two (columns, not spaces). */
        private const val COLUMN_GAP = 2.5

        private fun Rect.toBox() = Box(left, top, right, bottom)

        private fun overlap(a: Box, b: Box): Float {
            val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            if (w <= 0 || h <= 0) return 0f
            val smaller = minOf(a.width * a.height, b.width * b.height).coerceAtLeast(1)
            return w.toFloat() * h / smaller
        }

        /** How much two spans share, as a part of the shorter (0–1). */
        private fun shared(a1: Int, a2: Int, b1: Int, b2: Int): Float =
            (minOf(a2, b2) - maxOf(a1, b1)).coerceAtLeast(0) / (minOf(a2 - a1, b2 - b1).coerceAtLeast(1)).toFloat()

        /**
         * One Tesseract line from its [words], cut wherever the gap between
         * neighbours is over [COLUMN_GAP] line heights. Each part keeps its
         * words in Tesseract's order (right to left stays right to left) and
         * is as sure as its words are, longer words counting for more.
         */
        internal fun splitAtGaps(all: List<Line>): List<Line> {
            // A rule between newspaper columns comes out as a lone "|": a cut, not a word.
            val rules = all.filter { it.text.matches(COLUMN_RULE) }
            val words = all - rules.toSet()
            if (words.isEmpty()) return emptyList()
            val height = words.map { it.box.height }.sorted()[words.size / 2].coerceAtLeast(1)
            val byX = words.sortedBy { it.box.left }
            val cuts = byX.zipWithNext().filter { (a, b) -> b.box.left - a.box.right > height * COLUMN_GAP }.map { it.second.box.left } +
                rules.map { it.box.left }
            return words.groupBy { w -> cuts.count { w.box.left >= it } }.toSortedMap().values.map { part ->
                val weight = part.sumOf { it.text.length }.coerceAtLeast(1)
                Line(
                    part.drop(1).fold(StringBuilder(part[0].text)) { s, w ->
                        // Thai, Khmer, Chinese… put no space between words.
                        val unspaced = Character.UnicodeScript.of(s.codePointBefore(s.length)) in NO_SPACES &&
                            Character.UnicodeScript.of(w.text.codePointAt(0)) in NO_SPACES
                        (if (unspaced) s else s.append(' ')).append(w.text)
                    }.toString(),
                    Box(part.minOf { it.box.left }, part.minOf { it.box.top }, part.maxOf { it.box.right }, part.maxOf { it.box.bottom }),
                    part.sumOf { it.confidence.toDouble() * it.text.length }.toFloat() / weight
                )
            }
        }

        /**
         * [lines] in the order a person reads the page. Lines stacked close
         * together that share a column form a block (a letterhead, the
         * addressee beside it, a paragraph); blocks side by side are read one
         * whole block after another, left to right (right to left for Arabic
         * or Hebrew), unless their lines line up row for row, as a receipt's
         * items and prices or a signature line do: those are read across.
         */
        internal fun readingOrder(lines: List<Line>): List<Line> {
            if (lines.size < 2) return lines
            val rtl = isMostlyRtl(lines)
            fun rows(ls: List<Line>): List<Line> {
                val out = ArrayList<Line>()
                val sorted = ls.sortedBy { it.box.centerY }
                var row = ArrayList<Line>()
                for (l in sorted) {
                    val anchor = row.firstOrNull()
                    if (anchor != null && l.box.centerY - anchor.box.centerY > minOf(anchor.box.height, l.box.height) / 2) {
                        out += if (rtl) row.sortedByDescending { it.box.right } else row.sortedBy { it.box.left }
                        row = ArrayList()
                    }
                    row += l
                }
                out += if (rtl) row.sortedByDescending { it.box.right } else row.sortedBy { it.box.left }
                return out
            }

            // Blocks: a line joins the block whose nearest line sits just above it, in the same column.
            val blocks = ArrayList<MutableList<Line>>()
            for (l in lines.sortedBy { it.box.top }) {
                val h = l.box.height
                val home = blocks.filter { b ->
                    b.any { m ->
                        val gap = l.box.top - m.box.bottom
                        // Capitals' boxes are short for their spacing: up to 1.5 heights apart.
                        gap < h * 3 / 2 && gap > -h / 2 && l.box.top > m.box.top &&
                            shared(m.box.left, m.box.right, l.box.left, l.box.right) >= 0.5f
                    }
                }.minByOrNull { b -> b.minOf { m -> kotlin.math.abs(l.box.top - m.box.bottom) } }
                if (home != null) home += l else blocks += arrayListOf(l)
            }

            // Bands: blocks that share any height, read together.
            val bands = ArrayList<MutableList<MutableList<Line>>>()
            for (b in blocks.sortedBy { bl -> bl.minOf { it.box.top } }) {
                val top = b.minOf { it.box.top }
                val band = bands.lastOrNull()
                if (band != null && top < band.maxOf { bl -> bl.maxOf { it.box.bottom } }) band += b else bands += arrayListOf(b)
            }

            val out = ArrayList<Line>()
            for (band in bands) {
                if (band.size == 1) { out += rows(band[0]); continue }
                val all = band.flatten()
                // Row for row: most lines have one in another block at the same height.
                val aligned = band.sumOf { b ->
                    b.count { l ->
                        band.any { o -> o !== b && o.any { m -> kotlin.math.abs(m.box.centerY - l.box.centerY) < minOf(m.box.height, l.box.height) * 0.4 } }
                    }
                }
                // Cells, not columns of text: each narrower than the space between it and its neighbour.
                val byLeft = band.sortedBy { b -> b.minOf { it.box.left } }
                val cells = byLeft.zipWithNext().all { (a, b) ->
                    val aw = a.maxOf { it.box.right } - a.minOf { it.box.left }
                    val bw = b.maxOf { it.box.right } - b.minOf { it.box.left }
                    minOf(aw, bw) <= b.minOf { it.box.left } - a.maxOf { it.box.right }
                }
                if (cells && aligned * 2 >= all.size) out += rows(all)
                else (if (rtl) band.sortedByDescending { b -> b.maxOf { it.box.right } } else band.sortedBy { b -> b.minOf { it.box.left } })
                    .forEach { out += rows(it) }
            }
            return out
        }

        private fun isMostlyRtl(lines: List<Line>): Boolean {
            var rtl = 0; var all = 0
            for (l in lines) for (ch in l.text) if (Character.isLetter(ch)) {
                all++
                val d = Character.getDirectionality(ch)
                if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) rtl++
            }
            return rtl * 2 > all
        }

        /** Latin letters drawn the same as a Cyrillic one. */
        private val LOOKALIKE = mapOf(
            'A' to 'А', 'a' to 'а', 'B' to 'В', 'C' to 'С', 'c' to 'с', 'E' to 'Е', 'e' to 'е',
            'H' to 'Н', 'K' to 'К', 'k' to 'к', 'M' to 'М', 'O' to 'О', 'o' to 'о', 'P' to 'Р',
            'p' to 'р', 'T' to 'Т', 'X' to 'Х', 'x' to 'х', 'y' to 'у', 'Y' to 'У'
        )
        private val WORD = Regex("\\p{L}+")
        private val WORD_OR_NUMBER = Regex("[\\p{L}\\p{N}]+")

        /**
         * A line read as Cyrillic with Latin look-alikes put back: Tesseract,
         * reading Russian with English beside it, sometimes gives a capital
         * word or a short one ("На", "ТРУДА") in Latin letters that look the
         * same, which a search for the Russian word wouldn't find. In a line
         * mostly in Cyrillic, a word that mixes the two, or is all look-alikes,
         * becomes Cyrillic; real Latin words ("PDF", "Zoom") keep a letter
         * Cyrillic doesn't share, and stay.
         */
        internal fun cyrillicLookalikes(text: String): String {
            val counts = lettersByScript(text)
            val cyr = counts[Character.UnicodeScript.CYRILLIC] ?: 0
            if (cyr == 0) return text
            val mostly = cyr * 2 > counts.values.sum()
            return WORD.replace(text) { m ->
                val w = m.value
                val latin = w.filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
                when {
                    latin.isEmpty() -> w
                    latin.any { it !in LOOKALIKE } -> w
                    latin.length < w.length || mostly -> w.map { LOOKALIKE[it] ?: it }.joinToString("")
                    else -> w
                }
            }
        }

        /**
         * Whether a line from the Latin model looks like its guess at another
         * script rather than real Latin text: most of its words switch case
         * inside them ("MuHHCTepcrBO") or carry digits among their letters
         * ("6aHOBa", "4epraMs"), as no English, French or German words do.
         */
        internal fun looksLikeLatinGuess(text: String): Boolean {
            val words = WORD_OR_NUMBER.findAll(text).map { it.value }.filter { w -> w.count { it.isLetter() } >= 2 && w.length >= 3 }.toList()
            if (words.isEmpty()) return false
            val odd = words.count { w ->
                val rest = w.drop(1).filter { it.isLetter() }
                (rest.any { it.isUpperCase() } && rest.any { it.isLowerCase() }) ||
                    (w.any { it.isDigit() } && w.count { it.isLetter() } >= 3)
            }
            return odd * 2 >= words.size
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
            // A dropped line read a little differently this time ("6aHOBa" then "6aH0Ba") still accounts for the old word.
            val byLength = (now + replaced).groupBy { it.length }
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
