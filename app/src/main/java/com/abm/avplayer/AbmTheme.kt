package com.abm.avplayer

import android.graphics.Color as AndroidColor
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Single source of truth for the ABM Android visual system.
 *
 * Screens should use AbmTheme { ... } and these semantic tokens rather than
 * declaring screen-local colours, type styles or component shapes.
 */
object AbmColors {
    val canvas = Color(0xFFFFFFFF)
    val surface = Color(0xFFF7F9FC)
    val surfaceRaised = Color(0xFFEDF1F6)
    val primary = Color(0xFF087F8C)
    val secondary = Color(0xFF6655B8)
    val textPrimary = Color(0xFF17212F)
    val textMuted = Color(0xFF667085)
    val border = Color(0xFFE3E8EF)
    val success = Color(0xFF087F72)
    val warning = Color(0xFF9A5B13)
    val danger = Color(0xFFB42318)
}

// Compatibility aliases keep existing screens source-compatible while
// allowing all values to be maintained centrally in AbmColors.
val Canvas: Color get() = AbmColors.canvas
val Panel: Color get() = AbmColors.surface
val Raised: Color get() = AbmColors.surfaceRaised
val Cyan: Color get() = AbmColors.primary
val Violet: Color get() = AbmColors.secondary
val Muted: Color get() = AbmColors.textMuted
val Ink: Color get() = AbmColors.textPrimary

private val AbmColorScheme = lightColorScheme(
    primary = AbmColors.primary,
    onPrimary = Color.White,
    secondary = AbmColors.secondary,
    onSecondary = Color.White,
    background = AbmColors.canvas,
    onBackground = AbmColors.textPrimary,
    surface = AbmColors.surface,
    onSurface = AbmColors.textPrimary,
    surfaceVariant = AbmColors.surfaceRaised,
    onSurfaceVariant = AbmColors.textMuted,
    outline = AbmColors.border,
    error = AbmColors.danger
)

private val AbmTypography = Typography(
    defaultFontFamily = FontFamily.SansSerif
)

private val AbmShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

/** Apply the same theme, typography and component geometry on every screen. */
@Composable
fun AbmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AbmColorScheme,
        typography = AbmTypography,
        shapes = AbmShapes,
        content = content
    )
}

/** Consistent light system bars for every ABM Activity. */
fun ComponentActivity.applyAbmLightSystemBars() {
    window.statusBarColor = AndroidColor.WHITE
    window.navigationBarColor = AndroidColor.WHITE
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = true
        isAppearanceLightNavigationBars = true
    }
}
