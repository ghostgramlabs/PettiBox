package com.ghostgramlabs.pettibox.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.Character.UnicodeScript

class TextReaderScriptTest {
    private val cyrillic = setOf(UnicodeScript.CYRILLIC)
    private val malayalam = setOf(UnicodeScript.MALAYALAM)

    @Test fun a_line_mostly_in_the_script_counts_however_short() {
        assertTrue(TextReader.lineInScript("Привет", cyrillic) > 0)
        assertTrue(TextReader.lineInScript("天气", setOf(UnicodeScript.HAN)) > 0)
        assertTrue(TextReader.lineInScript("മലയാളം ഭാഷ", malayalam) > 0)
    }

    @Test fun made_up_letters_in_a_latin_line_dont() {
        assertEquals(0, TextReader.lineInScript("Meeting at ten д", cyrillic))
        assertEquals(0, TextReader.lineInScript("ഷി area chy ണ്ണ", malayalam)) // what a model makes of a script it doesn't know
        assertEquals(0, TextReader.lineInScript("д", cyrillic)) // one letter isn't enough
    }

    private val latin = setOf(UnicodeScript.LATIN)

    @Test fun a_first_reading_is_always_taken() {
        assertTrue(TextReader.improves("Hello", null, latin))
        assertFalse(TextReader.improves("", null, latin))
    }

    @Test fun text_in_a_language_turned_off_since_is_kept() {
        // Russian was read before; now only Latin can be: the Latin guess must not replace it.
        assertFalse(TextReader.improves("IpuBeT, Mup! BcTpe4a 3aBTpa", "Привет, мир! Встреча завтра", latin))
    }

    @Test fun a_reading_in_a_language_just_turned_on_replaces_a_latin_guess() {
        assertTrue(TextReader.improves("Привет, мир! Встреча завтра", "IpuBeT, Mup! BcTpe4a 3aBTpa", latin + cyrillic))
    }

    @Test fun a_shorter_reading_doesnt_make_words_disappear() {
        val full = "Page one words here\n\nPage two more words\n\nPage three final words"
        assertFalse(TextReader.improves("Page one words here", full, latin)) // fewer PDF pages
        assertTrue(TextReader.improves(full + "\n\nPage four", full, latin)) // more pages
        assertTrue(TextReader.improves("Grocery receipt total", "Grocerv receipt tota1", latin)) // a better English reading
    }

    @Test fun junk_of_the_same_length_doesnt_replace_real_text() {
        val real = "Invoice reference ALPHA paid on Monday"
        assertFalse(TextReader.improves("Xqzvwk plmnbt RRTYUQ lkjh ghfd Wxyzab", real, latin))
    }

    @Test fun indic_readings_come_out_as_keyboards_type_them() {
        assertEquals("കേരളത്തിൽ", TextReader.normalizeIndic("കേരളത്തില്‍"))
        assertEquals("தமிழ் மொழி", TextReader.normalizeIndic("தமிழ்‌ மொழி"))
        assertEquals("می‌خواهم", TextReader.normalizeIndic("می‌خواهم")) // Persian keeps its joiner
    }
}
