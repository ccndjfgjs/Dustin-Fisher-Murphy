package dev.opencode.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

val BgColor = Color(0xFF0F1115)
val SurfaceColor = Color(0xFF171A21)
val SurfaceHigh = Color(0xFF20242F)
val Accent = Color(0xFF7C9CFF)
val Accent2 = Color(0xFFFFB86B)
val Muted = Color(0xFF8A91A3)
val Success = Color(0xFF6BD98B)
val Danger = Color(0xFFFF6B6B)

val Mono = FontFamily.Monospace

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0B1020),
    primaryContainer = Color(0xFF25304D),
    onPrimaryContainer = Color(0xFFDDE5FF),
    secondary = Accent2,
    onSecondary = Color(0xFF241500),
    background = BgColor,
    onBackground = Color(0xFFE6E9F0),
    surface = SurfaceColor,
    onSurface = Color(0xFFE6E9F0),
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Color(0xFFBFC6D6),
    outline = Color(0xFF3A3F4E),
    error = Danger,
    onError = Color(0xFF2A0000)
)

@Composable
fun OpenCodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = MaterialTheme.typography.copy(
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp)
        ),
        content = content
    )
}
