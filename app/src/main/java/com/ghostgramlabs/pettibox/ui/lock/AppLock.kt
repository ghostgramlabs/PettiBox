package com.ghostgramlabs.pettibox.ui.lock

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ghostgramlabs.pettibox.data.preferences.AppLockPreferences
import com.ghostgramlabs.pettibox.ui.components.KeeperMascot
import com.ghostgramlabs.pettibox.ui.components.KeeperPose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide unlock state. The app starts locked, stays unlocked while
 * it's in use, and locks again once it has been in the background longer
 * than [lockAfterMs] (the user's "Lock after" choice, 1 minute by default).
 * Turning the phone's screen off locks it at once, whatever that choice:
 * a phone handed to someone else must not open straight into PettiBox.
 */
object AppLockSession {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** Kept in step with the "Lock after" preference by the Application. */
    @Volatile var lockAfterMs: Long = AppLockPreferences.DEFAULT_LOCK_AFTER_MS

    // Uptime, not wall-clock time: changing the phone's clock must not
    // stretch the grace period.
    private var backgroundedAt = 0L

    // The phone-PIN screen is another app's activity, so asking for the PIN
    // backgrounds us. Leaving for the prompt must not count as leaving.
    private var prompting = false

    /**
     * Told how long the home-screen widget may show titles (wall-clock ms):
     * Long.MAX_VALUE while unlocked and open, the relock deadline once the
     * app is left, 0 when locked. Set by the Application.
     */
    @Volatile var onTitlesVisibleUntil: (Long) -> Unit = {}

    /** Call once from Application.onCreate (main thread). */
    fun install(context: Context) {
        // SCREEN_OFF only reaches receivers registered at runtime, never the
        // manifest. It's a system broadcast, so a not-exported receiver
        // still gets it.
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) lock()
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (prompting) return
                backgroundedAt = SystemClock.elapsedRealtime()
                // The widget hides titles when the app itself would relock.
                if (_unlocked.value) onTitlesVisibleUntil(System.currentTimeMillis() + lockAfterMs)
            }

            override fun onStart(owner: LifecycleOwner) {
                if (!prompting && backgroundedAt > 0 &&
                    SystemClock.elapsedRealtime() - backgroundedAt >= lockAfterMs
                ) {
                    lock()
                } else if (!prompting && _unlocked.value) {
                    onTitlesVisibleUntil(Long.MAX_VALUE)
                }
                backgroundedAt = 0L
            }
        })
        // A fresh process always starts locked, whatever the widget last showed.
        onTitlesVisibleUntil(0L)
    }

    fun markUnlocked() {
        _unlocked.value = true
        onTitlesVisibleUntil(Long.MAX_VALUE)
    }

    private fun lock() {
        _unlocked.value = false
        autoPromptPending = true
        onTitlesVisibleUntil(0L)
    }

    // The lock screen asks by itself once per lock (app start counts).
    @Volatile private var autoPromptPending = true

    /** True once after each lock: the lock screen should open the prompt itself. */
    fun takeAutoPrompt(): Boolean {
        if (!autoPromptPending || prompting) return false
        autoPromptPending = false
        return true
    }

    /** Whether this phone has anything to unlock with: a screen lock or enrolled biometrics. */
    fun isAvailable(context: Context): Boolean {
        val keyguard = ContextCompat.getSystemService(context, KeyguardManager::class.java)
        return keyguard?.isDeviceSecure == true ||
            BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Shows the system prompt: fingerprint / face, with the phone's own
     * PIN, pattern, or password as the fallback. [onSuccess] runs on the
     * main thread.
     */
    fun prompt(
        activity: FragmentActivity,
        title: String = "Unlock PettiBox",
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {}
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                markUnlocked()
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
                onFailure()
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Use your fingerprint, face, or phone PIN")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        prompting = true
        runCatching { BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info) }
            .onFailure {
                prompting = false
                onFailure()
            }
    }
}

/**
 * Shows [content] only when App lock is off or the session is unlocked.
 * [enabled] is null until the preference has loaded, and nothing renders
 * in that moment either, so saves never flash on screen before the lock.
 */
@Composable
fun AppLockGate(
    activity: FragmentActivity,
    enabled: Boolean?,
    content: @Composable () -> Unit
) {
    val unlocked by AppLockSession.unlocked.collectAsState()
    // Re-checked on every resume, so adding a screen lock back in Android
    // settings brings the lock back the moment the user returns.
    var available by remember { mutableStateOf(AppLockSession.isAvailable(activity)) }
    LifecycleResumeEffect(Unit) {
        available = AppLockSession.isAvailable(activity)
        onPauseOrDispose { }
    }
    // Hide saves from the Recents thumbnail while the lock is on.
    LaunchedEffect(enabled) {
        if (enabled != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!enabled)
        }
    }
    when {
        enabled == null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        // If the phone's screen lock was removed after App lock was turned
        // on, there's nothing left to unlock with — step aside rather than
        // lock the user out of their own saves.
        !enabled || unlocked || !available -> content()
        else -> {
            // Ask once the app is actually in view. Asking on composition
            // fails when the lock happened with the screen off (the prompt is
            // dropped while the activity is stopped). Only once per lock, so
            // cancelling leaves the Unlock button instead of re-asking.
            LifecycleResumeEffect(Unit) {
                if (AppLockSession.takeAutoPrompt()) AppLockSession.prompt(activity, onSuccess = {})
                onPauseOrDispose { }
            }
            LockScreen(onUnlock = { AppLockSession.prompt(activity, onSuccess = {}) })
        }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        KeeperMascot(pose = KeeperPose.Welcome, badgeIcon = Icons.Rounded.Lock)
        Spacer(Modifier.height(20.dp))
        Text(
            "PettiBox is locked",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Unlock with your fingerprint, face, or phone PIN.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock, shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Unlock", fontWeight = FontWeight.Bold)
        }
    }
}
