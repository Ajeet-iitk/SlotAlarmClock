package com.ajeet.slotalarmclock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * All colour in this app is drawn by [com.ajeet.slotalarmclock.ui.metal.Metal], so the Material
 * scheme exists only to keep platform widgets (text selection handles, the system dialogs we
 * never show) from flashing white.
 */
private val SlotAlarmColors = darkColorScheme(
    primary = Color(0xFFFFB74D),
    onPrimary = Color(0xFF201400),
    background = Color(0xFF0C0E12),
    onBackground = Color(0xFFE6EBF2),
    surface = Color(0xFF14181E),
    onSurface = Color(0xFFE6EBF2),
    surfaceVariant = Color(0xFF20242B),
    onSurfaceVariant = Color(0xFFB4BCC6),
    outline = Color(0xFF3A4048),
    error = Color(0xFFE4695B),
)

@Composable
fun SlotAlarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SlotAlarmColors, content = content)
}
