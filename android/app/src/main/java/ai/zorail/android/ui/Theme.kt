package ai.zorail.android.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/*
 * Zorail's design language, ported from the web app's CSS tokens: a near
 * monochrome neutral scale where colour is reserved for meaning (danger,
 * success) and nothing else. The web palette is OKLCH; these are the sRGB
 * equivalents of the same ramp so the two products look like one product.
 */

// Dark (the default, as on the web).
private val DarkBg = Color(0xFF131313)          // --bg
private val DarkSurface = Color(0xFF1D1D1D)     // --bg-subtle
private val DarkSurfaceHigh = Color(0xFF262626) // --bg-muted
private val DarkBorder = Color(0xFF333333)      // --bd
private val DarkFg = Color(0xFFFAFAFA)          // --fg
private val DarkFgMuted = Color(0xFFAFAFAF)     // --fg-muted

// Light.
private val LightBg = Color(0xFFFFFFFF)
private val LightSurface = Color(0xFFFAFAFA)
private val LightSurfaceHigh = Color(0xFFF1F1F1)
private val LightBorder = Color(0xFFE3E3E3)
private val LightFg = Color(0xFF141414)
private val LightFgMuted = Color(0xFF6B6B6B)

private val Danger = Color(0xFFE5484D)
private val Ok = Color(0xFF46A758)

private val DarkColors = darkColorScheme(
    background = DarkBg,
    onBackground = DarkFg,
    surface = DarkBg,
    onSurface = DarkFg,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkFgMuted,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceHigh,
    // The accent is near-white on dark, as on the web: a light CTA, not a hue.
    primary = DarkFg,
    onPrimary = DarkBg,
    secondary = DarkSurfaceHigh,
    onSecondary = DarkFg,
    outline = DarkBorder,
    outlineVariant = DarkBorder,
    error = Danger,
    onError = Color.White,
)

private val LightColors = lightColorScheme(
    background = LightBg,
    onBackground = LightFg,
    surface = LightBg,
    onSurface = LightFg,
    surfaceVariant = LightSurface,
    onSurfaceVariant = LightFgMuted,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurfaceHigh,
    primary = LightFg,
    onPrimary = LightBg,
    secondary = LightSurfaceHigh,
    onSecondary = LightFg,
    outline = LightBorder,
    outlineVariant = LightBorder,
    error = Danger,
    onError = Color.White,
)

/** Accent used for "this worked" affordances; not part of the neutral ramp. */
val SuccessColor = Ok

/**
 * Type scale sized for a phone held at arm's length, not a desktop browser.
 * The web app's 11–13px steps are legible on a monitor and too small here, so
 * body text starts at 15sp.
 */
private val ZorailTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // Addresses and IDs are monospace, as in the web UI — they are data.
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
)

/** Corner radius shared by cards and rows. */
val CardRadius = 14.dp

@Composable
fun ZorailTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkColors else LightColors

    // Transparent system bars, with icon contrast matched to the theme: the app
    // draws edge-to-edge and insets its own content.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !dark
        }
    }

    MaterialTheme(colorScheme = colors, typography = ZorailTypography, content = content)
}
