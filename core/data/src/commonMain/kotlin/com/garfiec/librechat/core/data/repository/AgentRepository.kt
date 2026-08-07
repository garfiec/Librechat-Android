package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.AgentAction
import com.garfiec.librechat.core.model.AgentCategory
import com.garfiec.librechat.core.model.AgentTool
import com.garfiec.librechat.core.model.PaginatedAgents
import com.garfiec.librechat.core.model.request.CreateActionRequest
import com.garfiec.librechat.core.model.request.CreateAgentRequest
import com.garfiec.librechat.core.model.request.RevertAgentRequest
import com.garfiec.librechat.core.model.request.UpdateAgentRequest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

interface AgentRepository {

    /**
     * Bumped every time the agent cache is invalidated — i.e. after any agent mutation made through
     * this repository.
     *
     * Consumers that memoize per-agent data must re-read on a change. Without it an agent edited in
     * the agent editor keeps serving its pre-edit values to a chat screen that is still alive, and
     * nothing anywhere reports the staleness.
     */
    val revision: StateFlow<Long>

    // Agent CRUD
    suspend fun getAgents(category: String? = null): Result<List<Agent>>
    suspend fun getAgentsPaginated(
        page: Int,
        limit: Int,
        search: String? = null,
        category: String? = null,
    ): Result<PaginatedAgents>
    suspend fun getAgent(id: String): Result<Agent>

    /**
     * The agent's LLM `provider`, straight from `GET /api/agents/:id` (returned at VIEW permission).
     *
     * Deliberately not expressed as `getAgent(id).provider`: the agent *list* projection omits
     * `provider` entirely (`getListAgentsByAccess` upstream), and [getAgent] serves from that list's
     * cache whenever it's warm — so it answers `null` for an agent whose provider the server knows
     * perfectly well. Callers that route on the provider need the single-agent read, uncached.
     */
    suspend fun getAgentProvider(id: String): Result<String?>

    /**
     * Fetches complete agent data for editing via the /expanded endpoint.
     * Unlike [getAgent] which returns limited view-only fields, this method
     * returns the full agent document including instructions, tools, category,
     * conversation_starters, model_parameters, and all other configuration.
     * Never served from cache -- always fetches fresh from the server.
     */
    suspend fun getAgentForEditing(id: String): Result<Agent>
    suspend fun createAgent(request: CreateAgentRequest): Result<Agent>
    suspend fun updateAgent(id: String, request: UpdateAgentRequest): Result<Agent>
    suspend fun deleteAgent(id: String): Result<Unit>
    suspend fun duplicateAgent(id: String): Result<Agent>
    suspend fun revertAgent(id: String, request: RevertAgentRequest): Result<Agent>

    /**
     * Agent edit history, fetched on demand (v0.8.8 line, #13977).
     *
     * Snapshots stay raw JSON: each is the agent as its schema was at that save point, so
     * narrowing to today's [Agent] shape would quietly drop whatever the editor wants to show
     * from an older revision.
     */
    suspend fun getAgentVersions(id: String): Result<List<JsonElement>>
    suspend fun getAgentCategories(): Result<List<AgentCategory>>

    // Avatar
    suspend fun uploadAgentAvatar(id: String, imageBytes: ByteArray, mimeType: String): Result<Agent>

    /**
     * Reset the agent's avatar to the default. Upstream signals this by PATCHing
     * the agent with explicit `"avatar": null`.
     */
    suspend fun resetAgentAvatar(id: String): Result<Agent>

    // Actions
    suspend fun getAgentActions(): Result<List<AgentAction>>
    suspend fun addOrUpdateAction(agentId: String, request: CreateActionRequest): Result<Pair<Agent, AgentAction>>
    suspend fun deleteAction(agentId: String, actionId: String): Result<Unit>

    // Tools
    suspend fun getAvailableTools(): Result<List<AgentTool>>
}
