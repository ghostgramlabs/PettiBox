package com.ghostgramlabs.pettibox.data.ocr

import java.lang.Character.UnicodeScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for review finding O3: adding a script must not erase useful existing text. */
class OcrRetentionRegressionTest {
    @Test fun adding_a_script_does_not_allow_dropping_an_existing_invoice_line() {
        val retained = "Invoice reference ALPHA paid on Monday. Delivery address is London. "
        val old = retained + "Warranty code ZEBRA."
        val new = retained + "天气"
        assertFalse(
            "Adding Chinese must not allow dropping the existing warranty code from search",
            TextReader.improves(new, old, setOf(UnicodeScript.LATIN, UnicodeScript.HAN))
        )
    }

    @Test fun a_new_script_does_not_justify_discarding_existing_page_text() {
        val old = "Invoice reference ALPHA paid on Monday. " +
            "Delivery address and warranty terms must remain searchable."
        val new = "天气"
        assertFalse(
            "A two-character reading must not replace an entire useful invoice just because Chinese was enabled",
            TextReader.improves(new, old, setOf(UnicodeScript.LATIN, UnicodeScript.HAN))
        )
    }

    private val latinHan = setOf(UnicodeScript.LATIN, UnicodeScript.HAN)

    @Test fun a_reading_with_new_words_is_merged_so_nothing_is_lost() {
        val old = "Invoice reference ALPHA paid on Monday.\nWarranty code ZEBRA."
        val merged = TextReader.merged(Reading("Invoice reference ALPHA paid on Monday. 天气"), old, latinHan)!!
        assertTrue(merged, merged.contains("ZEBRA") && merged.contains("天气") && merged.contains("ALPHA"))
        // Only the old line it would lose is carried over, not the whole old text again.
        assertEquals(1, Regex("ALPHA").findAll(merged).count())
    }

    @Test fun a_short_label_in_a_new_script_is_merged_not_dropped() {
        val old = "Invoice reference ALPHA paid on Monday. Delivery address and warranty terms must remain searchable."
        val merged = TextReader.merged(Reading("天气"), old, latinHan)!!
        assertTrue(merged.startsWith("天气") && merged.contains("warranty terms"))
    }

    @Test fun junk_from_an_older_reading_no_longer_blocks_the_right_text() {
        // Read sideways before: junk. Read the right way up now: the real words.
        val old = "Iuvoxe rrfqnce PELlCAN"
        val new = Reading("Grocery receipt PELICAN\nTotal paid 42.50 on Tuesday")
        assertFalse(TextReader.improves(new, old, latinHan))
        val merged = TextReader.merged(new, old, latinHan)!!
        assertTrue(merged, merged.contains("Grocery receipt") && merged.contains("Tuesday") && merged.contains("Iuvoxe"))
    }

    @Test fun merging_again_with_the_same_reading_changes_nothing() {
        val old = "Iuvoxe rrfqnce\nsomething else"
        val new = Reading("Grocery receipt PELICAN")
        val once = TextReader.merged(new, old, latinHan)!!
        assertNull(TextReader.merged(new, once, latinHan))
    }

    @Test fun a_reading_with_nothing_new_is_not_merged() {
        assertNull(TextReader.merged(Reading("Invoice"), "Invoice reference ALPHA paid on Monday", latinHan))
    }

    @Test fun text_in_a_language_turned_off_is_not_mixed_with_guesses() {
        val old = "Привет, мир! Встреча завтра"
        assertNull(TextReader.merged(Reading("IpuBeT, Mup! BcTpe4a 3aBTpa"), old, setOf(UnicodeScript.LATIN)))
    }
}
