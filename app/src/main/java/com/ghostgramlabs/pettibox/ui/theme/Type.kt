package com.ghostgramlabs.pettibox.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Display = system serif (Noto Serif on most Android builds). Pairing a
// quiet serif headline with a sans body is the signature most "indie /
// designed-by-a-person" apps land on (Bear, Things, Stash) — it instantly
// removes the "stock Material" feel without needing a bundled font asset.
// Body stays sans for legibility at small sizes.
private val Display = FontFamily.Serif
private val Body = FontFamily.SansSerif

// Languages whose letters can take the headlines' tightened (negative)
// letter spacing. Indic, Arabic and CJK text is shaped in joined clusters;
// squeezing it breaks the shaping and even lets a heading such as Hindi
// "ब्राउज़" wrap mid-word, so those languages get normal spacing.
private val TIGHT_SPACING_LANGUAGES = setOf(
    "en", "es", "de", "fr", "in", "id", "tr", "pt", "vi", "ru", "el"
)

/** The app typography for the given language (see [TIGHT_SPACING_LANGUAGES]). */
fun pettiBoxTypography(language: String): Typography {
    if (language in TIGHT_SPACING_LANGUAGES) return PettiBoxTypography
    val t = PettiBoxTypography
    fun TextStyle.plain() = copy(letterSpacing = 0.sp)
    return t.copy(
        displayLarge = t.displayLarge.plain(),
        displayMedium = t.displayMedium.plain(),
        displaySmall = t.displaySmall.plain(),
        headlineLarge = t.headlineLarge.plain(),
        headlineMedium = t.headlineMedium.plain(),
        headlineSmall = t.headlineSmall.plain(),
        titleLarge = t.titleLarge.plain(),
        labelLarge = t.labelLarge.plain(),
        labelMedium = t.labelMedium.plain(),
        labelSmall = t.labelSmall.plain()
    )
}

val PettiBoxTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Black,
        fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1.2).sp
    ),
    displayMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.8).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp
    ),
    // Material's AlertDialog renders its title in headlineSmall. We never
    // defined it, so dialog titles were falling back to stock Roboto — which
    // is what made every centred popup ("Save a link", "New collection", …)
    // read as a generic Android dialog instead of PettiBox. Defining it as a
    // serif headline gives every dialog the same editorial voice as the
    // screens, in both themes, from one place.
    headlineSmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Bold,
        fontSize = 18.sp, lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp
    )
)
