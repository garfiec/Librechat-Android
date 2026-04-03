package com.librechat.android.core.model.speech

import androidx.compose.runtime.Immutable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class SpeechToTextResponse(
    val text: String? = null,
)

@Serializable
data class TextToSpeechRequest(
    @SerialName("input") val text: String,
    val voice: String? = null,
    val model: String? = null,
)

@Immutable
@Serializable
data class TtsVoice(
    val id: String,
    val name: String,
    val provider: String? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
)

/**
 * Handles both string arrays `["alloy","echo"]` and object arrays
 * `[{"id":"alloy","name":"Alloy"}]` from the server.
 */
internal object TtsVoiceListSerializer : KSerializer<List<TtsVoice>> {
    private val delegateSerializer = ListSerializer(TtsVoice.serializer())

    override val descriptor: SerialDescriptor = delegateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: List<TtsVoice>) {
        delegateSerializer.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<TtsVoice> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return delegateSerializer.deserialize(decoder)

        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonArray && element.firstOrNull() is JsonPrimitive) {
            return element.map { str ->
                val name = str.jsonPrimitive.content
                TtsVoice(id = name, name = name)
            }
        }
        return (element as JsonArray).map { obj ->
            jsonDecoder.json.decodeFromJsonElement(TtsVoice.serializer(), obj)
        }
    }
}

/**
 * Nullable wrapper around [TtsVoiceListSerializer].
 */
internal object NullableTtsVoiceListSerializer : KSerializer<List<TtsVoice>?> {
    private val delegate = TtsVoiceListSerializer.nullable

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<TtsVoice>?) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<TtsVoice>? {
        return delegate.deserialize(decoder)
    }
}

@Serializable
data class SpeechConfig(
    @SerialName("sttExternal") val sttExternal: Boolean = false,
    @SerialName("ttsExternal") val ttsExternal: Boolean = false,
    @SerialName("sttProvider") val sttProvider: String? = null,
    @SerialName("ttsProvider") val ttsProvider: String? = null,
    @Serializable(with = NullableTtsVoiceListSerializer::class)
    val voices: List<TtsVoice>? = null,
)
