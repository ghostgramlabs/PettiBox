package com.ghostgramlabs.pettibox.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.ghostgramlabs.pettibox.data.preferences.ThemeMode

// Persimmon on Bone in light, Persimmon on warm-charcoal in dark. Containers
// stay in the same washed family so nothing in the app shouts.
private val LightColors = lightColorScheme(
    primary = Persimmon,
    onPrimary = Bone,
    primaryContainer = PersimmonWash,
    onPrimaryContainer = Soot,
    secondary = Mauve,
    onSecondary = Bone,
    secondaryContainer = Butter,
    onSecondaryContainer = Soot,
    tertiary = SeaGlass,
    onTertiary = Soot,
    background = Bone,
    onBackground = Soot,
    surface = PaperOff,
    onSurface = Soot,
    // Neutralise M3's tonal-elevation overlay. Left unset it defaults to
    // `primary`, which blends a faint persimmon cast into every elevated
    // surface (dialogs, sheets) — making popups look subtly orange and out
    // of step with the flat paper surfaces used everywhere else. Pointing it
    // at the surface colour makes the overlay a no-op, so popups stay paper.
    surfaceTint = PaperOff,
    surfaceVariant = SurfaceTint,
    onSurfaceVariant = Gravel,
    // M3 components pick their fill from these container tiers (dialogs use
    // High, switch tracks use Highest). Left unset they fall back to the
    // stock lavender baseline, so map them onto the paper ladder.
    surfaceContainerLowest = PaperBright,
    surfaceContainerLow = PaperOff,
    surfaceContainer = PaperOff,
    surfaceContainerHigh = PaperBright,
    surfaceContainerHighest = SurfaceTint,
    surfaceBright = PaperBright,
    surfaceDim = Bone,
    outline = PaperEdge,
    outlineVariant = SurfaceTint,
    error = PersimmonDeep,
    onError = Bone
)

private val DarkColors = darkColorScheme(
    primary = Persimmon,
    onPrimary = InkDark,
    primaryContainer = PersimmonDeep,
    onPrimaryContainer = CreamOnDark,
    secondary = Mauve,
    onSecondary = CreamOnDark,
    tertiary = SeaGlass,
    onTertiary = InkDark,
    background = InkDark,
    onBackground = CreamOnDark,
    surface = SurfaceDark,
    onSurface = CreamOnDark,
    // Same fix as light: stop the default `primary` surfaceTint from casting
    // persimmon onto elevated dark surfaces so dark dialogs/sheets read as
    // clean charcoal, matching the rest of the dark surfaces.
    surfaceTint = SurfaceDark,
    surfaceVariant = SurfaceTintDark,
    onSurfaceVariant = Pebble,
    // Same container ladder as light, in charcoal (see LightColors).
    surfaceContainerLowest = InkDark,
    surfaceContainerLow = SurfaceDark,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceTintDark,
    surfaceContainerHighest = SurfaceTintDark,
    surfaceBright = SurfaceTintDark,
    surfaceDim = InkDark,
    outline = OutlineDark,
    outlineVariant = SurfaceTintDark,
    error = PersimmonDeep,
    onError = CreamOnDark
)

/**
 * True when the active scheme is the light "daytime shelf" palette. Used to
 * gate light-only surface treatments (inset search fill, the dock-style
 * bottom bar, the squircle FAB) so dark mode renders exactly as before —
 * the same trick the SaveCard light shadow already uses, hoisted to one place.
 */
@Composable
@ReadOnlyComposable
fun isLightTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f

@Composable
fun PettiBoxTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    // Deliberately NOT using dynamicColor - we want our hand-picked palette,
    // not the device-wallpaper-derived Material You scheme.
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // MainActivity already calls enableEdgeToEdge() so the system
            // bars are transparent — content (paper texture + Compose UI)
            // is responsible for what sits behind them. We only own the
            // icon tint here: dark icons on light background, light icons
            // on dark background. The deprecated statusBarColor /
            // navigationBarColor setters do nothing on Android 15 (API
            // 35) and we used to paint them anyway, which left some
            // Pixel devices showing a stale white bar.
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = PettiBoxTypography,
        shapes = PettiBoxShapes
    ) {
        // App-wide paper texture sits beneath everything. Screens should
        // use Color.Transparent for their Scaffold containerColor so the
        // bone + grain shows through.
        Box(
            Modifier
                .fillMaxSize()
                .paperTexture(colors.background)
        ) {
            content()
        }
    }
}
