package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.model.VerifyTwoFactorOutcome
import com.google.common.truth.Truth.assertThat
import io.ktor.serialization.JsonConvertException
import org.junit.Test
import java.io.IOException

/**
 * Pins [classifyVerifyFailure] to the backend's 2FA verify contract
 * (`upstream/api/server/controllers/auth/TwoFactorAuthController.js`): only a 401 means the
 * server evaluated the submitted code, and only an undecodable 2xx means it consumed the code
 * without yielding a session — everything else never judged the code at all. Only wire exceptions
 * reach the classifier; commit-path faults are covered by [AuthRepositoryImplVerifyTest].
 */
class VerifyFailureClassificationTest {

    @Test
    fun `a 401 is the server's verdict on the code`() {
        // Backend: wrong code and expired temp session both answer 401 — the entry is spent.
        val outcome = classifyVerifyFailure(ApiException(401, "Invalid 2FA code or backup code"))

        assertThat(outcome).isEqualTo(VerifyTwoFactorOutcome.CodeRejected("Invalid 2FA code or backup code"))
    }

    @Test
    fun `a 400 refused the request before evaluating the code`() {
        // Backend: missing tempToken / 2FA-not-enabled answer 400 without touching the code.
        val outcome = classifyVerifyFailure(ApiException(400, "2FA is not enabled for this user"))

        assertThat(outcome).isEqualTo(VerifyTwoFactorOutcome.NotEvaluated("2FA is not enabled for this user"))
    }

    @Test
    fun `a 429 rate limit never judged the code`() {
        val message = "Too many verification attempts, please try again after 5 minutes."
        val outcome = classifyVerifyFailure(ApiException(429, message))

        assertThat(outcome).isEqualTo(VerifyTwoFactorOutcome.NotEvaluated(message))
    }

    @Test
    fun `a 5xx is a server fault, not a verdict`() {
        val outcome = classifyVerifyFailure(ApiException(500, "Something went wrong"))

        assertThat(outcome).isEqualTo(VerifyTwoFactorOutcome.NotEvaluated("Something went wrong"))
    }

    @Test
    fun `an undecodable 2xx body consumed the code without a session`() {
        // Ktor's ContentNegotiation wraps every 2xx body decode failure in JsonConvertException
        // (a ContentConvertException) — this is the actual runtime type, not a bare kotlinx
        // SerializationException. AuthApiLoginTest pins that this is what the wire really throws.
        val outcome = classifyVerifyFailure(JsonConvertException("Illegal input: Unexpected JSON token"))

        assertThat(outcome).isEqualTo(VerifyTwoFactorOutcome.SessionIncomplete(INCOMPLETE_AUTH_MESSAGE))
    }

    @Test
    fun `a transport failure maps to ConnectionFailure`() {
        assertThat(classifyVerifyFailure(IOException("connection reset")))
            .isEqualTo(VerifyTwoFactorOutcome.ConnectionFailure)
    }

    @Test
    fun `a missing exception maps to ConnectionFailure`() {
        assertThat(classifyVerifyFailure(null)).isEqualTo(VerifyTwoFactorOutcome.ConnectionFailure)
    }
}
