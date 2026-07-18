package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.AgentAction
import com.garfiec.librechat.core.model.AgentCategory
import com.garfiec.librechat.core.model.AgentTool
import com.garfiec.librechat.core.model.PaginatedAgents
import com.garfiec.librechat.core.model.request.CreateActionRequest
import com.garfiec.librechat.core.model.request.CreateAgentRequest
import com.garfiec.librechat.core.model.request.RevertAgentRequest
import com.garfiec.librechat.core.model.request.UpdateAgentRequest
import com.garfiec.librechat.core.network.api.AgentsApi
import kotlinx.serialization.json.Json

class AgentRepositoryImpl(
    private val agentsApi: AgentsApi,
    activeAccountProvider: ActiveAccountProvider,
) : AgentRepository {

    // Account-keyed in-memory cache: the only isolation tier for agents (no Room/accountId scoping).
    private val cache = AccountKeyedCache<List<Agent>>(activeAccountProvider)

    // --- Agent CRUD ---

    override suspend fun getAgents(category: String?): Result<List<Agent>> {
        return safeApiCall {
            if (category != null) {
                return@safeApiCall agentsApi.getAgents(category).data
            }
            cache.getOrFetch { agentsApi.getAgents(null).data }
        }
    }

    override suspend fun getAgentsPaginated(
        page: Int,
        limit: Int,
        search: String?,
        category: String?,
    ): Result<PaginatedAgents> {
        return safeApiCall {
            val response = agentsApi.getAgentsPaginated(
                pageNumber = page,
                pageSize = limit,
                search = search,
                category = category,
            )
            PaginatedAgents(
                agents = response.data,
                hasMore = response.hasMore,
                total = response.data.size,
            )
        }
    }

    override suspend fun getAgent(id: String): Result<Agent> {
        return safeApiCall {
            cache.peek { agents -> agents.find { it.id == id } }
                ?.let { return@safeApiCall it }
            agentsApi.getAgent(id)
        }
    }

    override suspend fun getAgentForEditing(id: String): Result<Agent> {
        // Always fetch from server via /expanded endpoint -- never use cache.
        // The cache and standard getAgent endpoint only have basic view fields.
        return safeApiCall {
            agentsApi.getAgentForEditing(id)
        }
    }

    override suspend fun createAgent(request: CreateAgentRequest): Result<Agent> {
        return safeApiCall {
            val agent = agentsApi.createAgent(request)
            invalidateCache()
            agent
        }
    }

    override suspend fun updateAgent(id: String, request: UpdateAgentRequest): Result<Agent> {
        return safeApiCall {
            val agent = agentsApi.updateAgent(id, request)
            invalidateCache()
            agent
        }
    }

    override suspend fun deleteAgent(id: String): Result<Unit> {
        return safeApiCall {
            agentsApi.deleteAgent(id)
            invalidateCache()
        }
    }

    override suspend fun duplicateAgent(id: String): Result<Agent> {
        return safeApiCall {
            val agent = agentsApi.duplicateAgent(id)
            invalidateCache()
            agent
        }
    }

    override suspend fun revertAgent(id: String, request: RevertAgentRequest): Result<Agent> {
        return safeApiCall {
            val agent = agentsApi.revertAgent(id, request)
            invalidateCache()
            agent
        }
    }

    override suspend fun getAgentCategories(): Result<List<AgentCategory>> {
        return safeApiCall {
            agentsApi.getAgentCategories()
        }
    }

    // --- Avatar ---

    override suspend fun uploadAgentAvatar(
        id: String,
        imageBytes: ByteArray,
        mimeType: String,
    ): Result<Agent> {
        return safeApiCall {
            val agent = agentsApi.uploadAgentAvatar(id, imageBytes, mimeType)
            invalidateCache()
            agent
        }
    }

    override suspend fun resetAgentAvatar(id: String): Result<Agent> {
        return safeApiCall {
            val agent = agentsApi.resetAgentAvatar(id)
            invalidateCache()
            agent
        }
    }

    // --- Actions ---

    override suspend fun getAgentActions(): Result<List<AgentAction>> {
        return safeApiCall {
            agentsApi.getAgentActions()
        }
    }

    override suspend fun addOrUpdateAction(
        agentId: String,
        request: CreateActionRequest,
    ): Result<Pair<Agent, AgentAction>> {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        return safeApiCall {
            val jsonArray = agentsApi.addOrUpdateAction(agentId, request)
            // Response is [Agent, Action] JsonArray
            if (jsonArray.size < 2) {
                throw IllegalStateException("Expected array of 2 elements for agent action response, got ${jsonArray.size}")
            }
            val agent = json.decodeFromJsonElement(Agent.serializer(), jsonArray[0])
            val action = json.decodeFromJsonElement(AgentAction.serializer(), jsonArray[1])
            invalidateCache()
            Pair(agent, action)
        }
    }

    override suspend fun deleteAction(agentId: String, actionId: String): Result<Unit> {
        return safeApiCall {
            agentsApi.deleteAction(agentId, actionId)
        }
    }

    // --- Tools ---

    override suspend fun getAvailableTools(): Result<List<AgentTool>> {
        return safeApiCall {
            agentsApi.getAvailableTools()
        }
    }

    private suspend fun invalidateCache() = cache.invalidate()
}
