package com.ghostgramlabs.pettibox.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TextUtilsTest {
    @Test
    fun urlDedupeKeyIgnoresSchemeAndHostCase() {
        assertEquals(
            TextUtils.urlDedupeKey("https://example.com/page"),
            TextUtils.urlDedupeKey("HTTPS://Example.COM/page")
        )
    }

    @Test
    fun urlDedupeKeyKeepsPathQueryAndFragmentCase() {
        assertNotEquals(
            TextUtils.urlDedupeKey("https://example.com/CaseSensitive"),
            TextUtils.urlDedupeKey("https://example.com/casesensitive")
        )
        assertNotEquals(
            TextUtils.urlDedupeKey("https://example.com/?id=AbC"),
            TextUtils.urlDedupeKey("https://example.com/?id=abc")
        )
        assertEquals("https://example.com/Path?Q=1#Frag", TextUtils.urlDedupeKey("  HTTPS://EXAMPLE.com/Path?Q=1#Frag "))
    }

    @Test
    fun urlDedupeKeyHandlesHostOnlyAndNonUrls() {
        assertEquals("https://example.com", TextUtils.urlDedupeKey("https://Example.com"))
        assertEquals("not a url", TextUtils.urlDedupeKey("not a url"))
    }
}
