package com.garfiec.librechat.core.network.client

import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders

/**
 * Static per-server request headers the user configures for a deployment that sits behind a reverse
 * proxy or access gateway (issue #287). The driving case is Cloudflare Access service tokens
 * (`CF-Access-Client-Id` / `CF-Access-Client-Secret`), but nothing here is Cloudflare-specific — any
 * gateway that authenticates on static headers works.
 *
 * Keyed by **server, not account**: the credential belongs to the deployment, and it is needed on the
 * very first request — before any account exists — so an account-scoped store could never carry it.
 */
interface ServerHeadersProvider {
    /**
     * Suspends until the persisted headers have resolved. Deliberately URL-free: the snapshot in
     * [SwitchGate.captureSnapshot] only resolves its base URL *inside* the switch lock, so a URL-keyed
     * await taken before that lock could be awaiting a different server than the snapshot lands on.
     * The backing store is whole-store anyway, so one warm gate covers every server.
     *
     * Startup-path callers await this so a cold-start request can't be built before the store has
     * warmed up — missing the gateway credential on the *first* request is not a recoverable miss: an
     * access gateway answers it with a redirect to its own login page, not a retryable error.
     */
    suspend fun awaitWarm()

    /**
     * Headers configured for [baseUrl]'s server, or empty when none are set / the store hasn't warmed
     * up / [baseUrl] isn't a usable server URL. Must not block and **must not throw** — a blank or
     * partial base URL is routine on the cold-start and failed-probe paths.
     */
    fun headersFor(baseUrl: String): Map<String, String>
}

/** No headers for any server. The default binding when the feature is unconfigured, and for tests. */
object EmptyServerHeadersProvider : ServerHeadersProvider {
    override suspend fun awaitWarm() = Unit
    override fun headersFor(baseUrl: String): Map<String, String> = emptyMap()
}

/**
 * Why a header a user typed can't be trusted verbatim.
 *
 * Rejection is deliberately loud rather than silent-sanitising: a gateway credential that is *almost*
 * right fails as an opaque redirect to a login page, so the user has to be told at entry time.
 */
enum class HeaderRejection {
    /** Blank, or contains a character outside the RFC 7230 `token` set. */
    InvalidName,

    /** Contains a character outside printable US-ASCII (plus HTAB). */
    InvalidValue,

    /** A header the transport or the app itself owns; see [CustomHeaderRules.RESERVED_NAMES]. */
    ReservedName,
}

/** A rejected pair, as its position in the caller's list plus the reason. */
data class IndexedRejection(val index: Int, val reason: HeaderRejection)

/**
 * Validation for user-entered header pairs. Enforced at the UI entry point *and* again at the
 * injection sites, so a value that reached storage through an older build (or a future import path)
 * still can't reach the wire.
 */
object CustomHeaderRules {

    /**
     * Headers the user must not override.
     *
     * - `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Upgrade`, `TE` — the transport
     *   owns these; a user value corrupts framing. On iOS the SSE transport hand-writes the request
     *   line, so a bad `Host` there is a malformed request, not a caught exception.
     * - `Authorization` — the LibreChat session bearer. Overriding it silently breaks auth in a way
     *   that looks like an expired session. (The cost: gateways fronted by HTTP Basic aren't
     *   supported through this feature.)
     * - `User-Agent` — the stock LibreChat server's `ua-parser-js` middleware soft-bans non-browser
     *   UAs on first contact, and that ban outlives the bad config.
     * - `Accept`, `Accept-Encoding`, `Content-Type` — the iOS SSE transport hand-writes `Accept` and
     *   `Accept-Encoding`; a user value emits a second, ambiguous line rather than replacing them.
     * - `Expect`, `Proxy-Authorization` — engine-level semantics the client does not model.
     *
     * `Cookie` is deliberately **absent**: Cloudflare Access, Authelia, Authentik and oauth2-proxy all
     * authenticate on a session cookie, so reserving it would make the "any static-header gateway"
     * promise false for the most common self-hosted deployments. It is merged with the app's own
     * cookie instead — see [applyCustomHeaders].
     */
    val RESERVED_NAMES: Set<String> = setOf(
        "host",
        "content-length",
        "transfer-encoding",
        "connection",
        "upgrade",
        "te",
        "authorization",
        "user-agent",
        "accept",
        "accept-encoding",
        "content-type",
        "expect",
        "proxy-authorization",
    )

