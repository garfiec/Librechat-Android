package com.garfiec.librechat.feature.agents.components

import com.garfiec.librechat.core.model.ParameterDefinition
import com.garfiec.librechat.core.model.ParameterType
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.feature.agents.components.model.AgentAdvancedSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * Translates [AgentAdvancedSettings] into the [ModelParameters] shape consumed
 * by `ModelParameterContent`. Typed fields (temperature/topP/maxTokens) hydrate
 * the matching `ModelParameters` slots; everything in [AgentAdvancedSettings.extras]
 * is routed through [ModelParameters.withUpdatedKey] so the registry's typed
 * slots and dynamic-value fallback both receive the correct values.
 */
internal fun AgentAdvancedSettings.toModelParameters(): ModelParameters {
    var result = ModelParameters.DEFAULT.copy(
        temperature = temperature ?: ModelParameters.DEFAULT.temperature,
        topP = topP ?: ModelParameters.DEFAULT.topP,
        maxOutputTokens = maxTokens,
    )
    extras.forEach { (key, element) ->
        val str = when (element) {
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
        result = result.withUpdatedKey(key, str)
    }
    return result
}

/**
 * Translates an edited [ModelParameters] back into [AgentAdvancedSettings],
 * preserving any extras keys that aren't in [visibleDefs] (so server-set
 * fields outside the current schema survive a save).
 *
 * The three primary typed slots (temperature/topP/maxTokens) map directly to
 * the matching `AgentAdvancedSettings` fields. Every other visible def is
 * read out of the rendered parameters via [ModelParameters.getValueForKey]
 * and written to extras with a [JsonElement] shape that matches the def's
 * control type (boolean for SWITCH/CHECKBOX, numeric for SLIDER, array for
 * TAGS, string otherwise).
 */
internal fun ModelParameters.toAgentAdvancedSettings(
    previous: AgentAdvancedSettings,
    visibleDefs: List<ParameterDefinition>,
): AgentAdvancedSettings {
    val newExtras = previous.extras.toMutableMap()
    var topPKey = previous.topPKey
    var maxTokensKey = previous.maxTokensKey
    visibleDefs.forEach { def ->
        if (def.key in PRIMARY_TYPED_KEYS) {
            // Update the originally-loaded wire key when the currently-visible
            // provider's def disagrees. Without this, an agent loaded as
            // Anthropic (`topP`) and then resaved while the editor is showing
            // OpenAI defs (`top_p`) would carry the stale `topP` key, or vice
            // versa. The registry's def.key reflects the current provider's
            // canonical name and is the right choice for the next save.
            when (def.key) {
                "top_p", "topP" -> topPKey = def.key
                "max_tokens", "maxTokens", "maxOutputTokens" -> maxTokensKey = def.key
            }
            return@forEach
        }
        val value = getValueForKey(def.key)
        // Only write to extras when (a) the key was already there — preserve
        // a server-set value — or (b) the user moved the control away from
        // the registry default. ModelParameters' typed slots return their
        // own defaults ("0.0" for frequency_penalty, "false" for switches)
        // even when the user never touched anything, so unconditionally
        // writing every visible def's current value would re-stamp the
        // entire schema with defaults the agent never had.
        val isDefault = value == (def.default ?: "")
        val wasInExtras = def.key in previous.extras
        when {
            value.isBlank() -> newExtras.remove(def.key)
            wasInExtras || !isDefault -> newExtras[def.key] = encodeAsJsonElement(value, def.type)
            // else: untouched default value, not previously in extras — skip.
        }
        // Drop legacy alias keys that share the same UX slot as `def.key`.
        // Without this, a Bedrock-Anthropic agent previously saved by an older
        // mobile build under `promptPrefix` would, after the split between
        // `bedrock.system` and `librechat.promptPrefix`, carry BOTH keys on
        // the wire (the new write hits `system`; the legacy `promptPrefix`
        // value is preserved by the `toMutableMap()` copy and never visited
        // by visibleDefs). Backend precedence for two conflicting keys is
        // undefined; we explicitly converge on the def's canonical key.
        ALIAS_GROUPS[def.key]?.forEach { alias ->
            if (alias != def.key) newExtras.remove(alias)
        }
    }
    return previous.copy(
        temperature = temperature,
        topP = topP,
        maxTokens = maxOutputTokens ?: previous.maxTokens,
        topPKey = topPKey,
        maxTokensKey = maxTokensKey,
        extras = newExtras,
    )
}

private val PRIMARY_TYPED_KEYS = setOf(
    "temperature", "top_p", "topP", "max_tokens", "maxTokens", "maxOutputTokens",
)

/**
 * Wire-key alias groups that share a single UX slot in
 * `ModelParameters.getValueForKey` / `withUpdatedKey`. When the editor saves
 * the def's canonical key, any legacy alias must be cleared so that an agent
 * doesn't carry two semantically-identical keys with potentially diverging
 * values. Pairs derive from `ModelParameterContent.kt` aliases:
 *  - `system` / `promptPrefix` → customInstructions slot
 *  - `chatGptLabel` / `modelLabel` → customName slot
 *  - `topP` / `top_p` → handled separately via PRIMARY_TYPED_KEYS rewrite
 *  - `max_tokens` / `maxTokens` / `maxOutputTokens` → ditto
 */
private val ALIAS_GROUPS: Map<String, Set<String>> = mapOf(
    "system" to setOf("system", "promptPrefix"),
    "promptPrefix" to setOf("system", "promptPrefix"),
    "chatGptLabel" to setOf("chatGptLabel", "modelLabel"),
    "modelLabel" to setOf("chatGptLabel", "modelLabel"),
)

private fun encodeAsJsonElement(value: String, type: ParameterType): JsonElement = when (type) {
    ParameterType.SWITCH, ParameterType.CHECKBOX -> JsonPrimitive(value.toBoolean())
    ParameterType.SLIDER -> {
        value.toIntOrNull()?.let { JsonPrimitive(it) }
            ?: value.toDoubleOrNull()?.let { JsonPrimitive(it) }
            ?: JsonPrimitive(value)
    }
    ParameterType.TAGS -> buildJsonArray {
        value.split("\n").filter { it.isNotBlank() }.forEach { add(JsonPrimitive(it)) }
    }
    // ENUM_SLIDER and DROPDOWN values are always strings — keep as JsonPrimitive string.
    else -> JsonPrimitive(value)
}
