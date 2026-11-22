package com.ghostgramlabs.pettibox.domain.model

import com.ghostgramlabs.pettibox.R
import com.ghostgramlabs.pettibox.util.AppText

/** [brand] is shown as is; a generic source has [labelRes], said in the app's language. */
enum class SourceApp(private val brand: String, val emoji: String, private val labelRes: Int? = null) {
    INSTAGRAM("Instagram", "\uD83D\uDCF7"),
    REDDIT("Reddit", "\uD83D\uDC7D"),
    YOUTUBE("YouTube", "\u25B6"),
    CHROME("Chrome", "\uD83C\uDF10"),
    // Any other web link. Used to be labelled Chrome, which was wrong for
    // links from Firefox, Samsung Internet, mail apps, imports, etc.
    WEB("Web", "\uD83C\uDF10", R.string.source_web),
    MAPS("Maps", "\uD83D\uDCCD", R.string.source_maps),
    WHATSAPP("WhatsApp", "\uD83D\uDCAC"),
    TWITTER("X / Twitter", "\uD83D\uDC26"),
    PINTEREST("Pinterest", "\uD83D\uDCCC"),
    SPOTIFY("Spotify", "\uD83C\uDFB5"),
    AMAZON("Amazon", "\uD83D\uDCE6"),
    FILES("Files", "\uD83D\uDCC1", R.string.source_files),
    // Auto-captured by the clipboard-on-foreground flow. Tagged at the
    // source rather than via category_id so users can still file these
    // into any of their own collections without losing the "captured
    // from clipboard" provenance.
    CLIPBOARD("Clipboard", "\uD83D\uDCCB", R.string.source_clipboard),
    UNKNOWN("Other", "\u2728", R.string.source_other);

    val displayName: String get() = labelRes?.let { AppText.getString(it) } ?: brand

    companion object {
        fun fromUrl(url: String?): SourceApp {
            if (url.isNullOrBlank()) return UNKNOWN
            val u = url.lowercase()
            return when {
                "instagram.com" in u -> INSTAGRAM
                "reddit.com" in u || "redd.it" in u -> REDDIT
                "youtube.com" in u || "youtu.be" in u -> YOUTUBE
                "twitter.com" in u || "x.com" in u -> TWITTER
                "pinterest." in u -> PINTEREST
                "spotify.com" in u -> SPOTIFY
                "amazon." in u -> AMAZON
                "google.com/maps" in u || "maps.app.goo.gl" in u || "goo.gl/maps" in u -> MAPS
                "wa.me" in u || "whatsapp.com" in u -> WHATSAPP
                u.startsWith("http") -> WEB
                else -> UNKNOWN
            }
        }

        /** The app a share came from, when it's one we have a label for. */
        fun fromPackage(pkg: String?): SourceApp? = when {
            pkg.isNullOrBlank() -> null
            pkg == "com.android.chrome" || pkg.startsWith("com.chrome.") -> CHROME
            pkg == "com.instagram.android" -> INSTAGRAM
            pkg == "com.reddit.frontpage" -> REDDIT
            pkg == "com.google.android.youtube" -> YOUTUBE
            pkg == "com.google.android.apps.maps" -> MAPS
            pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b" -> WHATSAPP
            pkg == "com.twitter.android" -> TWITTER
            pkg == "com.pinterest" -> PINTEREST
            pkg == "com.spotify.music" -> SPOTIFY
            pkg.startsWith("com.amazon.mShop") -> AMAZON
            else -> null
        }
    }
}
