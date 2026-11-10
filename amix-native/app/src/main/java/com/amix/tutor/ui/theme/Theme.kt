package com.amix.tutor.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val AmixColors = darkColorScheme(
    primary = Color.White,
    onPrimary = AmixBg,
    secondary = AmixPanel2,
    onSecondary = AmixText,
    background = AmixBg,
    onBackground = AmixText,
    surface = AmixPanel,
    onSurface = AmixText,
    surfaceVariant = AmixPanel2,
    onSurfaceVariant = AmixMuted,
    outline = AmixLine,
    outlineVariant = AmixLine
)

private val AmixShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun AmixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AmixColors,
        typography = AmixTypography,
        shapes = AmixShapes,
        content = content
    )
}
