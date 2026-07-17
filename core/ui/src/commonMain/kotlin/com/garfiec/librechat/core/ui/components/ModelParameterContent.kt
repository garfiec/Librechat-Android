package com.garfiec.librechat.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.ParameterDefinition
import com.garfiec.librechat.core.model.ParameterType

@Stable
data class ModelParameters(
    val temperature: Float = 1.0f,
    val maxOutputTokens: Int? = null,
    val topP: Float = 1.0f,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val customName: String = "",
    val customInstructions: String = "",
    val maxContextTokens: Int? = null,
    val topK: Int? = null,
    val resendFiles: Boolean = false,
    val thinking: Boolean = false,
    val thinkingBudget: String = "Auto",
    val webSearch: Boolean = false,
    val urlContext: Boolean = false,
    val fileTokenLimit: Int? = null,
    val dynamicValues: Map<String, String> = emptyMap(),
) {
    companion object {
        val DEFAULT = ModelParameters()
    }

    /**
     * Reads a parameter value by its registry key, bridging between the typed fields
     * in ModelParameters and the dynamic string-based system.
     */
    fun getValueForKey(key: String): String = when (key) {
        "chatGptLabel", "modelLabel" -> customName
        "promptPrefix", "system" -> customInstructions
        "maxContextTokens" -> maxContextTokens?.toString() ?: ""
        "max_tokens", "maxOutputTokens", "maxTokens" -> maxOutputTokens?.toString() ?: ""
        "temperature" -> temperature.toString()
        "top_p", "topP" -> topP.toString()
        "frequency_penalty" -> frequencyPenalty.toString()
        "presence_penalty" -> presencePenalty.toString()
        // null means "unset" — return "" so valueDiffersFromDefault doesn't
        // light up the per-field reset icon on a fresh, untouched state.
        "topK" -> topK?.toString() ?: ""
        "resendFiles" -> resendFiles.toString()
        "thinking" -> thinking.toString()
        "thinkingBudget" -> thinkingBudget
        "web_search" -> webSearch.toString()
        "url_context" -> urlContext.toString()
        "fileTokenLimit" -> fileTokenLimit?.toString() ?: ""
        "stop" -> dynamicValues["stop"] ?: ""
        "reasoning_effort", "effort" -> dynamicValues[key] ?: ""
        "promptCache" -> dynamicValues["promptCache"] ?: "false"
        else -> dynamicValues[key] ?: ""
    }

    /**
     * Returns a copy of ModelParameters with the given key updated to the new value.
     * Maps dynamic registry keys back to the typed fields.
     */
    fun withUpdatedKey(key: String, value: String): ModelParameters = when (key) {
        "chatGptLabel", "modelLabel" -> copy(customName = value)
        "promptPrefix", "system" -> copy(customInstructions = value)
        "maxContextTokens" -> copy(maxContextTokens = value.toIntOrNull())
        "max_tokens", "maxOutputTokens", "maxTokens" -> copy(maxOutputTokens = value.toIntOrNull())
        "temperature" -> copy(temperature = value.toFloatOrNull() ?: temperature)
        "top_p", "topP" -> copy(topP = value.toFloatOrNull() ?: topP)
        "frequency_penalty" -> copy(frequencyPenalty = value.toFloatOrNull() ?: frequencyPenalty)
        "presence_penalty" -> copy(presencePenalty = value.toFloatOrNull() ?: presencePenalty)
        "topK" -> {
            val intVal = value.toIntOrNull()
            copy(topK = if (intVal == 0) null else intVal)
        }
        "resendFiles" -> copy(resendFiles = value.toBooleanStrictOrNull() ?: resendFiles)
        "thinking" -> copy(thinking = value.toBooleanStrictOrNull() ?: thinking)
        "thinkingBudget" -> copy(thinkingBudget = value)
        "web_search" -> copy(webSearch = value.toBooleanStrictOrNull() ?: webSearch)
        "url_context" -> copy(urlContext = value.toBooleanStrictOrNull() ?: urlContext)
        "fileTokenLimit" -> copy(fileTokenLimit = value.toIntOrNull())
        else -> copy(dynamicValues = dynamicValues.toMutableMap().apply { this[key] = value })
    }

    /**
     * Resets a single registry-keyed value back to its definition default.
     * Other typed fields and unrelated [dynamicValues] entries are preserved.
     * For dynamic-only keys, the entry is removed from the map (so reading
     * falls back to the registry default via [getValueForKey]).
     */
    fun resetKeyToDefault(definition: ParameterDefinition): ModelParameters {
        val typedKeys = setOf(
            "chatGptLabel", "modelLabel",
            "promptPrefix", "system",
            "maxContextTokens",
            "max_tokens", "maxOutputTokens", "maxTokens",
            "temperature", "top_p", "topP",
            "frequency_penalty", "presence_penalty",
            "topK",
            "resendFiles", "thinking", "thinkingBudget",
            "web_search", "url_context", "fileTokenLimit",
        )
        return if (definition.key in typedKeys) {
            withUpdatedKey(definition.key, definition.default.orEmpty())
        } else {
            copy(dynamicValues = dynamicValues - definition.key)
        }
    }

    /** True when the value rendered for [definition] differs from its definition default. */
    fun valueDiffersFromDefault(definition: ParameterDefinition): Boolean {
        val current = getValueForKey(definition.key)
        val default = definition.default.orEmpty()
        // For numeric fields, compare as numbers so "1.0" == "1" doesn't show as differing.
        val currentNum = current.toDoubleOrNull()
        val defaultNum = default.toDoubleOrNull()
        if (currentNum != null && defaultNum != null) return currentNum != defaultNum
        return current != default
    }
}

