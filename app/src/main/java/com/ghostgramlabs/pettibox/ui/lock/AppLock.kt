package com.ghostgramlabs.pettibox.ui.lock

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
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
import com.ghostgramlabs.pettibox.ui.components.KeeperMascot
import com.ghostgramlabs.pettibox.ui.components.KeeperPose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide unlock state. The app starts locked, stays unlocked while
 * it's in use, and locks again once it has been in the background for
 * [GRACE_MS] — long enough to pick a photo or confirm a PIN without being
 * asked twice, short enough that a phone left on a table isn't open.
 */
object AppLockSession {
    private const val GRACE_MS = 60_000L

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    private var backgroundedAt = 0L

    /** Call once from Application.onCreate (main thread). */
    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                backgroundedAt = System.currentTimeMillis()
            }

            override fun onStart(owner: LifecycleOwner) {
                if (backgroundedAt > 0 && System.currentTimeMillis() - backgroundedAt > GRACE_MS) {
                    _unlocked.value = false
                }
            }
        })
    }

    fun markUnlocked() {
        _unlocked.value = true
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
                markUnlocked()
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure()
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Use your fingerprint, face, or phone PIN")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        runCatching { BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info) }
            .onFailure { onFailure() }
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
        !enabled || unlocked || !AppLockSession.isAvailable(activity) -> content()
        else -> {
            // Ask straight away; the screen behind stays as the retry.
            LaunchedEffect(Unit) { AppLockSession.prompt(activity, onSuccess = {}) }
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
