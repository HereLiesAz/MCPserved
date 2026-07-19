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
 * Warm, dark, and dark regardless of system setting.
 *
 * The palette is the product's own: a plum-black ground and warm off-white text,
 * with a single amber accent carried by the primary role — filled actions and
 * the "ready" markers. Restraint is still the rule; there is one accent, not a
 * spectrum. The denial red keeps the one meaning it always had — the refused
 * action in the session log — and stays the only red in the app, so a single red
 * line remains unmissable against everything around it.
 *
 * Light mode is not offered. This is an application whose resting state is a
 * persistent notification saying something has authority over your phone; it
 * should read as serious, not cheerful.
 */
private val Scheme = darkColorScheme(
    primary = Color(0xFFE9A24A),
    onPrimary = Color(0xFF241019),
    secondary = Color(0xFFC06A3A),
    onSecondary = Color(0xFF241019),
    background = Color(0xFF191016),
    onBackground = Color(0xFFEDE4E8),
    surface = Color(0xFF221520),
    onSurface = Color(0xFFEDE4E8),
    surfaceVariant = Color(0xFF2B1B28),
    onSurfaceVariant = Color(0xFFA08A97),
    outline = Color(0xFF3A2833),
    error = Color(0xFFB3261E),
    onError = Color(0xFFEDE4E8)
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
