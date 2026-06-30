package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.ui.components.ModelParameters
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelParamPayloadTest {

    private fun build(params: ModelParameters) = ModelParamPayload.build(
        endpoint = "anthropic",
        provider = null,
        model = null,
        extendedEffortSupported = false,
        params = params,
    )

    @Test
    fun `untouched params send nothing`() {
        assertTrue(build(ModelParameters.DEFAULT).isEmpty())
    }

    @Test
    fun `changed temperature is sent as a number`() {
        val result = build(ModelParameters.DEFAULT.copy(temperature = 0.7f))
        assertEquals(0.7, result["temperature"]?.jsonPrimitive?.double)
    }

    @Test
    fun `promptCacheTtl is sent as a string when set`() {
        val params = ModelParameters.DEFAULT.copy(dynamicValues = mapOf("promptCacheTtl" to "1h"))
        val result = build(params)
        assertEquals("1h", result["promptCacheTtl"]?.jsonPrimitive?.content)
    }

    @Test
    fun `promptCache toggled on is transmitted as a boolean`() {
        val params = ModelParameters.DEFAULT.copy(dynamicValues = mapOf("promptCache" to "true"))
        val result = build(params)
        assertTrue(result["promptCache"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `default-valued params are not over-sent`() {
        // Regression guard: the sheet seeds default-valued entries into dynamicValues on open; those
        // must NOT be transmitted (no promptCache=false, no topP=1.0, etc.) on an untouched chat.
        val seeded = ModelParameters.DEFAULT.copy(
            dynamicValues = mapOf("promptCache" to "false", "top_p" to "1.0", "topP" to "1.0"),
        )
        val result = build(seeded)
        assertNull(result["promptCache"])
        assertNull(result["top_p"])
        assertNull(result["topP"])
    }
}
