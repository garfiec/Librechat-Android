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
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/**
 * ISO-8601 timestamp serializer that degrades unparseable input to `null` instead of throwing.
 *
 * Leniency is load-bearing, not tolerance for its own sake: a `Conversation` rides inside larger
 * decoded payloads (conversation-list pages, SSE `Final` events), where a strict serializer would
 * let one garbage timestamp fail the *entire* payload decode. A missing timestamp degrades to the
 * "Unknown" date bucket; a dropped conversation is data loss.
 *
 * Encoding must stay an ISO-8601 string: exported conversation files embed these fields, and
 * exports written by this version must remain readable by app versions that still model the field
 * as a `String`.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object LenientInstantSerializer : KSerializer<Instant?> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientInstant", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): Instant? {
        // decodeString() cannot degrade a structured value ({"$date": …}, an array): it throws with
        // the lexer left mid-value, so the exception fails the *enclosing* payload decode — the
        // exact loss this serializer exists to prevent. decodeJsonElement() consumes whatever token
        // is present, letting any non-string shape degrade to null.
        if (decoder is JsonDecoder) {
            val primitive = decoder.decodeJsonElement() as? JsonPrimitive ?: return null
            if (primitive is JsonNull) return null
            return try {
                Instant.parse(primitive.content)
            } catch (_: Exception) {
                null
            }
        }
        // Non-JSON formats: with a nullable descriptor this serializer receives explicit nulls
        // itself, so the null mark must be consumed here — decodeString() on a null crashes.
        if (!decoder.decodeNotNullMark()) {
            decoder.decodeNull()
            return null
        }
        return try {
            Instant.parse(decoder.decodeString())
        } catch (_: Exception) {
            null
        }
    }

    override fun serialize(encoder: Encoder, value: Instant?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.toString())
        }
    }
}
