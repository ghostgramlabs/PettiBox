package com.ghostgramlabs.pettibox.data.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataFetcher @Inject constructor() {

    /**
     * Pulls Open Graph / standard meta tags. Best-effort with a hard timeout —
     * the save flow must never hang on a slow page. Caller can fall back to
     * the URL's host as a title if this returns null.
     */
    suspend fun fetch(url: String): LinkMetadata? = withContext(Dispatchers.IO) {
        // YouTube often serves a consent/app shell with no og: tags, so its
        // official oEmbed answer (title + channel) is the reliable fallback.
        val oembed = withTimeoutOrNull(3_500L) { youtubeOEmbed(url) }
        withTimeoutOrNull(5_000L) {
            runCatching {
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Android) PettiBox/1.0")
                    .timeout(4_000)
                    .followRedirects(true)
                    .get()
                val imageUrl = doc.metaContent("og:image")
                    ?: doc.metaContent("twitter:image")
                    ?: youtubeThumbnail(url)

                LinkMetadata(
                    title = doc.metaContent("og:title")
                        ?: doc.metaContent("twitter:title")
                        ?: oembed?.first
                        ?: doc.title().takeIf { it.isNotBlank() },
                    description = doc.metaContent("og:description")
                        ?: doc.metaContent("description"),
                    imageUrl = imageUrl,
                    siteName = doc.metaContent("og:site_name"),
                    author = oembed?.second
                        ?: doc.metaContent("author")
                        ?: doc.metaContent("article:author")?.takeUnless { it.startsWith("http") }
                )
            }.getOrNull() ?: youtubeThumbnail(url)?.let { thumbnail ->
                LinkMetadata(
                    title = oembed?.first,
                    description = null,
                    imageUrl = thumbnail,
                    siteName = "YouTube",
                    author = oembed?.second
                )
            }
        } ?: youtubeThumbnail(url)?.let { thumbnail ->
            LinkMetadata(
                title = oembed?.first,
                description = null,
                imageUrl = thumbnail,
                siteName = "YouTube",
                author = oembed?.second
            )
        }
    }

    private fun org.jsoup.nodes.Document.metaContent(prop: String): String? {
        val byProp = select("meta[property=$prop]").attr("content")
        if (byProp.isNotBlank()) return byProp
        val byName = select("meta[name=$prop]").attr("content")
        return byName.ifBlank { null }
    }

    /** (title, channel) from YouTube's public oEmbed endpoint; og tags don't carry the channel. */
    private fun youtubeOEmbed(url: String): Pair<String?, String?>? {
        if (youtubeThumbnail(url) == null) return null
        return runCatching {
            val body = Jsoup.connect("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(url, "UTF-8"))
                .ignoreContentType(true)
                .timeout(3_000)
                .execute()
                .body()
            val json = org.json.JSONObject(body)
            json.optString("title").ifBlank { null } to json.optString("author_name").ifBlank { null }
        }.getOrNull()
    }

    private fun youtubeThumbnail(url: String): String? {
        val id = Regex("""(?:youtube\.com/(?:watch\?v=|shorts/|embed/)|youtu\.be/)([A-Za-z0-9_-]{11})""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        return "https://img.youtube.com/vi/$id/hqdefault.jpg"
    }
}
