package com.ghostgramlabs.pettibox.ui.screens.detail

import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * A dialog that really covers the whole screen, edge to edge, like the rest
 * of the app. Content draws behind the status and navigation bars, so it must
 * pad itself with WindowInsets.statusBars / navigationBars where it has
 * controls.
 *
 * A plain Dialog with usePlatformDefaultWidth = false is screen-sized but
 * centred inside the area between the bars. That pushes its top under the
 * status bar (with the dim scrim showing above it) and its bottom ~100px
 * under the navigation bar, where taps go to the system gesture area —
 * which is how the image viewer's Previous/Next buttons became unreachable.
 */
@Composable
fun FullScreenDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val dialogView = LocalView.current
        val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                window.setDimAmount(0f)
                window.attributes = window.attributes.apply {
                    gravity = Gravity.TOP
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setFitInsetsTypes(0)
                    }
                }
                WindowCompat.getInsetsController(window, dialogView).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        content()
    }
}
