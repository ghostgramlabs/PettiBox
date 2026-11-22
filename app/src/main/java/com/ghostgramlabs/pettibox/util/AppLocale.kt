package com.ghostgramlabs.pettibox.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's own language, chosen in Settings (or in the phone's per-app
 * language settings on Android 13+).
 *
 * On Android 13+ the platform's LocaleManager holds the choice, applies it
 * to every context (activities, workers, the widget) and recreates
 * activities when it changes. Below 13 the choice is kept here and applied to
 * each activity's base context in [wrapContext]; text built off the UI
 * (notifications, workers) uses [localizedContext].
 *
 * Language names are shown in their own script, so anyone can find theirs
 * whatever language the app is in now.
 */
object AppLocale {

    private const val PREFS = "app_locale"
    private const val KEY_TAG = "language_tag"

    /** The JVM default before any override, to go back to when "System" is picked again (below 13). */
    private val systemDefaultLocale: Locale = Locale.getDefault()

    /** (tag, name in its own script). An empty tag follows the phone's language. */
    val supported: List<Pair<String, String>> = listOf(
        "" to "System",
        "en" to "English",
        "es" to "Español",
        "de" to "Deutsch",
        "fr" to "Français",
        "id" to "Bahasa Indonesia",
        "tr" to "Türkçe",
        "ja" to "日本語",
        "hi" to "हिन्दी",
        "ar" to "العربية",
        "pt-BR" to "Português (Brasil)",
        "ru" to "Русский",
        "vi" to "Tiếng Việt",
        "bn" to "বাংলা",
        "te" to "తెలుగు",
        "ta" to "தமிழ்",
        "el" to "Ελληνικά",
        "ml" to "മലയാളം"
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The chosen language's tag, or "" when following the phone. */
    fun currentTag(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
            val tags = localeManager?.applicationLocales?.toLanguageTags().orEmpty()
            return if (tags.isBlank()) "" else matchSupported(tags.substringBefore(','))
        }
        return prefs(context).getString(KEY_TAG, "").orEmpty()
    }

    /**
     * The picker entry a platform tag selects ("es-US" or "pt-BR" from the
     * phone's settings): an exact regional match first, otherwise the bare
     * language.
     */
    internal fun matchSupported(tag: String): String {
        supported.firstOrNull { it.first.equals(tag, ignoreCase = true) }?.let { return it.first }
        val language = tag.substringBefore('-')
        if (language == "pt") return "pt-BR" // the only Portuguese the app has
        if (language == "in") return "id" // the old code some Android versions still give for Indonesian
        return language
    }

    /**
     * Keeps [tag] and applies it. Android 13+ recreates the activities itself;
     * below that [activity] is recreated so [wrapContext] applies it.
     */
    fun set(activity: Activity, tag: String) {
        if (tag == currentTag(activity)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = activity.getSystemService(android.app.LocaleManager::class.java)
            localeManager?.applicationLocales =
                if (tag.isBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        prefs(activity).edit().putString(KEY_TAG, tag).apply()
        activity.recreate()
    }

    /**
     * An activity's base context in the chosen language (and its layout
     * direction). Call from attachBaseContext. Below 13 it also sets the JVM
     * default, so dates and numbers formatted on screen match; background code
     * uses [localizedContext] instead.
     */
    fun wrapContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val locale = selectedLocale(base)
        if (locale == null) {
            Locale.setDefault(systemDefaultLocale)
            return base
        }
        Locale.setDefault(locale)
        return base.withLocale(locale)
    }

    /**
     * A context whose text is in the chosen language, for text built off the
     * UI (notifications, workers, the widget). [base] itself on Android 13+ or
     * when following the phone.
     */
    fun localizedContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val locale = selectedLocale(base) ?: return base
        return base.withLocale(locale)
    }

    private fun selectedLocale(context: Context): Locale? {
        // Unreadable prefs mean "follow the phone": never fail anything over its language.
        val tag = runCatching { prefs(context).getString(KEY_TAG, "").orEmpty() }.getOrDefault("")
        return if (tag.isBlank()) null else Locale.forLanguageTag(tag)
    }

    private fun Context.withLocale(locale: Locale): Context {
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return createConfigurationContext(config)
    }

    /** The activity behind a Compose context, if any. */
    fun activityFrom(context: Context): Activity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}

/** This context with text in the app's chosen language (see [AppLocale.localizedContext]). */
fun Context.localized(): Context = AppLocale.localizedContext(this)
