package com.garfiec.librechat.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt

/**
 * Discrete slider that maps a list of string [options] to slider positions.
 * Matches upstream's `enum`-typed Slider component (e.g. reasoning_effort,
 * verbosity, imageDetail) where the value is one of a fixed string set but
 * the UI affordance is a slider rather than a dropdown.
 *
 * [selectedValue] is the current string option; [optionLabels] overrides the
 * label shown above the slider when it differs from the raw option (e.g.
 * "none" → "Unset").
 */
@Composable
fun DynamicEnumSlider(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    optionLabels: Map<String, String>? = null,
) {
    if (options.isEmpty()) return

    val index = options.indexOf(selectedValue).let { if (it < 0) 0 else it }
    val displayLabel = optionLabels?.get(selectedValue) ?: selectedValue.ifEmpty { options.firstOrNull().orEmpty() }
    val sliderCd = "$label slider, value $displayLabel"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = sliderCd },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = displayLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { newPos ->
                val newIndex = newPos.roundToInt().coerceIn(0, options.lastIndex)
                onValueChange(options[newIndex])
            },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = (options.size - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
