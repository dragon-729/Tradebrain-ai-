package com.tradebrain.ai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF2DD4BF), onPrimary = Color(0xFF00201C), secondary = Color(0xFF60A5FA),
    background = Color(0xFF0B1220), onBackground = Color(0xFFE8EEF8),
    surface = Color(0xFF121C30), onSurface = Color(0xFFE8EEF8),
    surfaceVariant = Color(0xFF1A2740), onSurfaceVariant = Color(0xFF9FB0C8), error = Color(0xFFF87171)
)
private val Light = lightColorScheme(
    primary = Color(0xFF0F766E), onPrimary = Color.White, secondary = Color(0xFF2563EB),
    background = Color(0xFFF6F8FB), onBackground = Color(0xFF0F172A),
    surface = Color.White, onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEAF0F7), onSurfaceVariant = Color(0xFF475569)
)

@Composable
fun TradeBrainTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
