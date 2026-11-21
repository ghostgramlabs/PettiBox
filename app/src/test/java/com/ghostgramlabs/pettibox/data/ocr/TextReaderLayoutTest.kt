package com.ghostgramlabs.pettibox.data.ocr

import com.ghostgramlabs.pettibox.data.ocr.TextReader.Box
import com.ghostgramlabs.pettibox.data.ocr.TextReader.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextReaderLayoutTest {
    private fun line(text: String, left: Int, top: Int, right: Int, h: Int = 34, conf: Float = 0.9f) =
        Line(text, Box(left, top, right, top + h), conf)

    private fun order(vararg lines: Line) = TextReader.readingOrder(lines.toList().shuffled(java.util.Random(7))).map { it.text }

    @Test fun a_letterhead_and_the_addressee_beside_it_are_each_read_whole() {
        // As on a Russian official letter: the two blocks' lines sit at offset heights.
        assertEquals(
            listOf("МИНИСТЕРСТВО ТРУДА", "И СОЦИАЛЬНОЙ ПОЛИТИКИ", "ПРИМОРСКОГО КРАЯ", "Руководителю краевого", "учреждения", "Уважаемый Иван Иванович!"),
            order(
                line("МИНИСТЕРСТВО ТРУДА", 260, 190, 660),
                line("И СОЦИАЛЬНОЙ ПОЛИТИКИ", 220, 236, 700),
                line("ПРИМОРСКОГО КРАЯ", 290, 282, 630),
                line("Руководителю краевого", 980, 220, 1350),
                line("учреждения", 980, 268, 1170),
                line("Уважаемый Иван Иванович!", 600, 520, 1050)
            )
        )
    }

    @Test fun two_blocks_of_text_side_by_side_are_read_whole_even_when_their_rows_line_up() {
        // Line boxes as read from an emulator scan of such a letter.
        assertEquals(
            listOf("МИНИСТЕРСТВО ТРУДА", "И СОЦИАЛЬНОЙ ПОЛИТИКИ", "ПРИМОРСКОГО КРАЯ", "ул. Светланская, д. 22", "Руководителю краевого", "государственного казённого", "учреждения"),
            order(
                Line("МИНИСТЕРСТВО ТРУДА", Box(217, 162, 574, 192), 0.97f),
                Line("И СОЦИАЛЬНОЙ ПОЛИТИКИ", Box(177, 197, 612, 228), 0.96f),
                Line("ПРИМОРСКОГО КРАЯ", Box(234, 240, 555, 264), 0.96f),
                Line("ул. Светланская, д. 22", Box(144, 296, 644, 318), 0.96f),
                Line("Руководителю краевого", Box(836, 193, 1174, 222), 0.97f),
                Line("государственного казённого", Box(836, 235, 1239, 261), 0.93f),
                Line("учреждения", Box(836, 274, 1010, 299), 0.96f)
            )
        )
    }

    @Test fun a_receipt_is_read_row_by_row() {
        assertEquals(
            listOf("Milk", "2.40", "Bread", "1.10", "Total", "3.50"),
            order(
                line("Milk", 40, 100, 140), line("2.40", 600, 102, 680),
                line("Bread", 40, 150, 160), line("1.10", 600, 149, 680),
                line("Total", 40, 200, 160), line("3.50", 600, 203, 680)
            )
        )
    }

    @Test fun a_paragraph_reads_top_down_and_a_signature_line_across() {
        assertEquals(
            listOf("first line of text", "second line", "Заместитель министра", "А. В. Петрова"),
            order(
                line("first line of text", 190, 100, 1400),
                line("second line", 190, 148, 600),
                line("Заместитель министра", 190, 400, 560),
                line("А. В. Петрова", 1200, 404, 1460)
            )
        )
    }

    @Test fun lines_a_few_pixels_apart_in_height_stay_on_one_row_left_to_right() {
        // The old fixed 24 px bands split these two (centres 47 and 49 straddled a band edge).
        assertEquals(listOf("Дата:", "12.03.2026"), order(line("Дата:", 40, 30, 140), line("12.03.2026", 160, 32, 400)))
    }

    @Test fun columns_of_arabic_are_read_right_to_left() {
        assertEquals(
            listOf("مرحبا بكم", "في المدينة", "شكرا"),
            order(line("مرحبا بكم", 900, 100, 1300), line("في المدينة", 900, 148, 1300), line("شكرا", 100, 120, 400))
        )
    }

    @Test fun a_tesseract_line_across_two_columns_is_cut_in_two_each_as_sure_as_its_words() {
        val parts = TextReader.splitAtGaps(listOf(
            line("И", 220, 236, 250, conf = 0.9f),
            line("СОЦИАЛЬНОЙ", 262, 236, 480, conf = 0.9f),
            line("ПОЛИТИКИ", 492, 236, 700, conf = 0.9f),
            line("Pykobo", 980, 220, 1200, conf = 0.3f),
            line("kpaeBoro", 1212, 220, 1350, conf = 0.3f)
        ))
        assertEquals(listOf("И СОЦИАЛЬНОЙ ПОЛИТИКИ", "Pykobo kpaeBoro"), parts.map { it.text })
        assertEquals(0.9f, parts[0].confidence, 0.001f)
        assertEquals(Box(220, 236, 700, 270), parts[0].box)
    }

    @Test fun words_in_scripts_without_spaces_join_without_one() {
        val words = listOf(line("ภาษา", 40, 100, 120), line("ไทย", 125, 100, 180), line("OK", 190, 100, 230), line("ดี", 240, 100, 270))
        assertEquals(listOf("ภาษาไทย OK ดี"), TextReader.splitAtGaps(words).map { it.text })
    }

    @Test fun a_rule_between_newspaper_columns_cuts_the_line_and_is_dropped() {
        // Tesseract on Pravda, 1937: "…сплачиваются | партия и народ…" with columns a word-space apart.
        val parts = TextReader.splitAtGaps(listOf(
            line("государства.", 40, 100, 260), line("Продолжая", 272, 100, 450),
            line("|", 462, 98, 468),
            line("партия", 480, 100, 600), line("и", 612, 100, 630), line("народ", 642, 100, 760)
        ))
        assertEquals(listOf("государства. Продолжая", "партия и народ"), parts.map { it.text })
    }

    @Test fun ordinary_word_spaces_dont_cut_a_line() {
        val words = listOf(line("Просим", 190, 100, 330), line("в", 345, 100, 365), line("срок", 380, 100, 470))
        assertEquals(listOf("Просим в срок"), TextReader.splitAtGaps(words).map { it.text })
    }

    @Test fun latin_lookalikes_in_a_russian_line_become_cyrillic() {
        assertEquals("Приложение: На 3 л. в 1 экз.", TextReader.cyrillicLookalikes("Приложение: Ha 3 л. в 1 экз."))
        assertEquals("МИНИСТЕРСТВО ТРУДА", TextReader.cyrillicLookalikes("MИНИCTEPCTBO ТРУДА")) // mixed in one word
        assertEquals("ООО «Ромашка»", TextReader.cyrillicLookalikes("OOO «Ромашка»"))
    }

    @Test fun real_latin_words_and_latin_lines_stay_as_they_are() {
        assertEquals("Отчёт в PDF и Zoom", TextReader.cyrillicLookalikes("Отчёт в PDF и Zoom"))
        assertEquals("XXI век", TextReader.cyrillicLookalikes("XXI век")) // I has no look-alike
        assertEquals("MOCKBA office HOTEL", TextReader.cyrillicLookalikes("MOCKBA office HOTEL")) // no Cyrillic at all
        assertEquals("Call Mom about the Отчёт", TextReader.cyrillicLookalikes("Call Mom about the Отчёт")) // mostly Latin: stays
    }

    @Test fun the_latin_models_guesses_at_cyrillic_are_told_from_real_latin_text() {
        // As ML Kit read a 1953 Russian newspaper.
        assertTrue(TextReader.looksLikeLatinGuess("6aHOBa MBana I'puropbeBH4a a"))
        assertTrue(TextReader.looksLikeLatinGuess("O6beAHHATb MuHHCTepcrBO aBTOMOĞnJbHOŘ"))
        assertTrue(TextReader.looksLikeLatinGuess("KOYerAPKA"))
        assertFalse(TextReader.looksLikeLatinGuess("Invoice No. 4471 for Acme GmbH"))
        assertFalse(TextReader.looksLikeLatinGuess("Meeting with the iPhone team on Monday"))
        assertFalse(TextReader.looksLikeLatinGuess("TOTAL DUE 12.50"))
        assertFalse(TextReader.looksLikeLatinGuess("12.03.2026"))
    }

    @Test fun text_too_long_for_a_save_is_cut_at_a_line_end() {
        val text = "first line\n" + "x".repeat(30) + "\nlast"
        assertEquals("first line", TextReader.capped(text, 20))
        assertEquals(text, TextReader.capped(text, 1000))
        assertEquals("abcde", TextReader.capped("abcdefgh", 5)) // no line end: cut where it must
        assertEquals("abcd", TextReader.capped("abcd😀xyz", 5)) // never half an emoji
    }

    @Test fun an_old_misreading_read_a_little_differently_this_time_is_accounted_for() {
        val old = "6aHOBa MBana\nМинистерство"
        val new = Reading("Иванова Ивана\nМинистерство", replaced = "6aH0Ba MBaHa")
        assertEquals(emptyList<String>(), TextReader.unaccountedWords(old, new))
    }
}
