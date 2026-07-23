package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.model.ParameterType
import com.garfiec.librechat.core.ui.components.EndpointParameterRegistry
import com.garfiec.librechat.core.ui.components.ModelParameters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Serializes the configured model parameters into the provider-keyed map the server expects on a
 * chat send. Mirrors the web client, which spreads the `endpointOption` (the conversation's model
 * params) at the top level of the chat payload (`createPayload.ts: { ...userMessage, ...endpointOption }`).
 *
 * The mobile parameter sheet is driven by [EndpointParameterRegistry] definitions whose `key` is the
 * server's wire key (provider-aware: `top_p` for OpenAI, `topP`/`topK` for Anthropic, `promptCache`/
 * `promptCacheTtl`, etc.). We emit only the params the user actually changed from the composer's
 * default state — so an untouched sheet sends nothing and the server keeps its own defaults, exactly
 * as before.
 */
object ModelParamPayload {

    /**
     * Builds the model-parameter map for the given endpoint selection, carrying only the params the
     * user changed from the default composer state. Returns an empty object when nothing was customized.
     */
    fun build(
        endpoint: String,
        provider: String?,
        model: String?,
        extendedEffortSupported: Boolean,
        params: ModelParameters,
    ): JsonObject {
        val definitions = EndpointParameterRegistry.getDefinitions(
            endpoint = endpoint,
            extendedEffortSupported = extendedEffortSupported,
            provider = provider,
            model = model,
        )
        val out = LinkedHashMap<String, JsonElement>()
        for (definition in definitions) {
            val key = definition.key
            val raw = params.getValueForKey(key)
            // Skip any value the server would apply on its own: a blank (unset), the composer baseline
            // (ModelParameters.DEFAULT), or the provider-specific registry default. The registry default
            // must be checked too — for a dynamic-only key like promptCache the composer baseline is ""
            // (empty dynamicValues), so a seeded default-valued entry would otherwise be over-sent. Mere
            // presence in `dynamicValues` is not "changed": the sheet seeds default-valued entries on open.
            if (raw.isBlank() || raw == ModelParameters.DEFAULT.getValueForKey(key) || raw == definition.default) continue
            out[key] = encode(definition.type, raw)
        }
        return JsonObject(out)
    }

    /** Encodes a string param value to a typed JSON element matching its control type. */
    private fun encode(type: ParameterType, raw: String): JsonElement = when (type) {
        ParameterType.CHECKBOX, ParameterType.SWITCH ->
            JsonPrimitive(raw.toBooleanStrictOrNull() ?: (raw == "true"))
        ParameterType.SLIDER -> {
            val asInt = raw.toIntOrNull()
            when {
                asInt != null -> JsonPrimitive(asInt)
                else -> raw.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)
            }
        }
        ParameterType.TAGS ->
            JsonArray(
                raw.split('\n', ',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { JsonPrimitive(it) },
            )
        // DROPDOWN, ENUM_SLIDER, TEXT, TEXTAREA -> string (e.g. promptCacheTtl "5m"/"1h", reasoning_effort)
        else -> JsonPrimitive(raw)
    }
}
