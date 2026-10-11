package com.c0mpile.readahead

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Orange = Color(0xFFFF8A00)

private val Dark =
    darkColorScheme(
        primary = Orange,
        onPrimary = Color(0xFF1A1000),
        background = Color(0xFF1B1B1B),
        surface = Color(0xFF1B1B1B),
    )

private val Light = lightColorScheme(primary = Color(0xFFB45F00))

/** One orange accent on the system's light or dark scheme. */
@Composable
fun ExportTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
