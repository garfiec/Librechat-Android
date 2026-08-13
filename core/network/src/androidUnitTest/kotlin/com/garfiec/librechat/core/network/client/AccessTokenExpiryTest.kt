package com.garfiec.librechat.core.network.client

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The contract is "never throws, null when unsure". A throw here would propagate out of the request
 * barrier and fail an otherwise-fine request; a wrong non-null would drive a refresh loop. Every
 * malformed shape a server (or a proxy, or an OpenID pass-through) can hand us is enumerated.
 */
@OptIn(ExperimentalEncodingApi::class)
class AccessTokenExpiryTest {

    private fun jwt(payloadJson: String, padded: Boolean = false): String {
        val encoder = if (padded) {
            Base64.UrlSafe
        } else {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        }
        val header = encoder.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        val payload = encoder.encode(payloadJson.encodeToByteArray())
        return "$header.$payload.signature-not-verified"
    }

    @Test
    fun `reads exp from a well-formed token`() {
        val token = jwt("""{"id":"u1","exp":1754000000}""")
        assertThat(expiresAtEpochMillisOrNull(token)).isEqualTo(1_754_000_000_000L)
    }

    @Test
    fun `accepts a payload whose base64 padding survived`() {
        // jwt.sign strips '=' padding, but nothing in the wire format forbids it and a proxy or a
        // non-Node issuer may leave it on.
        val token = jwt("""{"exp":1754000000}""", padded = true)
        assertThat(expiresAtEpochMillisOrNull(token)).isEqualTo(1_754_000_000_000L)
    }

    @Test
    fun `accepts exp as a double`() {
        // jwt.sign emits an integer, but the decoder must not depend on that.
        val token = jwt("""{"exp":1754000000.75}""")
        assertThat(expiresAtEpochMillisOrNull(token)).isEqualTo(1_754_000_000_750L)
    }

    @Test
    fun `decodes a payload containing url-safe alphabet characters`() {
        // Padding aside, the difference between the standard and URL-safe alphabets is '+/' vs '-_'.
        // A payload that happens to encode to either would fail to decode under the wrong one, and the
        // failure is data-dependent — it would show up as an occasional token that never renews.
        val token = jwt("""{"sub":"a?b>c~dÿþ","exp":1754000000}""")
        assertThat(expiresAtEpochMillisOrNull(token)).isEqualTo(1_754_000_000_000L)
    }

    @Test
    fun `returns null when exp is absent`() {
        assertThat(expiresAtEpochMillisOrNull(jwt("""{"id":"u1"}"""))).isNull()
    }

    @Test
    fun `returns null when exp is a string`() {
        assertThat(expiresAtEpochMillisOrNull(jwt("""{"exp":"1754000000"}"""))).isNull()
    }

    @Test
    fun `returns null when exp is not a primitive`() {
        assertThat(expiresAtEpochMillisOrNull(jwt("""{"exp":{"at":1754000000}}"""))).isNull()
    }

    @Test
    fun `returns null for an opaque non-jwt token`() {
        // The OpenID pass-through case: a provider access token that is not a JWT at all.
        assertThat(expiresAtEpochMillisOrNull("gho_16C7e42F292c6912E7710c838347Ae178B4a")).isNull()
    }

    @Test
    fun `returns null for a three-segment token whose payload is not base64`() {
        assertThat(expiresAtEpochMillisOrNull("aaa.!!!not-base64!!!.ccc")).isNull()
    }

    @Test
    fun `returns null for a three-segment token whose payload is not json`() {
        val payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("not json".encodeToByteArray())
        assertThat(expiresAtEpochMillisOrNull("aaa.$payload.ccc")).isNull()
    }

    @Test
    fun `returns null for an empty payload segment`() {
        assertThat(expiresAtEpochMillisOrNull("aaa..ccc")).isNull()
    }

    @Test
    fun `returns null for an empty string`() {
        assertThat(expiresAtEpochMillisOrNull("")).isNull()
    }
}
