package com.ghostgramlabs.pettibox.data.repository

internal object SearchQuerySanitizer {
    /**
     * Sanitises raw user input for SQLite's FTS MATCH grammar. Strips control
     * chars, requires tokens of >=2 chars, appends a prefix wildcard so partial
     * words match. Returning a blank string short-circuits the search to an
     * empty result without ever touching the DB.
     */
    fun sanitizeFtsQuery(input: String): String {
        // \p{M}: the vowel signs of Hindi, Malayalam, Tamil… are part of the word.
        val cleaned = input.trim().replace("\"", " ").replace(Regex("[^\\p{L}\\p{M}\\p{N} ]+"), " ")
        val tokens = cleaned.split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" ") { "$it*" }
    }

    /** Scripts written without spaces between words: a word can sit anywhere in a run of text. */
    private val NO_SPACES = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR
    )

    /**
     * For a query in Chinese, Japanese, Thai and other scripts written
     * without spaces, a LIKE pattern (escaped with '\') finding it anywhere
     * in a title, note or image text: the index only finds words from their
     * start. Null for every other query.
     */
    fun containsPattern(input: String): String? {
        val q = input.trim()
        if (q.isEmpty() || q.codePoints().noneMatch { Character.UnicodeScript.of(it) in NO_SPACES }) return null
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }
}
