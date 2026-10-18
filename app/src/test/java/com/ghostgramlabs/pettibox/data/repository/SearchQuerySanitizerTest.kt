package com.ghostgramlabs.pettibox.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchQuerySanitizerTest {
    @Test
    fun sanitizeFtsQueryKeepsUsefulTokensAndPrefixesThem() {
        assertEquals(
            "recipe* pdf*",
            SearchQuerySanitizer.sanitizeFtsQuery(" recipe: pdf! ")
        )
    }

    @Test
    fun sanitizeFtsQueryDropsOneLetterNoise() {
        assertEquals(
            "AI* notes*",
            SearchQuerySanitizer.sanitizeFtsQuery("a AI & notes")
        )
    }

    @Test
    fun sanitizeFtsQueryKeepsVowelSignsOfIndicScripts() {
        assertEquals("മലയാളം* हिन्दी*", SearchQuerySanitizer.sanitizeFtsQuery("മലയാളം, हिन्दी"))
    }

    @Test
    fun containsPatternOnlyForScriptsWithoutSpaces() {
        assertEquals("%北京%", SearchQuerySanitizer.containsPattern(" 北京 "))
        assertEquals("%ภาษาไทย%", SearchQuerySanitizer.containsPattern("ภาษาไทย"))
        assertEquals("%50\\%割引%", SearchQuerySanitizer.containsPattern("50%割引"))
        assertEquals(null, SearchQuerySanitizer.containsPattern("Москва"))
        assertEquals(null, SearchQuerySanitizer.containsPattern("recipe"))
    }
}
