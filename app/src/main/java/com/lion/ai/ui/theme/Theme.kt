package com.lion.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val LionColors = darkColorScheme(
    primary = Gold,
    onPrimary = DarkBg,
    secondary = Orange,
    background = DarkBg,
    surface = SurfaceDark,
    onBackground = TextWhite,
    onSurface = TextWhite
)

@Composable
fun LionAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LionColors,
        typography = Typography,
        content = content
    )
}
