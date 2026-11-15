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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.35).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
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
