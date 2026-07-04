package com.garfiec.librechat.feature.conversations.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun DateGroupHeader(
    label: String,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.background,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(top = 8.dp),
    )
}
