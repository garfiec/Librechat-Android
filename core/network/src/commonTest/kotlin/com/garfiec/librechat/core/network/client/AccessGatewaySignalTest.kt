package com.garfiec.librechat.core.network.client

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The predicate every transport's gateway check routes through — these cases pin what the Ktor
 * clients and the iOS raw-socket transport agree counts as a rejection.
 */
class AccessGatewaySignalTest {

    @Test
    fun `the bare scheme is a challenge`() {
        assertTrue(AccessGatewaySignal.isGatewayChallenge("Cloudflare-Access"))
    }

    /** What the live rig actually sends: the scheme followed by parameters. */
    @Test
    fun `the scheme with parameters is a challenge`() {
        assertTrue(
            AccessGatewaySignal.isGatewayChallenge(
                "Cloudflare-Access resource_metadata=\"https://chat.example.com/.well-known\"",
            ),
        )
    }

    /** The header is a comma-separated list, so matching only a prefix would miss the gateway. */
    @Test
    fun `the scheme is found when it is not the first challenge listed`() {
        assertTrue(AccessGatewaySignal.isGatewayChallenge("Basic realm=\"x\", Cloudflare-Access"))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(AccessGatewaySignal.isGatewayChallenge("cloudflare-access"))
    }

    /** The header is absent on every successful response, so a null must never read as a rejection. */
    @Test
    fun `an absent header is not a challenge`() {
        assertFalse(AccessGatewaySignal.isGatewayChallenge(null))
        assertFalse(AccessGatewaySignal.isGatewayChallenge(""))
    }

    /**
     * LibreChat's own 401s carry `Bearer` — treating those as a gateway block would send a user with
     * an ordinary expired session to edit headers that are fine.
     */
    @Test
    fun `an unrelated challenge is not a gateway block`() {
        assertFalse(AccessGatewaySignal.isGatewayChallenge("Bearer realm=\"api\""))
        assertFalse(AccessGatewaySignal.isGatewayChallenge("Basic realm=\"proxy\""))
    }
}
