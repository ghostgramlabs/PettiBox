package com.ghostgramlabs.pettibox.data.ocr

import java.lang.Character.UnicodeScript

/**
 * Languages whose text PettiBox can read in images and PDFs, beyond the
 * Latin alphabet (English, Spanish, Portuguese, French, German, Indonesian,
 * Vietnamese, Turkish…), which is always read.
 *
 * Each one is read by Google's ML Kit (fetched by Play services when turned
 * on) or by Tesseract (its model downloaded once when turned on). Both run on
 * the phone: images never leave it.
 */
enum class OcrEngine { MLKIT_DEVANAGARI, MLKIT_CHINESE, MLKIT_JAPANESE, MLKIT_KOREAN, TESSERACT }

data class OcrLanguage(
    /** Stored id: the language's ISO 639-1 code. */
    val code: String,
    val name: String,
    /** The name in its own script, shown next to [name]. */
    val nativeName: String,
    val engine: OcrEngine,
    /** Tesseract model name (tessdata_fast), for [OcrEngine.TESSERACT]. */
    val tessCode: String? = null,
    /** Scripts this language is written in: how a reading is judged to be this language's. */
    val scripts: Set<UnicodeScript>,
    /** Phone language codes that turn it on by default (Android's old codes included). */
    val locales: Set<String> = setOf(code)
)

object OcrLanguages {
    private fun tess(code: String, name: String, native: String, tess: String, vararg scripts: UnicodeScript, locales: Set<String> = setOf(code)) =
        OcrLanguage(code, name, native, OcrEngine.TESSERACT, tess, scripts.toSet(), locales)

    private val DEVANAGARI = setOf(UnicodeScript.DEVANAGARI)

    /** In the order shown: by how many Android users read them, roughly, then grouped by region. */
    val all: List<OcrLanguage> = listOf(
        OcrLanguage("zh", "Chinese", "中文", OcrEngine.MLKIT_CHINESE, scripts = setOf(UnicodeScript.HAN)),
        OcrLanguage("hi", "Hindi", "हिन्दी", OcrEngine.MLKIT_DEVANAGARI, scripts = DEVANAGARI),
        tess("ar", "Arabic", "العربية", "ara", UnicodeScript.ARABIC),
        tess("bn", "Bengali", "বাংলা", "ben", UnicodeScript.BENGALI, locales = setOf("bn", "as")),
        tess("ru", "Russian", "Русский", "rus", UnicodeScript.CYRILLIC),
        OcrLanguage("ja", "Japanese", "日本語", OcrEngine.MLKIT_JAPANESE, scripts = setOf(UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA)),
        OcrLanguage("ko", "Korean", "한국어", OcrEngine.MLKIT_KOREAN, scripts = setOf(UnicodeScript.HANGUL)),
        OcrLanguage("mr", "Marathi", "मराठी", OcrEngine.MLKIT_DEVANAGARI, scripts = DEVANAGARI),
        OcrLanguage("ne", "Nepali", "नेपाली", OcrEngine.MLKIT_DEVANAGARI, scripts = DEVANAGARI),
        tess("te", "Telugu", "తెలుగు", "tel", UnicodeScript.TELUGU),
        tess("ta", "Tamil", "தமிழ்", "tam", UnicodeScript.TAMIL),
        tess("ml", "Malayalam", "മലയാളം", "mal", UnicodeScript.MALAYALAM),
        tess("kn", "Kannada", "ಕನ್ನಡ", "kan", UnicodeScript.KANNADA),
        tess("gu", "Gujarati", "ગુજરાતી", "guj", UnicodeScript.GUJARATI),
        tess("pa", "Punjabi", "ਪੰਜਾਬੀ", "pan", UnicodeScript.GURMUKHI),
        tess("or", "Odia", "ଓଡ଼ିଆ", "ori", UnicodeScript.ORIYA),
        tess("si", "Sinhala", "සිංහල", "sin", UnicodeScript.SINHALA),
        tess("ur", "Urdu", "اردو", "urd", UnicodeScript.ARABIC),
        tess("fa", "Persian", "فارسی", "fas", UnicodeScript.ARABIC),
        tess("uk", "Ukrainian", "Українська", "ukr", UnicodeScript.CYRILLIC),
        tess("th", "Thai", "ไทย", "tha", UnicodeScript.THAI),
        tess("my", "Burmese", "မြန်မာ", "mya", UnicodeScript.MYANMAR),
        tess("km", "Khmer", "ខ្មែរ", "khm", UnicodeScript.KHMER),
        tess("el", "Greek", "Ελληνικά", "ell", UnicodeScript.GREEK),
        tess("he", "Hebrew", "עברית", "heb", UnicodeScript.HEBREW, locales = setOf("he", "iw")),
    )

    private val byCode = all.associateBy { it.code }

    fun of(code: String): OcrLanguage? = byCode[code]

    /** The languages a phone set to [phoneLanguages] (ISO codes) should read without being asked. */
    fun defaultsFor(phoneLanguages: List<String>): Set<String> =
        phoneLanguages.flatMap { lang -> all.filter { lang.lowercase() in it.locales } }.mapTo(LinkedHashSet()) { it.code }

    /** Tesseract's English model, read alongside each Tesseract language so English words in the same image come out right. */
    const val TESS_ENGLISH = "eng"
}
