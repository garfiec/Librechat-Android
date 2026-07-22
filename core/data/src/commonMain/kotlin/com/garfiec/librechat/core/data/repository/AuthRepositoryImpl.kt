package com.garfiec.librechat.core.data.repository

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.SessionManager
import com.garfiec.librechat.core.common.identity.currentAccountId
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.data.datastore.AccountRegistry
import com.garfiec.librechat.core.data.util.SessionTaskRunner
import com.garfiec.librechat.core.model.LoginOutcome
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.model.response.TwoFactorSetupResponse
import com.garfiec.librechat.core.network.api.AuthApi
import com.garfiec.librechat.core.network.api.UserApi
import com.garfiec.librechat.core.network.api.dto.LoginResult
import com.garfiec.librechat.core.network.client.PendingRequestIdentity
import com.garfiec.librechat.core.network.client.SwitchGate
import com.garfiec.librechat.core.network.client.TokenManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class AuthRepositoryImpl(
    private val authApi: AuthApi,
    private val userApi: UserApi,
    private val tokenManager: TokenManager,
    private val sessionCacheCleaner: SessionCacheCleaner,
    private val sessionTaskRunner: SessionTaskRunner,
    private val accountSessionEstablisher: AccountSessionEstablisher,
    private val accountRegistry: AccountRegistry,
    private val activeAccountProvider: ActiveAccountProvider,
    private val sessionManager: SessionManager,
    private val accountSwitcher: AccountSwitcher,
    private val switchGate: SwitchGate,
) : AuthRepository {

    /**
     * Fires session tasks when [this] represents an authenticated success. Callers pass
     * [isAuthenticated] to discriminate: for OAuth/2FA any `Result.Success` is a real
     * sign-in, but `login()`'s `LoginOutcome.TwoFactorRequired` is a pending state (user
     * isn't authenticated yet) so tasks defer to `verifyTwoFactor()`.
     */
    private fun <T> Result<T>.fireSessionTasksIfLoggedIn(
        previousAccountId: AccountId?,
        isAuthenticated: (T) -> Boolean = { true },
    ): Result<T> =
        also {
            if (it is Result.Success && isAuthenticated(it.data)) {
                // The NavHost's accountTransitions (Switched) collector already runs the session tasks
                // for any account→different-account flip — an add-completion (A→B) AND a soft-expiry
                // re-auth as a different account (A→B). Firing here too would double every post-login
                // fetch and race two writers onto the same acct: keys. Fire here only when this sign-in
                // was NOT such a transition and the collector therefore won't cover it: a fresh login
                // from logged-out (null→B) or a re-auth as the same account (A→A). [previousAccountId]
                // is captured before establishSession; the current id reflects the post-establish flip.
                // The one window where a transition could slip past the collector — the NavHost VM being
                // re-created (process death) exactly across the flip — is backstopped by the cold-start
                // path (NavHostViewModel.init calls sessionTaskRunner.runAll for the resolved account),
                // so no resolved login is ever left without its session tasks.
                val current = activeAccountProvider.currentAccountId()
                val handledByCollector =
                    previousAccountId != null && current != null && previousAccountId != current
                if (!handledByCollector) sessionTaskRunner.runAll()
            }
        }

    /**
     * Runs [block] under the pending add-account identity when an add flow is in progress, so its
     * HTTP calls target the server being added (with the staged bearer) instead of the active
     * account's server. Passes [pending] explicitly so one capture drives both the request routing
     * and the completion dispatch — the two can't disagree mid-call.
     */
    private suspend fun <T> withAuthIdentity(pending: PendingAddSession?, block: suspend () -> T): T =
        pending.withRequestIdentity(block)

    /**
     * Turns the authenticated [user] into the active account: through the add-completion (atomic
     * URL+token+roster+identity flip to the new account) when [pending] is an add flow, else through
     * the normal establish path.
     */
    private suspend fun establishSession(pending: PendingAddSession?, user: User) {
        if (pending != null) accountSwitcher.completeAdd(user) else accountSessionEstablisher.establish(user)
    }

    /**
     * Fetches the just-authenticated user's profile carrying the *staged* bearer. After
     * [TokenManager.setTokens] the fresh pair sits under the bare staging keys with no active
     * binding — but a re-login after a hard session expiry still has the previous account
     * Resolved, so the switch barrier would key this request's bearer to that account's (now
     * empty) slot and deliberately never fall back to staging ([SwitchGate.captureSnapshot]),
     * turning every OAuth re-login into a 401 loop. Route the fetch under an explicit
     * staged-bearer identity instead. In add mode the surrounding pending identity already
     * carries the staged bearer.
     */
    private suspend fun fetchAuthenticatedUser(pending: PendingAddSession?): User {
        if (pending != null) return userApi.getUser()
        val baseUrl = switchGate.captureSnapshot().baseUrl
        return withContext(
            PendingRequestIdentity(baseUrl = baseUrl, bearer = { tokenManager.getStagedAccessToken() }),
        ) { userApi.getUser() }
    }

    private suspend fun stageAuthenticatedSession(result: LoginResult): User {
        val user = result.response.user ?: throw incompleteAuthResponse("user")
        val token = result.response.token ?: throw incompleteAuthResponse("token")
        if (result.refreshToken == null) {
            Logger.w { "Auth response carried no refresh token; this session cannot be renewed" }
        }
        tokenManager.setTokens(accessToken = token, refreshToken = result.refreshToken ?: "")
        return user
    }

    private fun incompleteAuthResponse(missingField: String): IllegalStateException {
        Logger.w { "Auth response was missing '$missingField'" }
        return IllegalStateException("The server's sign-in response was incomplete. Please try again.")
    }

    override suspend fun login(email: String, password: String): Result<LoginOutcome> {
        val pending = accountSwitcher.pendingAdd
        val previousAccountId = activeAccountProvider.currentAccountId()
        return safeApiCall {
            withAuthIdentity(pending) {
                val result = authApi.login(email, password)
                if (result.response.twoFAPending) {
                    LoginOutcome.TwoFactorRequired(
                        result.response.tempToken
                            ?: throw IllegalStateException("Server indicated 2FA required but tempToken was null"),
                    )
                } else {
                    val user = stageAuthenticatedSession(result)
                    // Establish identity (+ run the legacy claim) before session tasks fire, so their
                    // tenant writes are stamped to this account and reads filter to it.
                    establishSession(pending, user)
                    LoginOutcome.Success(user)
                }
            }
        }.fireSessionTasksIfLoggedIn(previousAccountId) { outcome ->
            outcome is LoginOutcome.Success
        }
    }

    override suspend fun loginWithOAuthToken(refreshToken: String): Result<User> {
        val pending = accountSwitcher.pendingAdd
        val previousAccountId = activeAccountProvider.currentAccountId()
        return safeApiCall {
            withAuthIdentity(pending) {
                val result = authApi.refresh(refreshToken)
                // If the backend rotated the refresh token, use the new one;
                // otherwise keep the original OAuth-provided token.
                val effectiveRefreshToken = result.newRefreshToken ?: refreshToken
                tokenManager.setTokens(
                    accessToken = result.response.token,
                    refreshToken = effectiveRefreshToken,
                )
                // Fetch the user profile, then establish identity before session tasks fire.
                val user = fetchAuthenticatedUser(pending)
                establishSession(pending, user)
                user
            }
        }.fireSessionTasksIfLoggedIn(previousAccountId)
    }

    override suspend fun verifyTwoFactor(tempToken: String, code: String, isBackupCode: Boolean): Result<User> {
        val pending = accountSwitcher.pendingAdd
        val previousAccountId = activeAccountProvider.currentAccountId()
        return safeApiCall {
            withAuthIdentity(pending) {
                val result = authApi.verifyTempToken(
                    tempToken = tempToken,
                    totpCode = code.takeUnless { isBackupCode },
                    backupCode = code.takeIf { isBackupCode },
                )
                val user = stageAuthenticatedSession(result)
                establishSession(pending, user)
                user
            }
        }.fireSessionTasksIfLoggedIn(previousAccountId)
    }

    override suspend fun register(
        name: String,
        email: String,
        username: String,
        password: String,
    ): Result<Unit> {
        return safeApiCall {
            withAuthIdentity(accountSwitcher.pendingAdd) {
                authApi.register(name, email, username, password, password)
            }
        }
    }

    override suspend fun logout(): Result<Unit> {
        return safeApiCall {
            // Let the cold-start seed resolve identity first: a logout during the warming window
            // would otherwise read a null account and skip the scoped Room purge below (the leak).
            accountRegistry.awaitReady()
            // One atomic snapshot drives both the server-side revocation and the local teardown
            // target: the POST otherwise captures its identity at request-build time, so a switch
            // landing between entry and the request would revoke the server session of the account
            // the user just switched TO (and never revoke this one). Pinning via a pending-style
            // identity also skips the 401-refresh — revocation is best-effort; the teardown below
            // is what must not miss.
            val snapshot = switchGate.captureSnapshot()
            val account = snapshot.accountId?.let { AccountId(it) }
            try {
                withContext(
                    PendingRequestIdentity(baseUrl = snapshot.baseUrl, bearer = { snapshot.bearer }),
                ) { authApi.logout() }
            } finally {
                // Teardown must finish even if this coroutine is cancelled mid-way (e.g. the Activity
                // finishes during the WebView wipe). Without NonCancellable a cancellation could abort
                // after the identity flip but before the cookie/WebView wipe, leaving the server's OAuth
                // refreshToken cookie alive for the next resume's checkOAuthResult() to re-consume —
                // silently signing the just-logged-out user back in. The switch/remove paths already
                // run their teardown under NonCancellable; logout must too.
                withContext(NonCancellable) {
                    // End the account session scope only if the captured account is still the live one.
                    // A switch/add landing during the slow revocation POST flips the live identity;
                    // remove() below then reaps only the captured account without touching the
                    // switched-to session, so we must not end that session. NOTE: tenant writes are not
                    // yet bound to Session.scope (the structural SessionWriter is deferred), so this does
                    // not by itself fence a debounced/in-flight write against the purge; it is mitigated
                    // per-path (draft debounce re-checks identity; cache writes capture+guard the account
                    // at request time).
                    if (account == null || activeAccountProvider.currentAccountId() == account) {
                        sessionManager.endCurrentSession()
                    }
                    if (account != null) {
                        // One teardown owner: remove() promotes the most-recently-used survivor (the user
                        // stays signed in) or, when this was the last account, tears down to logged-out
                        // and emits session-expired to route to auth — the same path the account-switcher's
                        // remove-account uses. It serializes the identity flip under the SwitchGate itself
                        // and runs the slow cache/WebView wipes after the gate reopens. The switched-away
                        // case (a switch landed mid-POST, so the captured account is no longer active) is
                        // handled inside remove(): it reaps just that account without touching the live one.
                        accountSwitcher.remove(account.value)
                    } else {
                        // Unresolved identity (should not happen after awaitReady) — no account to reap;
                        // clear the account-blind file caches so nothing is left behind.
                        sessionCacheCleaner.clearFileCaches()
                    }
                }
            }
        }
    }

    override suspend fun isLoggedIn(): Boolean {
        return tokenManager.getAccessToken() != null
    }

    override suspend fun restoreAccountIfNeeded(): Boolean {
        if (!isLoggedIn()) return true
        // Let the registry's cold-start seed finish, then act only if it found no persisted account.
        accountRegistry.awaitReady()
        val state = activeAccountProvider.state.value
        if (state is AccountState.Resolved && state.id != null) return true
        // Logged in but unaccounted → fresh upgrade. Derive identity from the current user. Wrap the
        // live getUser() so a transient/offline failure returns false (retry-worthy) instead of throwing
        // and leaving the provider stuck at Resolved(null) — which would render every tenant read empty
        // for an otherwise logged-in user until a manual relaunch while online.
        val result = safeApiCall {
            val user = userApi.getUser()
            accountSessionEstablisher.establish(user)
        }
        return result is Result.Success
    }

    override suspend fun enableTwoFactor(token: String?, backupCode: String?): Result<TwoFactorSetupResponse> {
        return safeApiCall {
            authApi.enableTwoFactor(token = token, backupCode = backupCode)
        }
    }

    override suspend fun confirmTwoFactor(code: String): Result<TwoFactorSetupResponse> {
        return safeApiCall {
            authApi.confirmTwoFactor(code)
        }
    }

    override suspend fun disableTwoFactor(code: String): Result<Unit> {
        return safeApiCall {
            authApi.disableTwoFactor(code)
        }
    }

    override suspend fun regenerateBackupCodes(token: String?, backupCode: String?): Result<TwoFactorSetupResponse> {
        return safeApiCall {
            authApi.regenerateBackupCodes(token = token, backupCode = backupCode)
        }
    }

    override suspend fun requestPasswordReset(email: String): Result<Unit> {
        return safeApiCall {
            // Reachable from an add-mode login screen too — route to the pending server when set.
            withAuthIdentity(accountSwitcher.pendingAdd) {
                authApi.requestPasswordReset(email)
            }
        }
    }

    override suspend fun resetPassword(
        userId: String,
        token: String,
        password: String,
        confirmPassword: String,
    ): Result<Unit> {
        return safeApiCall {
            authApi.resetPassword(userId, token, password, confirmPassword)
        }
    }
}
