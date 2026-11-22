package com.ghostgramlabs.pettibox.data.reminders

import com.ghostgramlabs.pettibox.util.AppText

import com.ghostgramlabs.pettibox.R

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Single source of truth for the reminder notification channel.
 *
 * Channels are registered idempotently — the OS keeps the first
 * configuration around forever once a user sees a notification on it,
 * so any future copy/visibility change has to ship via a new channel id.
 */
object ReminderNotifications {
    const val CHANNEL_ID = "pettibox_reminders"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            AppText.getString(R.string.reminder_save_reminders),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = AppText.getString(R.string.reminder_nudges_things_asked_pettibox_remind)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }
}
