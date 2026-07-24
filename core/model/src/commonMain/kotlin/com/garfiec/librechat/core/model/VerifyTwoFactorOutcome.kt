package com.garfiec.librechat.core.model

/**
 * The total outcome of a 2FA verify attempt, classified once at the repository against the
 * backend's wire contract. The UI's clear-or-keep decision for the entered code follows from
 * the case alone: a [CodeConsumed] case clears the entry for a fresh code; every other failure
 * keeps it intact for a plain retry.
 */
sealed interface VerifyTwoFactorOutcome {
    /** Session committed (tokens stored, account established). Must always complete sign-in. */
    data class Success(val user: User) : VerifyTwoFactorOutcome

    /**
     * The server consumed the single-use code, so the entered code is spent and the entry must
     * clear. The sub-cases differ only in *why* no session resulted; grouping them here lets the
     * one consumer route both with a single branch while keeping the distinction for future UI.
     */
    sealed interface CodeConsumed : VerifyTwoFactorOutcome {
        val message: String
    }

    /**
     * 401 — the server evaluated the submission: wrong/expired code, or expired temp session.
     * The entered code is spent; clear the entry for a fresh one.
     */
    data class CodeRejected(override val message: String) : CodeConsumed

    /**
     * 2xx — the server accepted (consumed) the single-use code but no usable session came back
     * (missing user/token, or an unparseable body). Clear the entry; retrying the same code
     * would be rejected as reused.
     */
    data class SessionIncomplete(override val message: String) : CodeConsumed

    /**
     * The server answered without judging the code: 400 malformed, 429 rate-limited, any other
     * non-401 4xx, or a 5xx fault. Keep the entry intact for a plain retry.
     */
    data class NotEvaluated(val message: String) : VerifyTwoFactorOutcome

    /**
     * No usable response at all (IO/timeout). Keep the entry; the UI supplies its own
     * connectivity wording.
     */
    data object ConnectionFailure : VerifyTwoFactorOutcome
}
