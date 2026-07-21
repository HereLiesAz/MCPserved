package com.hereliesaz.mcpserved.desktop.ui

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
 * The desktop app wears the same skin as the phone.
 *
 * This is a direct port of the Android app's `ui/Theme.kt`: a plum-black ground
 * and warm off-white text, with a single amber accent on the primary role. Dark
 * regardless of the host's setting, for the same reason it is on the phone — this
 * is a tool that holds authority over a device, and it should read as serious,
 * not cheerful. The two must be kept in lockstep so the desktop and the phone
 * look like one product.
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
    onError = Color(0xFFEDE4E8),
)

/** Denial marker. The only other colour in the product. */
val DeniedRed = Color(0xFFB3261E)

private val Type = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 30.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
    ),
)

@Composable
fun McpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