@Composable
fun ModelParameterContent(
    parameters: ModelParameters,
    onParametersChange: (ModelParameters) -> Unit,
    modifier: Modifier = Modifier,
    selectedEndpoint: String = "",
    dynamicParameterDefinitions: List<ParameterDefinition>? = null,
    extendedEffortSupported: Boolean = false,
    selectedProvider: String? = null,
    selectedModel: String? = null,
    onSaveAsPreset: () -> Unit = {},
    showHeader: Boolean = true,
    showSaveAsPreset: Boolean = true,
    /** When false, skips the internal `verticalScroll` modifier — required when
     *  the host already provides scrolling (e.g. embedded inside another
     *  scrollable Column on the agent editor screen). Default true preserves
     *  the bottom-sheet use case. */
    applyVerticalScroll: Boolean = true,
) {
    val definitions = remember(
        selectedEndpoint,
        dynamicParameterDefinitions,
        extendedEffortSupported,
        selectedProvider,
        selectedModel,
    ) {
        if (!dynamicParameterDefinitions.isNullOrEmpty()) {
            dynamicParameterDefinitions
        } else {
            EndpointParameterRegistry.getDefinitions(
                endpoint = selectedEndpoint,
                extendedEffortSupported = extendedEffortSupported,
                provider = selectedProvider,
                model = selectedModel,
            )
        }
    }

    val scrollableModifier = if (applyVerticalScroll) {
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
    } else {
        modifier.fillMaxWidth()
    }

    Column(
        modifier = scrollableModifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showHeader) {
            Text(
                text = "Model Parameters",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )

            Spacer(modifier = Modifier.height(4.dp))
        }

        definitions.forEach { definition ->
            val currentValue = parameters.getValueForKey(definition.key)
            val differs = parameters.valueDiffersFromDefault(definition)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            when (definition.type) {
                ParameterType.TEXT -> {
                    DynamicInput(
                        label = definition.label,
                        value = currentValue,
                        onValueChange = { newValue ->
                            onParametersChange(parameters.withUpdatedKey(definition.key, newValue))
                        },
                        placeholder = definition.placeholder
                            ?: definition.default?.ifEmpty { "Default" }
                            ?: "Default",
                        description = definition.description,
                    )
                }

                ParameterType.TEXTAREA -> {
                    DynamicTextarea(
                        label = definition.label,
                        value = currentValue,
                        onValueChange = { newValue ->
                            onParametersChange(parameters.withUpdatedKey(definition.key, newValue))
                        },
                        placeholder = definition.placeholder
                            ?: definition.default?.ifEmpty { null },
                        description = definition.description,
                    )
                }

                ParameterType.SLIDER -> {
                    val min = definition.min?.toFloat() ?: 0f
                    val max = definition.max?.toFloat() ?: 1f
                    val step = definition.step?.toFloat() ?: 0.01f
                    val floatValue = currentValue.toFloatOrNull() ?: (definition.default?.toFloatOrNull() ?: min)

                    DynamicSlider(
                        label = definition.label,
                        value = floatValue.coerceIn(min, max),
                        onValueChange = { newValue ->
                            onParametersChange(parameters.withUpdatedKey(definition.key, newValue.toString()))
                        },
                        min = min,
                        max = max,
                        step = step,
                        description = definition.description,
                    )
                }

                ParameterType.SWITCH, ParameterType.CHECKBOX -> {
                    val checked = currentValue.toBooleanStrictOrNull()
                        ?: (definition.default?.toBooleanStrictOrNull() ?: false)

                    DynamicCheckbox(
                        label = definition.label,
                        checked = checked,
                        onCheckedChange = { newChecked ->
                            onParametersChange(parameters.withUpdatedKey(definition.key, newChecked.toString()))
                        },
                        description = definition.description,
                    )
                }

                ParameterType.DROPDOWN -> {
                    val displayValue = if (currentValue.isEmpty()) {
                        definition.default ?: definition.options?.firstOrNull() ?: ""
                    } else {
                        currentValue
                    }

                    DynamicDropdown(
                        label = definition.label,
                        selectedValue = displayValue,
                        options = definition.options ?: emptyList(),
                        onValueChange = { newValue ->
                            onParametersChange(parameters.withUpdatedKey(definition.key, newValue))
                        },
                        description = definition.description,
                        optionLabels = definition.optionLabels,
                    )
                }

                ParameterType.ENUM_SLIDER -> {
                    val opts = definition.options ?: emptyList()
                    val displayValue = if (currentValue.isEmpty()) {
                        definition.default ?: opts.firstOrNull() ?: ""
                    } else {
                        currentValue
                    }
                    DynamicEnumSlider(
                        label = definition.label,
                        selectedValue = displayValue,
                        options = opts,
                        onValueChange = { newValue ->
                            onParametersChange(parameters.withUpdatedKey(definition.key, newValue))
                        },
                        description = definition.description,
                        optionLabels = definition.optionLabels,
                    )
                }

                ParameterType.TAGS -> {
                    val tags = if (currentValue.isBlank()) {
                        emptyList()
                    } else {
                        currentValue.split("\n").filter { it.isNotBlank() }
                    }

                    DynamicTagsInput(
                        label = definition.label,
                        tags = tags,
                        onTagsChange = { newTags ->
                            onParametersChange(
                                parameters.withUpdatedKey(definition.key, newTags.joinToString("\n")),
                            )
                        },
                        description = definition.description,
                        maxTags = definition.max?.toInt() ?: 4,
                    )
                }
            }

                if (differs) {
                    TextButton(
                        onClick = {
                            onParametersChange(parameters.resetKeyToDefault(definition))
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Restore default")
                    }
                }
            }
        }

        // Reset visible definitions to their defaults; dynamicValues for keys
        // not in the current schema are preserved (other endpoints' state stays
        // intact when the user is just resetting the active endpoint's view).
        OutlinedButton(
            onClick = {
                val reset = definitions.fold(parameters) { acc, def -> acc.resetKeyToDefault(def) }
                onParametersChange(reset)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset to Defaults")
        }

        // Save As Preset
        if (showSaveAsPreset) {
            FilledTonalButton(
                onClick = onSaveAsPreset,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save As Preset")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
