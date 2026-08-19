package com.garfiec.librechat.core.model.serializer

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decodes a TEXT content-part body that arrives as either a plain string or an
 * `{ "value": "...", "annotations": [...] }` object, normalizing both to the string.
 *
 * Upstream d920328bfa53: the PUT message-content edit route spread-preserves the edited part, so
 * a part whose `text` was the object form (the Open Responses annotated-text shape) PERSISTS as
 * that object — every later fetch of the conversation returns it. A strict `String?` field throws
 * on the object and the exception rejects the whole containing response (a `GET /messages` page,
 * a Final frame), which reads as data loss, not as one odd part. Web reads
 * `typeof text === 'string' ? text : text?.value` at every consumer; mobile normalizes once at
 * decode instead so everything downstream keeps seeing a `String`.
 *
 * `annotations` are deliberately dropped: nothing on mobile renders them, and re-encoding (the
 * Room cache, conversation export) writes the plain string — the shape every consumer and older
 * app version already reads.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object FlexibleTextSerializer : KSerializer<String?> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleText", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): String? {
        // decodeString() cannot degrade a structured value: it throws with the lexer mid-value,
        // failing the enclosing payload decode — the exact loss this serializer prevents.
        if (decoder is JsonDecoder) {
            return when (val element = decoder.decodeJsonElement()) {
                is JsonNull -> null
                is JsonPrimitive -> element.content
                is JsonObject -> (element["value"] as? JsonPrimitive)
                    ?.takeIf { it !is JsonNull }
                    ?.content
                else -> null
            }
        }
        // Non-JSON formats: the nullable descriptor delivers explicit nulls here, so the null
        // mark must be consumed — decodeString() on a null crashes.
        if (!decoder.decodeNotNullMark()) {
            decoder.decodeNull()
            return null
        }
        return decoder.decodeString()
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value)
        }
    }
}
