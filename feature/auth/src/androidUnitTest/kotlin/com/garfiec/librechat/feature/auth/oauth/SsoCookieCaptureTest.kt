package com.garfiec.librechat.feature.auth.oauth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SsoCookieCaptureTest {

    @Test
    fun `extracts refresh token from header`() {
        assertThat(
            extractRefreshTokenFromCookies("refreshToken=abc123"),
        ).isEqualTo("abc123")
    }

    @Test
    fun `returns null when header has no refresh token`() {
        assertThat(extractRefreshTokenFromCookies("foo=bar; baz=qux")).isNull()
    }

    @Test
    fun `returns null for null header`() {
        assertThat(extractRefreshTokenFromCookies(null)).isNull()
    }

    @Test
    fun `extracts token among multiple cookies`() {
        assertThat(
            extractRefreshTokenFromCookies("theme=dark; refreshToken=jwt.token.here; lang=en"),
        ).isEqualTo("jwt.token.here")
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertThat(
            extractRefreshTokenFromCookies("  refreshToken=tokenValue  "),
        ).isEqualTo("tokenValue")
    }

    @Test
    fun `does not match cookie whose name merely contains refreshToken`() {
        assertThat(extractRefreshTokenFromCookies("oldRefreshToken=stale")).isNull()
    }

    @Test
    fun `returns null when refresh token value is empty`() {
        assertThat(extractRefreshTokenFromCookies("refreshToken=")).isNull()
    }

    @Test
    fun `query param present`() {
        assertThat(
            extractQueryParameter("https://x.com/login?redirect=false&error=AUTH_FAILED", "error"),
        ).isEqualTo("AUTH_FAILED")
    }

    @Test
    fun `query param absent returns null`() {
        assertThat(extractQueryParameter("https://x.com/login?redirect=false", "error")).isNull()
    }

    @Test
    fun `query param not first`() {
        assertThat(
            extractQueryParameter("https://x.com/login?redirect=false&error=SOME_CODE", "error"),
        ).isEqualTo("SOME_CODE")
    }

    @Test
    fun `query param fragment excluded`() {
        assertThat(
            extractQueryParameter("https://x.com/login?error=E1#section", "error"),
        ).isEqualTo("E1")
    }

    @Test
    fun `query param name without value returns null`() {
        assertThat(extractQueryParameter("https://x.com/login?error", "error")).isNull()
    }

    @Test
    fun `query param exact name match not substring`() {
        assertThat(
            extractQueryParameter("https://x.com/login?oauth_error=NO", "error"),
        ).isNull()
    }
}