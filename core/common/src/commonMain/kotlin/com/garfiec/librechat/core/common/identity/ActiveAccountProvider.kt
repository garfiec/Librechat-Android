package com.garfiec.librechat.core.common.identity

import com.garfiec.librechat.core.common.identity.AccountState.Resolved
import com.garfiec.librechat.core.common.identity.AccountState.Warming
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * App-singleton holder of the active account's [AccountState]. It is the single source every identity-dependent subsystem reads from — the
 * account-scoped session gates its creation on it, the token store selects its key from it, and the
 * tenant repositories `flatMapLatest` over it.
 *
 * It deliberately exposes only the *current-account* signal; ownership of session transitions (the
 * `cancelAndJoin(old) → publish(new)` mutex) belongs to the `SessionManager`, not here. The provider
 * starts [Warming] and is moved to [Resolved] exactly once the persisted registry has loaded (or a
 * login resolves a new identity).
 */
interface ActiveAccountProvider {

    val state: StateFlow<AccountState>

    /**
     * Suspends until the active account resolves to a non-null id and returns it. Re-evaluable on
     * every call: across a login→logout→login-as-B sequence each call observes the id live at
     * that moment, rather than caching the first cold-start resolution. Callers that must distinguish
     * the logged-out state collect [state] directly instead.
     */
    suspend fun awaitResolvedAccount(): AccountId

    /** Resolve to a live account (login or cold-start restore). */
    fun set(id: AccountId)

    /** Move to the logged-out state — `Resolved(null)`, NOT back to [Warming]. */
    fun clear()
}

/**
 * Plain in-memory [ActiveAccountProvider]. The persisted seed (reading the active id from the
 * registry at cold start and chaining the URL warm-up) is layered on by the platform DI that
 * constructs this; this class only owns the observable transition.
 */
class InMemoryActiveAccountProvider(
    initial: AccountState = Warming,
) : ActiveAccountProvider {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<AccountState> = _state.asStateFlow()

    override suspend fun awaitResolvedAccount(): AccountId =
        (_state.first { it is Resolved && it.id != null } as Resolved).id!!

    override fun set(id: AccountId) {
        _state.value = Resolved(id)
    }

    override fun clear() {
        _state.value = Resolved(null)
    }
}

/** Current resolved account id, or `null` while warming or logged out. Non-suspending snapshot for
 * one-shot reads/writes (`getById`, stamping). Flow-based reads use [flatMapAccountOrEmpty] instead. */
fun ActiveAccountProvider.currentAccountId(): AccountId? = (state.value as? Resolved)?.id

/**
 * Re-subscribes [read] to the active account on every identity transition: a
 * login → logout → login-as-B sequence tears down A's query and starts B's. Emits [empty] while
 * [Warming] or logged out (`Resolved(null)`), so a tenant list never surfaces another account's rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> ActiveAccountProvider.flatMapAccountOrEmpty(empty: T, read: (AccountId) -> Flow<T>): Flow<T> =
    state.flatMapLatest { st -> (st as? Resolved)?.id?.let(read) ?: flowOf(empty) }
