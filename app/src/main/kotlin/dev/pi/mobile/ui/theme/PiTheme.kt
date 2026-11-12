package dev.pi.mobile.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Pi Mobile theme (PLAN.md 5.5): dark-first neutral #101014, one restrained
 * accent, Material3, no dynamic colour. Prose is 15sp/22sp and code is 12.5sp
 * monospace; the Settings text-size preference scales every size by
 * 100/115/130% on top of the system font scale.
 */

/** The single accent used for actions, links, the current-session mark and the live dot. */
val PiAccent = Color(0xFF7C8CFF)
val PiBackground = Color(0xFF101014)

private val PiDarkColors = darkColorScheme(
    primary = PiAccent,
    onPrimary = Color(0xFF0A0B16),
    primaryContainer = Color(0xFF262B66),
    onPrimaryContainer = Color(0xFFDFE2FF),
    secondary = Color(0xFFAEB2C4),
    onSecondary = Color(0xFF15161D),
    secondaryContainer = Color(0xFF23242E),
    onSecondaryContainer = Color(0xFFE4E5EF),
    tertiary = Color(0xFF9AA0B4),
    onTertiary = Color(0xFF15161D),
    background = PiBackground,
    onBackground = Color(0xFFE7E7EE),
    surface = PiBackground,
    onSurface = Color(0xFFE7E7EE),
    surfaceVariant = Color(0xFF1D1D25),
    onSurfaceVariant = Color(0xFFA7A7B5),
    surfaceTint = PiAccent,
    inverseSurface = Color(0xFFE7E7EE),
    inverseOnSurface = Color(0xFF101014),
    inversePrimary = Color(0xFF4B56C8),
    error = Color(0xFFFFB3B8),
    onError = Color(0xFF1A0A0C),
    errorContainer = Color(0xFF4A151B),
    onErrorContainer = Color(0xFFFFDADB),
    outline = Color(0xFF3C3C48),
    outlineVariant = Color(0xFF26262F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF1A1A22),
    surfaceDim = Color(0xFF0C0C10),
    surfaceContainerLowest = Color(0xFF0B0B0F),
    surfaceContainerLow = Color(0xFF131319),
    surfaceContainer = Color(0xFF17171E),
    surfaceContainerHigh = Color(0xFF1C1C24),
    surfaceContainerHighest = Color(0xFF22222B),
)

private val PiLightColors = lightColorScheme(
    primary = Color(0xFF4B56C8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E3FF),
    onPrimaryContainer = Color(0xFF101A5C),
    secondary = Color(0xFF5B6072),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E5F0),
    onSecondaryContainer = Color(0xFF171A24),
    tertiary = Color(0xFF5B6072),
    onTertiary = Color.White,
    background = Color(0xFFF7F7FA),
    onBackground = Color(0xFF17171C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17171C),
    surfaceVariant = Color(0xFFEDEDF3),
    onSurfaceVariant = Color(0xFF565666),
    inverseSurface = Color(0xFF17171C),
    inverseOnSurface = Color(0xFFF2F2F7),
    inversePrimary = Color(0xFFB8C0FF),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFC9C9D4),
    outlineVariant = Color(0xFFE0E0E8),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE6E6EC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F4F8),
    surfaceContainer = Color(0xFFF0F0F5),
    surfaceContainerHigh = Color(0xFFEAEAF0),
    surfaceContainerHighest = Color(0xFFE4E4EB),
)

/** Text-size multiplier from Settings (100/115/130%). */
val LocalPiTextScale = staticCompositionLocalOf { 1f }

/** Minimum interactive target (PLAN.md 5.5). */
object PiDims {
    val TouchTarget = 48.dp
    val BarHeight = 56.dp
    val ComposerMinHeight = 52.dp
    val ButtonHeight = 52.dp
    val SessionRowHeight = 64.dp
    val AccentBar = 3.dp
}

private fun typography(scale: Float): Typography {
    fun size(sp: Float): Float = sp * scale
    return Typography(
        headlineMedium = TextStyle(
            fontSize = size(21f).sp,
            lineHeight = size(28f).sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
        ),
        headlineSmall = TextStyle(
            fontSize = size(18f).sp,
            lineHeight = size(24f).sp,
            fontWeight = FontWeight.SemiBold,
        ),
        titleLarge = TextStyle(
            fontSize = size(17f).sp,
            lineHeight = size(23f).sp,
            fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = TextStyle(
            fontSize = size(16f).sp,
            lineHeight = size(22f).sp,
            fontWeight = FontWeight.Medium,
        ),
        titleSmall = TextStyle(
            fontSize = size(14f).sp,
            lineHeight = size(19f).sp,
            fontWeight = FontWeight.Medium,
        ),
        bodyLarge = TextStyle(fontSize = size(15f).sp, lineHeight = size(22f).sp),
        bodyMedium = TextStyle(fontSize = size(14f).sp, lineHeight = size(20f).sp),
        bodySmall = TextStyle(fontSize = size(13f).sp, lineHeight = size(18f).sp),
        labelLarge = TextStyle(
            fontSize = size(14f).sp,
            lineHeight = size(18f).sp,
            fontWeight = FontWeight.Medium,
        ),
        labelMedium = TextStyle(
            fontSize = size(12f).sp,
            lineHeight = size(16f).sp,
            fontWeight = FontWeight.Medium,
        ),
        labelSmall = TextStyle(fontSize = size(11f).sp, lineHeight = size(14f).sp),
    )
}

/** Code and monospace output at 12.5sp (scaled by the text-size preference). */
@Composable
fun codeTextStyle(): TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = (12.5f * LocalPiTextScale.current).sp,
    lineHeight = (17.5f * LocalPiTextScale.current).sp,
)

@Composable
fun PiTheme(
    theme: String = "dark",
    textScalePercent: Int = 100,
    content: @Composable () -> Unit,
) {
    val dark = when (theme.lowercase()) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    val scale = (textScalePercent.coerceIn(85, 160) / 100f).coerceIn(0.85f, 1.6f)
    val scheme = if (dark) PiDarkColors else PiLightColors
    CompositionLocalProvider(LocalPiTextScale provides scale) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography(scale),
        ) {
            // MaterialTheme does not provide a content colour. Every screen draws
            // its own background, so the default text colour must follow the
            // theme here or unstyled Text renders black on the dark surface.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
                content()
            }
        }
    }
    PiSystemBars(dark = dark)
}

/**
 * Edge-to-edge system bar icon contrast. MainActivity calls enableEdgeToEdge();
 * the icons must follow the in-app theme, not the system one.
 */
@Composable
private fun PiSystemBars(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(dark) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        onDispose { }
    }
}

internal fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
