package com.ghostgramlabs.pettibox.data.ocr

import java.lang.Character.UnicodeScript
import org.junit.Assert.assertFalse
import org.junit.Test

/** Regression coverage for review finding O3: adding a script must not erase useful existing text. */
class OcrRetentionRegressionTest {
    @Test fun a_new_script_does_not_justify_discarding_existing_page_text() {
        val old = "Invoice reference ALPHA paid on Monday. " +
            "Delivery address and warranty terms must remain searchable."
        val new = "天气"
        assertFalse(
            "A two-character reading must not replace an entire useful invoice just because Chinese was enabled",
            TextReader.improves(new, old, setOf(UnicodeScript.LATIN, UnicodeScript.HAN))
        )
    }
}
