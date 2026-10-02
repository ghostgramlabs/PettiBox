package com.ghostgramlabs.pettibox.data.util

import java.util.regex.Pattern

object TextUtils {

    private val URL = Pattern.compile(
        "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)",
        Pattern.CASE_INSENSITIVE
    )

    fun extractFirstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = URL.matcher(text)
        return if (m.find()) m.group(1) else null
    }

    fun smartTitle(text: String?, fallback: String): String {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return fallback
        val firstLine = t.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (firstLine.length <= 80) return firstLine.ifBlank { fallback }
        return firstLine.take(77) + "…"
    }

    /**
     * Key for "is this the same link?" checks. Scheme and host are
     * case-insensitive, so they're lowercased; the path, query and fragment
     * can name different pages by case alone (/Report vs /report), so they
     * are kept exactly.
     */
    fun urlDedupeKey(url: String): String {
        val t = url.trim()
        val schemeEnd = t.indexOf("://")
        if (schemeEnd <= 0) return t
        val authorityStart = schemeEnd + 3
        val authorityEnd = t.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) t.length else it }
        return t.substring(0, authorityEnd).lowercase() + t.substring(authorityEnd)
    }

    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            java.net.URI(url).host?.removePrefix("www.")
        }.getOrNull()
    }
}
