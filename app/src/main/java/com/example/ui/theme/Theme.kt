package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = Color.Black,
    primaryContainer = StudioSurfaceVariant,
    onPrimaryContainer = AccentCyan,
    secondary = AccentAmber,
    onSecondary = Color.Black,
    secondaryContainer = StudioSurfaceVariant,
    onSecondaryContainer = AccentAmber,
    tertiary = AccentGreen,
    error = RecordingRed,
    onError = Color.White,
    background = StudioBackground,
    onBackground = OnStudioSurface,
    surface = StudioSurface,
    onSurface = OnStudioSurface,
    surfaceVariant = StudioSurfaceVariant,
    onSurfaceVariant = OnStudioSurfaceVariant,
    outline = StudioCardBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Default to sleek studio dark for camera viewfinder experience
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
