package com.garfiec.librechat.core.data.util

import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.AccountRoster
import com.garfiec.librechat.core.data.repository.UserRepository
import com.garfiec.librechat.core.data.repository.displayLabel
import com.garfiec.librechat.core.network.client.ServerUrlProvider

/**
 * Refreshes the active account's roster display label + avatar from the live user profile when a
 * session starts. Fresh logins already write these (establish / add-completion); this covers the
 * entries those paths never touch: a migrated pre-roster account (seeded with the server-host
 * fallback because no `User` payload exists at migration time) and server-side profile changes for
 * a stay-logged-in user. Best-effort — the runner swallows failures, and the switcher UI falls
 * back to whatever label the roster already holds.
 */
class AccountLabelBackfillSessionTask(
    private val userRepository: UserRepository,
    private val accountRoster: AccountRoster,
    private val activeAccountProvider: ActiveAccountProvider,
    private val serverUrlProvider: ServerUrlProvider,
) : SessionTask {
    override suspend fun run() {
        val active = activeAccountProvider.currentAccountId() ?: return
        val user = (userRepository.getUser() as? Result.Success)?.data ?: return
        accountRoster.updateDisplay(
            accountId = active.value,
            displayLabel = user.displayLabel(serverUrlProvider.awaitBaseUrl()),
            avatarUrl = user.avatar?.takeIf { it.isNotBlank() },
        )
    }
}
