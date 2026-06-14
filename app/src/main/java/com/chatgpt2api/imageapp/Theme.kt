package com.chatgpt2api.imageapp

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
internal fun AppTheme(themePref: ThemePref = ThemePref.System, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themePref) {
        ThemePref.Light -> false
        ThemePref.Dark -> true
        ThemePref.System -> systemDark
    }
    val palette = if (isDark) DarkPaletteForTheme else LightPaletteForTheme
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides palette) {
        val onInk = if (palette.isDark) Color(0xFF111111) else Color.White
        val scheme = if (palette.isDark) {
            androidx.compose.material3.darkColorScheme(
                primary = palette.ink,
                onPrimary = onInk,
                secondary = palette.accent,
                onSecondary = Color.White,
                background = palette.paper,
                surface = palette.surface,
                onSurface = palette.textPrimary,
                outline = palette.glassBorder,
            )
        } else {
            lightColorScheme(
                primary = palette.ink,
                onPrimary = onInk,
                secondary = palette.accent,
                onSecondary = Color.White,
                background = palette.paper,
                surface = palette.surface,
                onSurface = palette.textPrimary,
                outline = palette.glassBorder,
            )
        }
        MaterialTheme(
            colorScheme = scheme,
            content = { Surface(color = Color.Transparent, content = content) },
        )
    }
}
