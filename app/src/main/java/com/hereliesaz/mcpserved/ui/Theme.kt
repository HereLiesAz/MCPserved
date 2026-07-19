package com.hereliesaz.mcpserved.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Monochrome throughout, and dark regardless of system setting.
 *
 * Colour is reserved for one thing: the denial marker in the session log. An
 * interface with no other colour in it makes a single red line unmissable, which
 * is the point — the log exists to be scanned quickly for the entry that should
 * not be there.
 *
 * Light mode is not offered. This is an application whose resting state is a
 * persistent notification saying something has authority over your phone; it
 * should not look cheerful.
 */
private val Scheme = darkColorScheme(
    primary = Color(0xFFE8E8E8),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFF9A9A9A),
    onSecondary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Color(0xFF8A8A8A),
    outline = Color(0xFF2A2A2A),
    error = Color(0xFFB3261E),
    onError = Color(0xFFE8E8E8)
)

/** Denial marker. The only colour in the application. */
val DeniedRed = Color(0xFFB3261E)

private val Type = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 30.sp,
        letterSpacing = (-0.5).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp
    )
)

@Composable
fun McpTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
