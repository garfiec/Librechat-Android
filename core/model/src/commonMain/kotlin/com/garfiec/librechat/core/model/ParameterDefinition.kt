package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Describes a dynamic model parameter that can be configured per-endpoint.
 * Used by ModelParameterContent to render appropriate controls.
 */
@Serializable
data class ParameterDefinition(
    val key: String,
    val label: String,
    val type: ParameterType,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val default: String? = null,
    val description: String? = null,
    val options: List<String>? = null,
    /** Optional input/textarea placeholder shown when the value is blank. */
    val placeholder: String? = null,
    /** Optional dropdown label overrides keyed by option value. Lets the UI display
     *  e.g. "Unset" for the empty-string wire value, matching upstream enumMappings. */
    val optionLabels: Map<String, String>? = null,
)

@Serializable
enum class ParameterType {
    @SerialName("slider")
    SLIDER,

    @SerialName("dropdown")
    DROPDOWN,

    @SerialName("checkbox")
    CHECKBOX,

    @SerialName("text")
    TEXT,

    @SerialName("switch")
    SWITCH,

    @SerialName("textarea")
    TEXTAREA,

    @SerialName("tags")
    TAGS,

    /** Discrete-value slider over a list of [ParameterDefinition.options].
     *  Used for enum-valued controls (reasoning_effort, verbosity, etc.)
     *  that upstream renders as a slider mapped onto enum positions. */
    @SerialName("enum_slider")
    ENUM_SLIDER,
}
