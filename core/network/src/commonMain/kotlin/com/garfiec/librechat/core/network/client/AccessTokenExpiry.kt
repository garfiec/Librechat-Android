package com.garfiec.librechat.core.network.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Tolerant reader for a payload we did not mint; only the `exp` claim is ever looked at. */
private val expiryJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * When the LibreChat access token expires, read out of the token itself.
 *
 * The backend mints it with `jwt.sign(payload, secret, { expiresIn })`
 * (`packages/data-schemas/src/crypto/index.ts`), so the deadline is already in the string we hold —
 * no third per-account storage key to write, re-home, delete and migrate at every token call site,
 * and nothing that can desync from the token it describes.
 *
 * Returns the `exp` claim as epoch millis, or null when [token] is not a JWT whose payload carries a
 * numeric `exp`. Never throws — every malformed shape (wrong segment count, non-base64 payload,
 * non-JSON payload, absent or non-numeric `exp`) is a null.
 *
 * **Null means "unknown", and callers must treat it as "do nothing".** It is not merely defensive: an
 * OpenID deployment that hands through the provider's own access token may give us something opaque,
 * and the only safe reading of an undecodable token is to leave it to the reactive 401 path.
 */
@OptIn(ExperimentalEncodingApi::class)
fun expiresAtEpochMillisOrNull(token: String): Long? {
    val segments = token.split('.')
    if (segments.size != JWT_SEGMENTS) return null
    val payload = segments[1]
    if (payload.isEmpty()) return null
    val decoded = try {
        // JWT payloads are base64url with the padding stripped; the URL-safe alphabet is required
        // (a payload containing '-' or '_' fails to decode under the standard one) and
        // `withPadding(ABSENT_OPTIONAL)` accepts the segment whether or not '=' survived.
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload).decodeToString()
    } catch (_: IllegalArgumentException) {
        return null
    }
    val exp = try {
        (expiryJson.parseToJsonElement(decoded) as? JsonObject)?.get("exp")
    } catch (_: Exception) {
        // parseToJsonElement throws SerializationException, but a payload that decoded to invalid
        // UTF-8 can surface other shapes; nothing here is worth failing a request over.
        return null
    }
    // Read through the safe accessor, not `.jsonPrimitive` — that throws on a non-primitive `exp`
    // (an object or array), which is exactly the malformed input this function exists to absorb.
    val seconds = (exp as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toDoubleOrNull() ?: return null
    return (seconds * MILLIS_PER_SECOND).toLong()
}

private const val JWT_SEGMENTS = 3
private const val MILLIS_PER_SECOND = 1000
