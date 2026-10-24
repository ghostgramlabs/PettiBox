package com.ghostgramlabs.pettibox.data.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether locked collections are open right now. Process-wide and never
 * persisted, so a fresh start is always locked. While locked, the
 * repository hides locked collections themselves (not just their saves),
 * like Google Photos' Locked Folder: the only way in is the Browse
 * "Locked" pill, which asks for fingerprint / face / phone PIN.
 *
 * Opened by [com.ghostgramlabs.pettibox.ui.lock.AppLockSession.unlockCollections]
 * and closed again on the same events that relock the app.
 */
object LockedCollections {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun setUnlocked(unlocked: Boolean) {
        _unlocked.value = unlocked
    }
}
