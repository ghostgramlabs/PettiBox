package com.ghostgramlabs.pettibox.domain.model

import androidx.compose.ui.graphics.Color
import com.ghostgramlabs.pettibox.ui.theme.BeautyPink
import com.ghostgramlabs.pettibox.ui.theme.FinanceEmerald
import com.ghostgramlabs.pettibox.ui.theme.FitnessIndigo
import com.ghostgramlabs.pettibox.ui.theme.HomePurple
import com.ghostgramlabs.pettibox.ui.theme.IdeasMustard
import com.ghostgramlabs.pettibox.ui.theme.MusicCoral
import com.ghostgramlabs.pettibox.ui.theme.PersonalPink
import com.ghostgramlabs.pettibox.ui.theme.ReadLaterSky
import com.ghostgramlabs.pettibox.ui.theme.ReadingForest
import com.ghostgramlabs.pettibox.ui.theme.RecipeOrange
import com.ghostgramlabs.pettibox.ui.theme.ShoppingOrange
import com.ghostgramlabs.pettibox.ui.theme.StyleAmber
import com.ghostgramlabs.pettibox.ui.theme.TravelTeal
import com.ghostgramlabs.pettibox.ui.theme.WatchPlum
import com.ghostgramlabs.pettibox.ui.theme.WorkIndigo

data class CategoryPreset(
    val id: String,
    val name: String,
    val emoji: String,
    val colorHex: Long
)

object CategoryPalette {
    // Broad starter taxonomy for the save patterns people actually have:
    // things to watch/read, things to buy, life admin, inspiration, and
    // personal plans. Kept finite on purpose; the app should feel helpful,
    // not like it shipped with a pre-filled filing cabinet.
    val Defaults: List<CategoryPreset> = listOf(
        CategoryPreset("recipes",    "Recipes",    "\uD83C\uDF73", RecipeOrange.toLongHex()),
        CategoryPreset("travel",     "Travel",     "\u2708\uFE0F", TravelTeal.toLongHex()),
        CategoryPreset("fitness",    "Fitness",    "\uD83D\uDCAA", FitnessIndigo.toLongHex()),
        CategoryPreset("style",      "Style",      "\uD83D\uDC57", StyleAmber.toLongHex()),
        CategoryPreset("home",       "Home",       "\uD83C\uDFE1", HomePurple.toLongHex()),
        CategoryPreset("beauty",     "Beauty",     "\uD83E\uDDF4", BeautyPink.toLongHex()),
        CategoryPreset("read_later", "Read Later", "\uD83D\uDCD6", ReadLaterSky.toLongHex()),
        CategoryPreset("finance",    "Finance",    "\uD83D\uDCB0", FinanceEmerald.toLongHex()),
        CategoryPreset("music",      "Music",      "\uD83C\uDFB5", MusicCoral.toLongHex()),
        CategoryPreset("books",      "Books",      "\uD83D\uDCDA", ReadingForest.toLongHex()),
        CategoryPreset("food_spots", "Food Spots", "\uD83C\uDF55", RecipeOrange.toLongHex()),
        CategoryPreset("shopping",   "Shopping",   "\uD83D\uDECD\uFE0F", ShoppingOrange.toLongHex()),
        CategoryPreset("work",       "Work",       "\uD83D\uDCBC", WorkIndigo.toLongHex()),
        CategoryPreset("ideas",      "Ideas",      "\uD83D\uDCA1", IdeasMustard.toLongHex()),
        CategoryPreset("watch",      "Watch",      "\uD83C\uDFAC", WatchPlum.toLongHex()),
        CategoryPreset("learning",   "Learning",   "\uD83C\uDF93", ReadingForest.toLongHex()),
        CategoryPreset("health",     "Health",     "\uD83E\uDE7A", FitnessIndigo.toLongHex()),
        CategoryPreset("docs",       "Docs",       "\uD83D\uDCC4", ReadLaterSky.toLongHex()),
        CategoryPreset("personal",   "Personal",   "\uD83D\uDCDD", PersonalPink.toLongHex()),
        CategoryPreset("tech",       "Tech",       "\uD83D\uDCBB", WorkIndigo.toLongHex()),
        CategoryPreset("design",     "Design",     "\uD83C\uDFA8", StyleAmber.toLongHex()),
        CategoryPreset("gifts",      "Gifts",      "\uD83C\uDF81", BeautyPink.toLongHex()),
        CategoryPreset("places",     "Places",     "\uD83D\uDCCD", TravelTeal.toLongHex()),
        CategoryPreset("kids",       "Kids",       "\uD83E\uDDF8", HomePurple.toLongHex())
    )

    fun colorOf(hex: Long): Color = Color(hex)
}

private fun Color.toLongHex(): Long {
    val a = (alpha * 255).toInt() and 0xFF
    val r = (red * 255).toInt() and 0xFF
    val g = (green * 255).toInt() and 0xFF
    val b = (blue * 255).toInt() and 0xFF
    return ((a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
}