    // RFC 7230 token characters. Anything else in a header name is malformed on the wire.
    private const val TOKEN_SPECIALS = "!#$%&'*+-.^_`|~"

    private fun Char.isTokenChar(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in TOKEN_SPECIALS

    /**
     * A header value character that is safe to put on the wire: printable US-ASCII plus HTAB.
     *
     * The bar is deliberately higher than "no CR/LF/NUL". A non-ASCII character (a smart quote or a
     * non-breaking space picked up pasting a token out of a dashboard) passes Ktor's own
     * `checkHeaderValue`, but OkHttp's `Headers.Builder` throws on it — and its message quotes the
     * offending value for any name outside its sensitive list. A pasted secret would then fail every
     * Android request with the secret verbatim in an exception that flows through `safeApiCall`.
     */
    private fun Char.isSafeValueChar(): Boolean = this == '\t' || this.code in 0x20..0x7E

    /** Null when [name] is a usable header name, else why it isn't. */
    fun validateName(name: String): HeaderRejection? = when {
        name.isBlank() || !name.all { it.isTokenChar() } -> HeaderRejection.InvalidName
        name.lowercase() in RESERVED_NAMES -> HeaderRejection.ReservedName
        else -> null
    }

    /**
     * Null when [value] is safe to send. Empty is allowed — some gateways treat a present-but-empty
     * header as meaningful — and spaces are legal inside a value; control and non-ASCII characters
     * never are.
     */
    fun validateValue(value: String): HeaderRejection? =
        if (value.all { it.isSafeValueChar() }) null else HeaderRejection.InvalidValue

    /**
     * Canonical form of a user-entered value: surrounding whitespace stripped. Pasting a token out of
     * a dashboard is the overwhelmingly likely entry path and it very often carries a trailing
     * newline or space, which would otherwise be a silent authentication failure.
     */
    fun normalizeValue(value: String): String = value.trim()

    /** Null when the pair is safe to send, else the first problem found. */
    fun validate(name: String, value: String): HeaderRejection? =
        validateName(name.trim()) ?: validateValue(normalizeValue(value))

    /**
     * Drop every pair that fails [validate], normalizing what survives. The last line of defence
     * before the wire: injection sites call this rather than trusting the store, so a value persisted
     * by an older build — or one that became reserved after a later release added it — can't reach a
     * request.
     */
    fun sanitize(headers: Map<String, String>): Map<String, String> =
        headers.mapNotNull { (name, value) ->
            val trimmedName = name.trim()
            val normalizedValue = normalizeValue(value)
            if (validate(trimmedName, normalizedValue) == null) trimmedName to normalizedValue else null
        }.toMap()

    /**
     * The first pair that can't be sent, or null. Fully blank pairs are skipped rather than rejected:
     * an editor's row list always ends with one the user hasn't filled in yet.
     *
     * Takes plain pairs rather than an editor row type because both editors feed it and `:core:ui`
     * (which owns that type) deliberately does not depend on this module. Single-sourced because the
     * two editors write to the same store: if one of them decided a row was blank and the other did
     * not, they would disagree about whether a given set of rows means "delete this credential".
     */
    fun firstRejection(pairs: List<Pair<String, String>>): IndexedRejection? =
        pairs.withIndex().firstNotNullOfOrNull { (index, pair) ->
            val (name, value) = pair
            if (name.isBlank() && value.isBlank()) {
                null
            } else {
                validate(name, value)?.let { IndexedRejection(index, it) }
            }
        }

    /** Drops blank pairs and normalizes what's left. Later pairs win on a duplicate name. */
    fun toHeaderMap(pairs: List<Pair<String, String>>): Map<String, String> =
        pairs.filterNot { (name, value) -> name.isBlank() && value.isBlank() }
            .associate { (name, value) -> name.trim() to normalizeValue(value) }
}

/**
 * Append [custom] to an outgoing request, merging rather than duplicating `Cookie`.
 *
 * Ktor's `append` is additive, and RFC 6265 allows exactly one `Cookie` header — two lines are
 * handled inconsistently across servers and proxies. The app sets its own `Cookie: refreshToken=…` on
 * the refresh POST, so a user cookie has to be folded into that one header. The custom value goes
 * first: a gateway sitting in front of the origin reads its own cookie without having to parse past
 * the app's.
 *
 * **The app's own segments win on a name collision.** Ordering alone is not enough: cookie parsers
 * keep the *first* occurrence of a repeated name (Express's `cookie.parse` among them), so a user who
 * pastes a whole `Cookie` header out of browser devtools — the likely way anyone configures a
 * cookie-auth gateway — can carry a stale `refreshToken=` that shadows the real one and signs them
 * out at every refresh. Colliding custom segments are therefore dropped, not merely out-ordered.
 */
// NoGetOutsideModuleDefinition is a Koin rule that matches on the name `getAll`; here it is
// Ktor's StringValuesBuilder.getAll, reading back a header this function just wrote. No DI involved.
@Suppress("NoGetOutsideModuleDefinition")
internal fun HeadersBuilder.applyCustomHeaders(custom: Map<String, String>) {
    CustomHeaderRules.sanitize(custom).forEach { (name, value) ->
        if (name.equals(HttpHeaders.Cookie, ignoreCase = true)) {
            val existing = getAll(HttpHeaders.Cookie).orEmpty().flatMap(::cookieSegments)
            // Case-SENSITIVE: RFC 6265 cookie names are case-sensitive, so `refreshToken` and
            // `refreshtoken` are genuinely different cookies and folding them would over-drop.
            val ownNames = existing.map { it.cookieName() }.toSet()
            val merged = (cookieSegments(value).filterNot { it.cookieName() in ownNames } + existing)
                .distinct()
            remove(HttpHeaders.Cookie)
            if (merged.isNotEmpty()) append(HttpHeaders.Cookie, merged.joinToString("; "))
        } else {
            append(name, value)
        }
    }
}

/**
 * Undo [applyCustomHeaders] for a request that is about to leave the server's authority (a redirect
 * off-domain). Ktor's `HttpRedirect` copies every header to the new target and strips only
 * `Authorization`, so without this a server-supplied redirect would hand a long-lived, non-rotating
 * gateway secret to an arbitrary host.
 *
 * `Cookie` is unpicked segment-wise rather than dropped wholesale, so the app's own cookie survives a
 * strip of the user's.
 */
@Suppress("NoGetOutsideModuleDefinition") // Ktor's StringValuesBuilder.getAll, not Koin's — see above.
internal fun HeadersBuilder.stripCustomHeaders(custom: Map<String, String>) {
    CustomHeaderRules.sanitize(custom).forEach { (name, value) ->
        if (name.equals(HttpHeaders.Cookie, ignoreCase = true)) {
            val customSegments = cookieSegments(value).toSet()
            val remaining = getAll(HttpHeaders.Cookie).orEmpty()
                .flatMap(::cookieSegments)
                .filterNot { it in customSegments }
            remove(HttpHeaders.Cookie)
            if (remaining.isNotEmpty()) append(HttpHeaders.Cookie, remaining.joinToString("; "))
        } else {
            remove(name)
        }
    }
}

private fun cookieSegments(value: String): List<String> =
    value.split(';').map { it.trim() }.filter { it.isNotEmpty() }

/** The name half of a `name=value` cookie segment; the whole segment when there is no `=`. */
private fun String.cookieName(): String = substringBefore('=').trim()

/**
 * [custom] rendered as raw HTTP/1.1 header lines (each CRLF-terminated), for the iOS SSE transport,
 * which hand-writes its request rather than going through Ktor.
 *
 * Lives in `commonMain` rather than inline in `SseHttpTransport.ios.kt` so it is unit-testable: the
 * module has no `iosTest` source set, so anything written inside the iOS transport is verifiable only
 * by inspection and on-device runs. Returns "" for an empty map so callers can append unconditionally.
 */
internal fun customHeaderLines(custom: Map<String, String>): String =
    CustomHeaderRules.sanitize(custom)
        .entries
        .joinToString(separator = "") { (name, value) -> "$name: $value\r\n" }
