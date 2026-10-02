package com.ghostgramlabs.pettibox.ui.screens.save

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostgramlabs.pettibox.MainActivity
import com.ghostgramlabs.pettibox.data.preferences.AppLockPreferences
import com.ghostgramlabs.pettibox.data.reminders.ReminderAlarmReceiver
import com.ghostgramlabs.pettibox.ui.lock.AppLockGate
import com.ghostgramlabs.pettibox.ui.theme.PettiBoxTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Receives Android share intents (text/url/image/file/PDF) and opens the
 * Save bottom sheet over a transparent activity. Finishes itself once the
 * user saves or dismisses, so it never lands in the recents stack.
 */
@AndroidEntryPoint
// FragmentActivity (still a ComponentActivity) because BiometricPrompt needs one.
class ShareReceiverActivity : FragmentActivity() {
    @Inject lateinit var appLockPreferences: AppLockPreferences

    private var incomingShare by mutableStateOf<IncomingShare?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val incoming = IncomingShare.from(intent).copy(senderPackage = senderPackage())
        if (!incoming.hasAnything) {
            finish(); return
        }
        incomingShare = incoming

        setContent {
            val lockEnabled by appLockPreferences.enabled.map<Boolean, Boolean?> { it }
                .collectAsStateWithLifecycle(initialValue = null)
            incomingShare?.let { share ->
                PettiBoxTheme {
                    // The sheet lists collections and recent saves, so it
                    // sits behind App lock like the rest of the app.
                    AppLockGate(activity = this@ShareReceiverActivity, enabled = lockEnabled) {
                    SaveSheet(
                        incoming = share,
                        onDismiss = { finish() },
                        onSaved = { finish() },
                        // Duplicate "Open it" → hand off to the main app via
                        // the same deep-link MainActivity uses for reminder
                        // taps, then close this transparent share activity.
                        onOpenExisting = { id ->
                            val i = Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra(ReminderAlarmReceiver.EXTRA_OPEN_ITEM_ID, id)
                            }
                            startActivity(i)
                            finish()
                        }
                    )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = IncomingShare.from(intent).copy(senderPackage = senderPackage())
        if (!incoming.hasAnything) {
            finish()
        } else {
            incomingShare = incoming
        }
    }

    /** The sharing app's package, used only to label where a save came from. */
    private fun senderPackage(): String? =
        referrer?.takeIf { it.scheme == "android-app" }?.host
}
