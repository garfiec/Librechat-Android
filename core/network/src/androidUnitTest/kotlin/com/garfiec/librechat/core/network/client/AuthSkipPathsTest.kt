package com.garfiec.librechat.core.network.client

import com.google.common.truth.Truth.assertThat
import io.ktor.http.URLBuilder
import org.junit.Test

/**
 * The skip list is shared by two plugins that must not drift: [AuthInterceptorPlugin] keeps these
 * paths out of the bearer attach and the 401-refresh leg, and [SwitchBarrierPlugin] keeps them out of
 * proactive renewal. A sign-in POST that first drove a refresh of the session it is replacing would
 * put that whole round trip in front of the Sign in button on a slow or failing server.
 */
class AuthSkipPathsTest {

    private fun matches(path: String): Boolean = isAuthSkipPath(URLBuilder(path))

    @Test
    fun `matches the auth endpoints that take no bearer`() {
        assertThat(matches("https://chat.example.com/api/auth/login")).isTrue()
        assertThat(matches("https://chat.example.com/api/auth/register")).isTrue()
        assertThat(matches("https://chat.example.com/api/auth/refresh")).isTrue()
        assertThat(matches("https://chat.example.com/api/auth/requestPasswordReset")).isTrue()
        assertThat(matches("https://chat.example.com/api/auth/resetPassword")).isTrue()
        assertThat(matches("https://chat.example.com/api/auth/2fa/verify-temp")).isTrue()
    }

    @Test
    fun `matches a relative path, which is the form the barrier sees`() {
        // The barrier phase runs before the base URL is applied, so its URLBuilder has no host yet.
        assertThat(matches("/api/auth/login")).isTrue()
    }

    @Test
    fun `does not match ordinary api paths`() {
        assertThat(matches("/api/convos")).isFalse()
        assertThat(matches("/api/agents/chat")).isFalse()
        assertThat(matches("/api/user")).isFalse()
    }

    @Test
    fun `matches whole segments only`() {
        // A path-prefixed deployment must not have every request it serves treated as an auth path.
        assertThat(matches("/apps/auth/login-x")).isFalse()
        assertThat(matches("/api/auth/loginhistory")).isFalse()
        // ...but a genuine prefix deployment's real login endpoint still matches.
        assertThat(matches("/apps/librechat/api/auth/login")).isTrue()
    }
}
