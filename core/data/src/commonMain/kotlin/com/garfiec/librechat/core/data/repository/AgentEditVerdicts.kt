package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.currentAccountId
import com.garfiec.librechat.core.model.Agent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The per-agent EDIT verdicts (`isEditable`) that only the agent LIST routes stamp.
 *
 * Upstream sets the field in one place — `getListAgents`, from an EDIT-scoped accessible-id set —
 * so `GET /api/agents/:id` and `/expanded` never carry it. Without somewhere to keep it, the answer
 * the server already gave is thrown away at the list row and the detail screen has to fall back to
 * its own permission probe, which is what this exists to let it narrow.
 *
 * Account-keyed for the same reason [AccountKeyedCache] is: agents have no Room/accountId scoping,
 * and serving one account's verdict to another would put an Edit button on an agent that account
 * cannot touch. The key is read under the lock at access time, never captured beforehand.
 *
 * Only explicit answers are stored. A row from a server that predates the field records nothing, so
 * a later read is indistinguishable from "never listed" — both mean unknown, and unknown must leave
 * the probe in charge rather than grant or deny.
 */
internal class AgentEditVerdicts(private val activeAccountProvider: ActiveAccountProvider) {

    private val mutex = Mutex()
    private val verdicts = mutableMapOf<String, Boolean>()
    private var recordedFor: String? = null

    /** Keeps the explicit verdicts carried by a batch of list rows. */
    suspend fun record(agents: List<Agent>) = mutex.withLock {
        val account = activeAccountProvider.currentAccountId()?.value
        if (recordedFor != account) {
            verdicts.clear()
            recordedFor = account
        }
        agents.forEach { agent ->
            val verdict = agent.isEditable ?: return@forEach
            verdicts[agent.id] = verdict
        }
    }

    /** The recorded verdict for [id], or null when this account has no list answer for it. */
    suspend fun get(id: String): Boolean? = mutex.withLock {
        val account = activeAccountProvider.currentAccountId()?.value
        verdicts[id].takeIf { recordedFor == account }
    }

    suspend fun clear() = mutex.withLock {
        verdicts.clear()
        recordedFor = null
    }
}
