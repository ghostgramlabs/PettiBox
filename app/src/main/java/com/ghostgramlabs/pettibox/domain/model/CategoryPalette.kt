package com.ghostgramlabs.pettibox.domain.model

import com.ghostgramlabs.pettibox.util.AppText

import com.ghostgramlabs.pettibox.R

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

    /** Each starter's name as a string resource, by id. */
    private val nameRes: Map<String, Int> = mapOf(
        "recipes" to R.string.starter_recipes,
        "travel" to R.string.starter_travel,
        "fitness" to R.string.starter_fitness,
        "style" to R.string.starter_style,
        "home" to R.string.starter_home,
        "beauty" to R.string.starter_beauty,
        "read_later" to R.string.starter_read_later,
        "finance" to R.string.starter_finance,
        "music" to R.string.starter_music,
        "books" to R.string.starter_books,
        "food_spots" to R.string.starter_food_spots,
        "shopping" to R.string.starter_shopping,
        "work" to R.string.starter_work,
        "ideas" to R.string.starter_ideas,
        "watch" to R.string.starter_watch,
        "learning" to R.string.starter_learning,
        "health" to R.string.starter_health,
        "docs" to R.string.starter_docs,
        "personal" to R.string.starter_personal,
        "tech" to R.string.starter_tech,
        "design" to R.string.starter_design,
        "gifts" to R.string.starter_gifts,
        "places" to R.string.starter_places,
        "kids" to R.string.starter_kids
    )

    /**
     * A collection's name as shown: a starter still called what it came with
     * is said in the app's language; a renamed or user-made one as written.
     * The stored name is never changed, so phones in different languages
     * syncing the same library don't keep renaming it for each other.
     */
    fun displayName(id: String, name: String): String {
        val preset = Defaults.firstOrNull { it.id == id } ?: return name
        if (name != preset.name) return name
        return nameRes[id]?.let { AppText.getString(it) } ?: name
    }
}

/** This collection's name in the app's language (see [CategoryPalette.displayName]). */
val com.ghostgramlabs.pettibox.data.local.CategoryEntity.displayName: String
    get() = CategoryPalette.displayName(id, name)

private fun Color.toLongHex(): Long {
    val a = (alpha * 255).toInt() and 0xFF
    val r = (red * 255).toInt() and 0xFF
    val g = (green * 255).toInt() and 0xFF
    val b = (blue * 255).toInt() and 0xFF
    return ((a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
}
