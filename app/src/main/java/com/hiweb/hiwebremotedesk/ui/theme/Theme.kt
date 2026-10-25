package com.hiweb.hiwebremotedesk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RemoteDeskColors = darkColorScheme(
    primary = Accent,
    onPrimary = TextPrimary,
    secondary = Accent,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceCard,
    onSurfaceVariant = TextSecondary,
    outline = Outline,
    error = DangerRed,
)

/** Приложение всегда тёмное, как в макете. */
@Composable
fun HiWebRemoteDeskTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RemoteDeskColors,
        typography = Typography,
        content = content,
    )
}
