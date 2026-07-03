package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
import com.garfiec.librechat.core.data.datastore.AccountRoster

/**
 * Resolves the account id to stamp on a read-through cache write, applying the origin-capture
 * provenance rule (multi-account, issue #179).
 *
 * A streamed finalize (`cacheMessages` / `saveConversation` / `generateTitle` / `refreshConversation`)
 * lands minutes after the send, potentially after the user switched accounts — so reading the *live*
 * active account at land time would mis-attribute A's reply to B. The fix is origin-capture: the
 * account is captured at the operation's entry (send time, in the chat streaming delegate) and threaded
 * down as [origin]. This helper turns that captured origin into the id to stamp:
 *
 * - **No [origin]** (foreground read-through / CRUD, where entry *is* land time): stamp the live active
 *   account; skip (null) when unresolved, exactly as before.
 * - **[origin] present**: stamp it **iff it is still a known roster account**. If the account was
 *   removed since capture, skip — a blanket stamp would resurrect its purged rows, while a blanket skip
 *   (the draft template) would drop a valid finalize for an account that is merely non-active after a
 *   switch. "Still known" threads that needle.
 */
internal suspend fun resolveWriteAccountId(
    origin: AccountId?,
    activeAccountProvider: ActiveAccountProvider,
    roster: AccountRoster,
): String? =
    if (origin == null) {
        activeAccountProvider.currentAccountId()?.value
    } else {
        origin.value.takeIf { roster.contains(it) }
    }

/**
 * True when an origin-captured finalize's *network* leg may proceed. [resolveWriteAccountId] scopes
 * the DB stamp, but the transport still snapshots the LIVE identity at request-build time — a
 * finalize landing after a switch would send the origin account's conversation id to the NEW
 * account's server under its bearer (cross-server id disclosure plus a guaranteed 404). Entry-time
 * calls (null origin) always proceed; origin-captured ones only while that origin is still the
 * live account.
 */
internal suspend fun originTransportAllowed(
    origin: AccountId?,
    activeAccountProvider: ActiveAccountProvider,
): Boolean = origin == null || origin.value == activeAccountProvider.currentAccountId()?.value
