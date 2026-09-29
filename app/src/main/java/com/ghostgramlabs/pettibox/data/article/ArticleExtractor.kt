package com.ghostgramlabs.pettibox.data.article

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads a page and pulls out its readable article (Mozilla Readability
 * via Readability4J), then sanitizes it down to text formatting only — no
 * scripts, styles, images, or embeds — so the stored copy is small and
 * safe to render offline.
 */
@Singleton
class ArticleExtractor @Inject constructor() {

    sealed interface Outcome {
        data class Article(val html: String, val text: String, val byline: String?, val wordCount: Int) : Outcome
        /** Won't work now or later (paywall, missing page, not an article). */
        data class Failed(val reason: String) : Outcome
        /** Site type that never has a readable article (video, social). */
        data object Unsupported : Outcome
        /** Network trouble or a server hiccup; worth retrying. */
        data object TryLater : Outcome
    }

    suspend fun extract(url: String): Outcome = withContext(Dispatchers.IO) {
        if (!isSupported(url)) return@withContext Outcome.Unsupported
        val response = try {
            Jsoup.connect(url)
                // A mainstream browser UA: plenty of sites serve a stripped
                // or blocked page to anything that looks like a bot.
                .userAgent(USER_AGENT)
                .timeout(20_000)
                .maxBodySize(MAX_PAGE_BYTES)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .ignoreContentType(true)
                .execute()
        } catch (e: IOException) {
            return@withContext Outcome.TryLater
        }

        when (val code = response.statusCode()) {
            in 200..299 -> Unit
            404, 410 -> return@withContext Outcome.Failed("The page no longer exists")
            401, 402, 403, 451 -> return@withContext Outcome.Failed("This site doesn't allow saving a copy")
            429, in 500..599 -> return@withContext Outcome.TryLater
            else -> return@withContext Outcome.Failed("The site answered with an error ($code)")
        }
        val contentType = response.contentType().orEmpty().lowercase()
        if (contentType.isNotEmpty() && "html" !in contentType) return@withContext Outcome.Unsupported

        val finalUrl = response.url().toString()
        // runCatching also traps Errors (e.g. a jsoup API mismatch or OOM on
        // a monster page), which must degrade to "no copy", never a crash.
        val article = runCatching { Readability4J(finalUrl, response.body()).parse() }.getOrNull()
            ?: return@withContext Outcome.Failed("Couldn't find an article on this page")
        val rawHtml = article.content
        val text = article.textContent?.trim().orEmpty()
        if (rawHtml.isNullOrBlank() || text.length < MIN_ARTICLE_CHARS) {
            return@withContext Outcome.Failed("Couldn't find an article on this page")
        }

        // Absolute links (so they still open from the reader), text markup
        // only. Images are left out on purpose: they'd multiply storage
        // many times over, and an offline copy can't load them anyway.
        val clean = Jsoup.clean(rawHtml, finalUrl, SAFELIST)
        // Android can't read a database row past ~2 MB (CursorWindow), and a
        // copy that big is a book, not an article. Refuse rather than store
        // something that could crash the screen that opens it.
        if (clean.utf8Size() > MAX_HTML_BYTES || text.utf8Size() > MAX_TEXT_BYTES) {
            return@withContext Outcome.Failed("This page is too long to keep a copy of")
        }
        Outcome.Article(
            html = clean,
            text = text,
            byline = article.byline?.trim()?.takeIf { it.isNotEmpty() && it.length < 120 },
            wordCount = text.split(WHITESPACE).count { it.isNotEmpty() }
        )
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/128.0.0.0 Mobile Safari/537.36"
        private const val MAX_PAGE_BYTES = 3 * 1024 * 1024
        private const val MAX_HTML_BYTES = 512 * 1024
        private const val MAX_TEXT_BYTES = 256 * 1024
        /** Below this it's a stub, a login wall, or a cookie notice — not an article. */
        private const val MIN_ARTICLE_CHARS = 400
        private val WHITESPACE = Regex("\\s+")

        private val SAFELIST: Safelist = Safelist.relaxed()
            .removeTags("img")
            .addTags("figure", "figcaption", "hr", "s", "del", "ins", "mark", "abbr", "time")

        /**
         * Hosts whose pages are video players, feeds, or app shells: a
         * reader copy of them is empty or a login wall, so we don't try —
         * and the UI doesn't offer it — rather than show a failure.
         */
        private val UNSUPPORTED_HOSTS = setOf(
            "youtube.com", "youtu.be", "instagram.com", "tiktok.com", "x.com", "twitter.com",
            "facebook.com", "fb.watch", "threads.net", "pinterest.com", "pin.it",
            "spotify.com", "music.apple.com", "open.spotify.com", "vimeo.com", "twitch.tv",
            "maps.google.com", "maps.app.goo.gl", "goo.gl", "play.google.com", "apps.apple.com",
            "linkedin.com", "snapchat.com", "whatsapp.com", "wa.me", "t.me", "discord.com", "discord.gg"
        )

        private val FILE_EXTENSIONS = setOf(
            "pdf", "jpg", "jpeg", "png", "gif", "webp", "mp4", "mp3", "zip", "apk", "doc", "docx"
        )

        private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

        /** Whether a copy is worth attempting for this URL at all. */
        fun isSupported(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
            if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
            val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return false
            if (UNSUPPORTED_HOSTS.any { host == it || host.endsWith(".$it") }) return false
            val ext = uri.path.orEmpty().substringAfterLast('/').substringAfterLast('.', "").lowercase()
            return ext !in FILE_EXTENSIONS
        }
    }
}
