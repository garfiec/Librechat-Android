package com.garfiec.librechat.core.common.identity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A change of resolved identity, as the UI layer needs to see it. Cold-start resolution
 * (Warming → first Resolved) and logins from the logged-out state are NOT transitions — the auth flow
 * owns that navigation; these fire only when an *established* identity changes underneath the UI.
 */
sealed interface AccountTransition {
    /** Account-to-account flip (switch / add-completion / remove-active-with-remaining): the back
     *  stack still shows the outgoing account's routes and must be reset. */
    data object Switched : AccountTransition

    /** Account-to-logged-out flip (logout / remove-last). Navigation is owned by the initiating flow
     *  (or the session-expired signal); consumers use this for cache hygiene only. */
    data object Ended : AccountTransition
}

/**
 * Emits an [AccountTransition] for every change of an established resolved identity. [Warming][AccountState.Warming]
 * is ignored entirely (it only precedes the first resolution and never separates two resolved states),
 * so the cold-start seed can never fire a transition.
 */
fun ActiveAccountProvider.accountTransitions(): Flow<AccountTransition> = flow {
    var previous: String? = null
    state.collect { accountState ->
        if (accountState !is AccountState.Resolved) return@collect
        val current = accountState.id?.value
        if (previous != null && current != previous) {
            emit(if (current != null) AccountTransition.Switched else AccountTransition.Ended)
        }
        previous = current
    }
}
