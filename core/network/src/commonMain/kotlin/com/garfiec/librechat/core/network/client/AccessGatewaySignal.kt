package com.garfiec.librechat.core.network.client

/**
 * The single definition of "an access gateway answered this, not the server" (issue #287).
 *
 * Must stay the only copy: the Ktor plugin and the iOS raw-socket SSE transport share nothing else,
 * and a second copy would let one platform drift and go quietly undetected. Cloudflare Access only —
 * see `core/network/CLAUDE.md` for why cookie-based gateways are out of scope.
 */
internal object AccessGatewaySignal {

    /** The `WWW-Authenticate` scheme Cloudflare Access answers a rejected request with. */
    const val SCHEME = "Cloudflare-Access"

    /**
     * Whether a `WWW-Authenticate` value is a gateway challenge. Substring, not equality: the header
     * is a comma-separated list and the scheme may carry parameters.
     */
    fun isGatewayChallenge(wwwAuthenticate: String?): Boolean =
        wwwAuthenticate?.contains(SCHEME, ignoreCase = true) == true
}
