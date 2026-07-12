package com.vibecoding.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6EF3),
    onPrimary = Color.White,
    secondary = Color(0xFF5B6B7C),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF001B3F),
    secondary = Color(0xFFB0BEC5),
    background = Color(0xFF101418),
    surface = Color(0xFF1A1F24),
    onSurface = Color(0xFFE8EAED)
)

@Composable
fun ReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
