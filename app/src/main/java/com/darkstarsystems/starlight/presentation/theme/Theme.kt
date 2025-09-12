package com.darkstarsystems.starlight.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

// Minimal high-contrast palette with true black background for OLED
private val OledColors = Colors(
    primary      = Color.White,
    onPrimary    = Color.Black,
    secondary    = Color(0xFFB0B0B0),
    onSecondary  = Color.Black,
    background   = Color.Black,   // <- important
    onBackground = Color.White,   // <- text/icons on black
    surface      = Color.Black,   // <- components sit on black
    onSurface    = Color.White,
    error        = Color(0xFFB00020),
    onError      = Color.White
)

@Composable
fun StarlightTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = OledColors, content = content)
}
