package com.ghostgramlabs.pettibox.ui.nav

import android.content.Context
import android.content.Intent
import com.ghostgramlabs.pettibox.MainActivity
import com.ghostgramlabs.pettibox.data.reminders.ReminderAlarmReceiver

/**
 * Where an outside entry point wants the app to land: a notification, a
 * home-screen widget tap, or a long-press app-icon shortcut. MainActivity
 * reads it from the launching intent and hands it to the nav graph once
 * navigation is ready.
 */
sealed interface AppLaunch {
    data class OpenItem(val id: Long) : AppLaunch
    data object Unread : AppLaunch
    data object Search : AppLaunch
    /** Home's "+" flows, opened directly. */
    data object NewNote : AppLaunch
    data object AddLink : AppLaunch
    data object AddChooser : AppLaunch

    companion object {
        // Literal strings: res/xml/shortcuts.xml names these actions too.
        const val ACTION_UNREAD = "com.ghostgramlabs.pettibox.action.UNREAD"
        const val ACTION_SEARCH = "com.ghostgramlabs.pettibox.action.SEARCH"
        const val ACTION_NEW_NOTE = "com.ghostgramlabs.pettibox.action.NEW_NOTE"
        const val ACTION_ADD_LINK = "com.ghostgramlabs.pettibox.action.ADD_LINK"
        const val ACTION_ADD = "com.ghostgramlabs.pettibox.action.ADD"

        fun from(intent: Intent?): AppLaunch? {
            intent ?: return null
            val id = intent.getLongExtra(ReminderAlarmReceiver.EXTRA_OPEN_ITEM_ID, -1L)
            if (id > 0L) return OpenItem(id)
            return when (intent.action) {
                ACTION_UNREAD -> Unread
                ACTION_SEARCH -> Search
                ACTION_NEW_NOTE -> NewNote
                ACTION_ADD_LINK -> AddLink
                ACTION_ADD -> AddChooser
                else -> null
            }
        }

        /** An intent that opens the app at [launch] (for notifications and widgets). */
        fun intent(context: Context, launch: AppLaunch): Intent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                when (launch) {
                    is OpenItem -> putExtra(ReminderAlarmReceiver.EXTRA_OPEN_ITEM_ID, launch.id)
                    Unread -> action = ACTION_UNREAD
                    Search -> action = ACTION_SEARCH
                    NewNote -> action = ACTION_NEW_NOTE
                    AddLink -> action = ACTION_ADD_LINK
                    AddChooser -> action = ACTION_ADD
                }
            }
    }
}
