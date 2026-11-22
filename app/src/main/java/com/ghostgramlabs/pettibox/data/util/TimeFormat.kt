package com.ghostgramlabs.pettibox.data.util

import android.text.format.DateFormat
import com.ghostgramlabs.pettibox.R
import com.ghostgramlabs.pettibox.util.AppText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFormat {
    /** "just now", "5m ago", "3h ago", "2d ago", then a date, in the app's language. */
    fun relative(ts: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - ts
        return when {
            diff < 60_000 -> AppText.getString(R.string.time_just_now)
            diff < 3_600_000 -> (diff / 60_000).toInt().let { AppText.plural(R.plurals.time_minutes_ago, it) }
            diff < 86_400_000 -> (diff / 3_600_000).toInt().let { AppText.plural(R.plurals.time_hours_ago, it) }
            diff < 604_800_000 -> (diff / 86_400_000).toInt().let { AppText.plural(R.plurals.time_days_ago, it) }
            else -> {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
                val curYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                date(ts, if (cal.get(java.util.Calendar.YEAR) == curYear) "MMMd" else "yMMMd")
            }
        }
    }

    /** [ts] with the app language's own arrangement of [skeleton] (e.g. "MMMd" → "Mar 4", "4 мар."). */
    fun date(ts: Long, skeleton: String): String {
        val locale = Locale.getDefault()
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(ts))
    }
}
