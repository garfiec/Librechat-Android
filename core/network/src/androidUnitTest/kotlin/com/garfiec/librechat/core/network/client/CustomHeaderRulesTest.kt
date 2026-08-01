package com.garfiec.librechat.core.network.client

import com.google.common.truth.Truth.assertThat
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import org.junit.Test

/**
 * The validation contract for user-entered gateway headers (issue #287), plus the `Cookie`
 * merge/unpick pair that the redirect guard depends on.
 */
class CustomHeaderRulesTest {

    @Test
    fun `accepts a Cloudflare Access service token pair`() {
        assertThat(CustomHeaderRules.validate("CF-Access-Client-Id", "4cf2254e.access")).isNull()
        assertThat(CustomHeaderRules.validate("CF-Access-Client-Secret", "efde2dc9dbfff6c4")).isNull()
    }

    @Test
    fun `values may contain spaces and may be empty`() {
        // Spaces are legal in a header value (`Bearer x`, `key=a b`), and some gateways treat a
        // present-but-empty header as meaningful.
        assertThat(CustomHeaderRules.validateValue("token with spaces")).isNull()
        assertThat(CustomHeaderRules.validateValue("")).isNull()
    }

    @Test
    fun `rejects control characters and non-ASCII in values`() {
        // CR/LF are request-splitting. Non-ASCII is the subtler one: it survives Ktor's own
        // checkHeaderValue but throws inside OkHttp's Headers.Builder, whose message quotes the
        // offending value — so a pasted secret would fail every Android request with the secret
        // verbatim in the exception text.
        assertThat(CustomHeaderRules.validateValue("bad\rvalue")).isEqualTo(HeaderRejection.InvalidValue)
        assertThat(CustomHeaderRules.validateValue("bad\nvalue")).isEqualTo(HeaderRejection.InvalidValue)
        assertThat(CustomHeaderRules.validateValue("bad\u0000value")).isEqualTo(HeaderRejection.InvalidValue)
        // U+2019 right single quote and U+00A0 no-break space — what a pasted token actually looks
        // like once it has been through a dashboard's rich-text field or a chat client.
        assertThat(CustomHeaderRules.validateValue("smart\u2019quote")).isEqualTo(HeaderRejection.InvalidValue)
        assertThat(CustomHeaderRules.validateValue("nb\u00A0space")).isEqualTo(HeaderRejection.InvalidValue)
        // A plain space is legal and must NOT be rejected.
        assertThat(CustomHeaderRules.validateValue("plain space")).isNull()
    }

    @Test
    fun `trims surrounding whitespace off pasted values`() {
        assertThat(CustomHeaderRules.normalizeValue("  token\n")).isEqualTo("token")
        assertThat(CustomHeaderRules.sanitize(mapOf(" X-Token " to "  secret  ")))
            .containsExactly("X-Token", "secret")
    }

    @Test
    fun `rejects names the transport or the app owns`() {
        listOf("Authorization", "user-agent", "Host", "Accept", "Content-Length", "TE").forEach { name ->
            assertThat(CustomHeaderRules.validateName(name)).isEqualTo(HeaderRejection.ReservedName)
        }
    }

    @Test
    fun `Cookie is not reserved`() {
        // Authelia, Authentik and oauth2-proxy all authenticate on a session cookie; reserving it
        // would make the "any static-header gateway" promise false for the commonest self-hosted setups.
        assertThat(CustomHeaderRules.validateName("Cookie")).isNull()
    }

    @Test
    fun `rejects malformed names`() {
        listOf("", "   ", "X Token", "X:Token", "X\nToken").forEach { name ->
            assertThat(CustomHeaderRules.validateName(name)).isEqualTo(HeaderRejection.InvalidName)
        }
    }

    @Test
    fun `sanitize drops rejected pairs and keeps the rest`() {
        val result = CustomHeaderRules.sanitize(
            mapOf(
                "CF-Access-Client-Id" to "keep-me",
                "Authorization" to "Basic drop-me",
                "X Bad Name" to "drop-me",
                "X-Bad-Value" to "drop\r\nme",
            ),
        )
        assertThat(result).containsExactly("CF-Access-Client-Id", "keep-me")
    }

    @Test
    fun `applyCustomHeaders merges into a single Cookie header`() {
        // Ktor's append is additive and RFC 6265 allows exactly one Cookie header; two lines are
        // handled inconsistently across servers, and the app's own refreshToken cookie is on the line
        // that would break.
        val builder = HeadersBuilder().apply {
            append(HttpHeaders.Cookie, "refreshToken=rt-1")
            applyCustomHeaders(mapOf("Cookie" to "CF_Authorization=jwt"))
        }
        val cookies = builder.getAll(HttpHeaders.Cookie).orEmpty()
        assertThat(cookies).hasSize(1)
        assertThat(cookies.single()).isEqualTo("CF_Authorization=jwt; refreshToken=rt-1")
    }

    @Test
    fun `the app's own cookie wins over a colliding user segment`() {
        // Pasting a whole Cookie header out of browser devtools is the likely way anyone configures a
        // cookie-auth gateway, and it can carry a stale refreshToken. Cookie parsers keep the FIRST
        // occurrence of a repeated name (Express's cookie.parse among them), so ordering alone would
        // let that stale value shadow the app's real one and sign the user out at every refresh.
        val builder = HeadersBuilder()
        builder.append(HttpHeaders.Cookie, "refreshToken=app-token")
        builder.applyCustomHeaders(
            mapOf(HttpHeaders.Cookie to "authelia_session=abc; refreshToken=stale-browser-token"),
        )

        val cookies = builder.getAll(HttpHeaders.Cookie).orEmpty()
        assertThat(cookies).hasSize(1)
        assertThat(cookies.single()).isEqualTo("authelia_session=abc; refreshToken=app-token")
    }

    @Test
    fun `cookie name matching is case-sensitive per RFC 6265`() {
        // `refreshToken` and `refreshtoken` are genuinely different cookies; folding them would drop a
        // user segment the gateway needs.
        val builder = HeadersBuilder()
        builder.append(HttpHeaders.Cookie, "refreshToken=app-token")
        builder.applyCustomHeaders(mapOf(HttpHeaders.Cookie to "refreshtoken=gateway-value"))

        assertThat(builder.getAll(HttpHeaders.Cookie).orEmpty().single())
            .isEqualTo("refreshtoken=gateway-value; refreshToken=app-token")
    }

    @Test
    fun `stripCustomHeaders unpicks the user cookie and keeps the app's`() {
        val builder = HeadersBuilder().apply {
            append(HttpHeaders.Cookie, "refreshToken=rt-1")
            applyCustomHeaders(mapOf("Cookie" to "CF_Authorization=jwt", "CF-Access-Client-Id" to "id"))
            stripCustomHeaders(mapOf("Cookie" to "CF_Authorization=jwt", "CF-Access-Client-Id" to "id"))
        }
        assertThat(builder["CF-Access-Client-Id"]).isNull()
        assertThat(builder.getAll(HttpHeaders.Cookie).orEmpty().single()).isEqualTo("refreshToken=rt-1")
    }

    @Test
    fun `customHeaderLines renders CRLF-terminated lines for the iOS SSE transport`() {
        // The iOS transport hand-writes its request, so this is the only unit-testable seam for it —
        // core:network has no iosTest source set.
        val lines = customHeaderLines(mapOf("CF-Access-Client-Id" to "id", "Authorization" to "dropped"))
        assertThat(lines).isEqualTo("CF-Access-Client-Id: id\r\n")
        assertThat(customHeaderLines(emptyMap())).isEmpty()
    }
}
