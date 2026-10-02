package com.ghostgramlabs.pettibox.domain.model

enum class SourceApp(val displayName: String, val emoji: String) {
    INSTAGRAM("Instagram", "\uD83D\uDCF7"),
    REDDIT("Reddit", "\uD83D\uDC7D"),
    YOUTUBE("YouTube", "\u25B6"),
    CHROME("Chrome", "\uD83C\uDF10"),
    // Any other web link. Used to be labelled Chrome, which was wrong for
    // links from Firefox, Samsung Internet, mail apps, imports, etc.
    WEB("Web", "\uD83C\uDF10"),
    MAPS("Maps", "\uD83D\uDCCD"),
    WHATSAPP("WhatsApp", "\uD83D\uDCAC"),
    TWITTER("X / Twitter", "\uD83D\uDC26"),
    PINTEREST("Pinterest", "\uD83D\uDCCC"),
    SPOTIFY("Spotify", "\uD83C\uDFB5"),
    AMAZON("Amazon", "\uD83D\uDCE6"),
    FILES("Files", "\uD83D\uDCC1"),
    // Auto-captured by the clipboard-on-foreground flow. Tagged at the
    // source rather than via category_id so users can still file these
    // into any of their own collections without losing the "captured
    // from clipboard" provenance.
    CLIPBOARD("Clipboard", "\uD83D\uDCCB"),
    UNKNOWN("Other", "\u2728");

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
