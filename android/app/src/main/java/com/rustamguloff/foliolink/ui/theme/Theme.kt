package com.rustamguloff.foliolink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Цвета как у iOS-версии: акцент — тёмно-синий «folio», в тёмной теме — светлый синий;
// золото и бумага — из иконки. Dynamic color не используем: приложение узнаётся по своим цветам.

val FolioNavy = Color(0xFF1B2A4A)
val FolioNavyLight = Color(0xFF8DA6D9)
val FolioGold = Color(0xFFD9A93E)
val FolioPaper = Color(0xFFF6F1E4)
val FolioSuccess = Color(0xFF2E9E5B)
val FolioDanger = Color(0xFFD64545)

private val Light: ColorScheme = lightColorScheme(
    primary = FolioNavy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4F3),
    onPrimaryContainer = FolioNavy,
    secondary = Color(0xFF8A6A1E),
    secondaryContainer = Color(0xFFF5E6C2),
    onSecondaryContainer = Color(0xFF3A2B05),
    background = Color.White,
    surface = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F7FA),
    surfaceContainer = Color(0xFFF1F2F6),
    surfaceContainerHigh = Color(0xFFEBECF1),
    surfaceContainerHighest = Color(0xFFE4E6EC),
    error = FolioDanger,
)

private val Dark: ColorScheme = darkColorScheme(
    primary = FolioNavyLight,
    onPrimary = Color(0xFF0B1630),
    primaryContainer = Color(0xFF263A63),
    onPrimaryContainer = Color(0xFFDDE4F3),
    secondary = FolioGold,
    secondaryContainer = Color(0xFF4A3A10),
    onSecondaryContainer = Color(0xFFF5E6C2),
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF111214),
    surfaceContainer = Color(0xFF1A1B1F),
    surfaceContainerHigh = Color(0xFF222328),
    surfaceContainerHighest = Color(0xFF2C2D33),
    error = Color(0xFFFF6B6B),
)

@Composable
fun FolioTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

