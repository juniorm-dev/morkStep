package com.morkstep.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MorkGreen = Color(0xFF1B5E20)
private val MorkGreenLight = Color(0xFF66BB6A)
private val MorkAmber = Color(0xFFFFB300)
private val MorkRed = Color(0xFFE53935)

private val LightColors = lightColorScheme(
    primary = MorkGreen,
    secondary = MorkGreenLight,
    tertiary = MorkAmber,
    error = MorkRed,
)

private val DarkColors = darkColorScheme(
    primary = MorkGreenLight,
    secondary = MorkGreen,
    tertiary = MorkAmber,
    error = MorkRed,
)

@Suppress("FunctionName")
@Composable
fun MorkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}