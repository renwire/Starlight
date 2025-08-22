package com.darkstarsystems.starlight.presentation.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme

@Composable
fun StarlightTheme(content: @Composable () -> Unit) {
    // Uses Wear defaults; override colors/typography later if needed.
    MaterialTheme(content = content)
}