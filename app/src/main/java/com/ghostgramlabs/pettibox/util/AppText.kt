package com.ghostgramlabs.pettibox.util

import android.content.Context
import android.content.res.Resources

/**
 * Text in the app's language for code that has no Context of its own:
 * helpers that format times and labels, view models, workers and
 * notifications. Set up once in PettiBoxApp.onCreate.
 *
 * On Android 13+ the application's resources already follow the app
 * language; below 13 they're the phone's, so a context in the chosen
 * language is made (and kept until the choice changes).
 */
object AppText {
    @Volatile private var app: Context? = null
    @Volatile private var cached: Pair<String, Resources>? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    val res: Resources
        get() {
            val base = app ?: error("AppText.init wasn't called")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) return base.resources
            val tag = AppLocale.currentTag(base)
            cached?.let { (t, r) -> if (t == tag) return r }
            return AppLocale.localizedContext(base).resources.also { cached = tag to it }
        }

    fun getString(id: Int): String = res.getString(id)

    fun getString(id: Int, vararg args: Any?): String = res.getString(id, *args)

    fun plural(id: Int, count: Int, vararg args: Any?): String =
        res.getQuantityString(id, count, *(if (args.isEmpty()) arrayOf<Any?>(count) else args))
}
