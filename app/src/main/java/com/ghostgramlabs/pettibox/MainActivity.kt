package com.ghostgramlabs.pettibox

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ghostgramlabs.pettibox.data.preferences.AppLockPreferences
import com.ghostgramlabs.pettibox.data.preferences.ThemeMode
import com.ghostgramlabs.pettibox.data.preferences.ThemePreferences
import com.ghostgramlabs.pettibox.data.sync.SyncPreferences
import com.ghostgramlabs.pettibox.data.sync.SyncScheduler
import com.ghostgramlabs.pettibox.ui.nav.AppLaunch
import com.ghostgramlabs.pettibox.ui.lock.AppLockGate
import com.ghostgramlabs.pettibox.ui.nav.PettiBoxNavGraph
import com.ghostgramlabs.pettibox.ui.theme.PettiBoxTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
// FragmentActivity (still a ComponentActivity) because BiometricPrompt needs one.
class MainActivity : FragmentActivity() {
    @Inject lateinit var themePreferences: ThemePreferences
    @Inject lateinit var appLockPreferences: AppLockPreferences
    @Inject lateinit var syncPreferences: SyncPreferences

    /**
     * Where a notification, widget, or app-icon shortcut asked to land,
     * held until the NavController is composed and can act on it. Using
     * mutableStateOf instead of a Channel keeps this idempotent: a second
     * cold-start with the same intent fires once after the NavGraph reads
     * + clears it.
     */
    private var pendingLaunch by mutableStateOf<AppLaunch?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingLaunch = AppLaunch.from(intent)
        syncWhileOpen()
        setContent {
            val themeMode by themePreferences.mode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            // Null until loaded, so the gate can hold back saves until it knows.
            // remember: building the mapped flow inside composition made a new one
            // on every recomposition (lint: FlowOperatorInvokedInComposition).
            val lockEnabledFlow = remember { appLockPreferences.enabled.map<Boolean, Boolean?> { it } }
            val lockEnabled by lockEnabledFlow.collectAsStateWithLifecycle(initialValue = null)
            // Remember the value so a recomposition driven by theme change
            // doesn't re-fire the deep link.
            val launch = pendingLaunch
            PettiBoxTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    // Transparent so the app-wide paper texture PettiBoxTheme
                    // paints behind everything actually shows through (cards and
                    // sheets draw their own opaque surfaces on top). Painting the
                    // background color here was covering the grain, leaving the
                    // paper reading as flat vector color — the exact "AI tell"
                    // the texture exists to kill. contentColor keeps default text
                    // on-palette since the Surface no longer supplies a bg color.
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    AppLockGate(activity = this@MainActivity, enabled = lockEnabled) {
                    PettiBoxNavGraph(
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            lifecycleScope.launch { themePreferences.setMode(mode) }
                        },
                        launch = launch,
                        onLaunchConsumed = {
                            // Clear once the NavGraph has navigated, so a
                            // back-press to Home doesn't bounce the user
                            // straight back into Detail.
                            pendingLaunch = null
                        }
                    )
                    }
                }
            }
        }
    }

    /**
     * While PettiBox is on screen, sync every minute, so edits made in Chrome
     * or on another phone show up while you look. A round with nothing new
     * is one small Drive request. Stops as soon as the app is left.
     */
    private fun syncWhileOpen() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val sync = syncPreferences.status.first()
                    if (sync.enabled && System.currentTimeMillis() - sync.lastSyncAt >= 55_000L) {
                        SyncScheduler.now(this@MainActivity)
                    }
                    delay(60_000L)
                }
            }
        }
    }

    /**
     * Notification / widget / shortcut path while the activity is already
     * alive. Setting [pendingLaunch] flips the state Compose is observing;
     * the NavGraph's LaunchedEffect picks it up and navigates without us
     * having to recreate() the activity.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppLaunch.from(intent)?.let { pendingLaunch = it }
    }
}
