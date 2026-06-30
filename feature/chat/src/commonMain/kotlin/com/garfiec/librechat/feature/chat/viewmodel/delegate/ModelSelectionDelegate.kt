package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.McpRepository
import com.garfiec.librechat.core.data.util.PermissionGate
import com.garfiec.librechat.core.logging.Diag
import com.garfiec.librechat.core.model.Agent
import com.garfiec.librechat.core.model.Conversation
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.mcp.McpServer
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.feature.chat.model.McpServerDisplayData
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ModelSelectionDelegate(
    private val stateHandle: ChatStateHandle,
    private val configRepository: ConfigRepository,
    private val agentRepository: AgentRepository,
    private val mcpRepository: McpRepository,
    private val settingsDataStore: SettingsDataStore,
    private val permissionGate: PermissionGate,
    initialAgentId: String? = null,
) {

    /**
     * Tier-0 explicit agent selection passed from the start-chat navigation (e.g.
     * "Start Chat" on an agent detail/card, or post-create). One-shot: applied on the
     * first [applySeed] pass and cleared, so it wins over last-used/first-agent/first-
     * model exactly once and then normal seeding governs. Authoritative — set before
     * any validation, and seeding only runs on a NEW chat (so the existing-conversation
     * clobber-guard in [refilterModels] never sees it).
     */
    private var pendingAgentOverride: String? = initialAgentId?.takeIf { it.isNotBlank() }

    /**
     * Cached last-used endpoint/model from DataStore, kept in sync by
     * [seedInitialSelection]. Used by [refilterModels]' existing-conversation
     * fallback. Null until the first DataStore emission.
     */
    var cachedLastUsedEndpoint: String? = null
    var cachedLastUsedModel: String? = null

    /**
     * True once a conversation-model LOAD has been attempted (success, 404, or new-chat
     * short-circuit). Used to prevent [refilterModels] from overwriting the conversation
     * model with a fallback before loadConversationModel has had a chance to set it.
     *
     * NOTE: "attempted", not "succeeded" — a 404 (the created-before-save race) still flips
     * this. To tell "we actually have the conversation's real model" use
     * [conversationModelResolved].
     */
    var conversationModelLoaded = false

    /**
     * True once the conversation's authoritative (endpoint, model) has actually been applied
     * — either from the in-process new-chat handoff or from a successful conversation read.
     * Distinct from [conversationModelLoaded], which only records that a load was attempted
     * (and is set even on a 404). [ChatViewModel.handleFinal] re-derives the selection from
     * the Final event only while this is still false, so a racy 404 can't strand a fallback
     * guess on screen.
     */
    var conversationModelResolved = false

    /**
     * Applies an authoritative conversation (endpoint, model) as the active selection and
     * marks the conversation model both loaded and resolved. The single write point for a
     * resolved existing/just-created conversation's selection — used by the new-chat handoff
     * and by [ChatViewModel]'s conversation reads — so the flags can't drift from the value.
     */
    fun applyResolvedConversationModel(endpoint: String, model: String?) {
        applySelection(endpoint, model, reason = "conversationResolved")
        conversationModelLoaded = true
        conversationModelResolved = true
    }

    /**
     * Resolves a loaded [conversation]'s authoritative (endpoint, model) and applies it as
     * the active selection via [applyResolvedConversationModel]. Agents conversations carry
     * the agent in `agentId`, so prefer that over `model` for the AGENTS endpoint. Returns
     * true when a concrete selection was applied (i.e. the conversation model is now
     * resolved), false when the conversation lacked enough info.
     */
    fun applyConversationModel(conversation: Conversation): Boolean {
        val endpoint = conversation.endpoint
        val isAgentConversation = endpoint == EndpointConstants.AGENTS
        val resolvedModel = if (isAgentConversation) {
            conversation.agentId ?: conversation.model
        } else {
            conversation.model
        }
        if (endpoint != null && resolvedModel != null) {
            applyResolvedConversationModel(endpoint, resolvedModel)
            return true
        }
        return false
    }

    /**
     * True once [loadAgents] has finished on ANY path (success, error, or
     * permission-denied). [seedInitialSelection] uses this to tell "agents not
     * loaded yet" (wait) from "agents loaded and genuinely empty" (fall through).
     *
     * Modeled as a flow (not a plain `var`) so it is an arm of the seeder's
     * `combine`: the error / permission-denied paths flip it without publishing a
     * new agent list, and a bare var would leave those transitions invisible to
     * the seeder — it would stay parked on the agents tier forever (no model on
     * the landing). `internal` for the delegate's unit tests.
     */
    internal val agentsLoaded = MutableStateFlow(false)

    /**
     * The last-used (endpoint, model) pair this delegate last applied as the
     * active selection. Lets [seedInitialSelection] re-apply a *changed* last-used
     * even over an already-valid selection (the retained-landing resync) without
     * re-applying an unchanged value on every unrelated emission.
     */
    private var lastAppliedLastUsed: Pair<String, String>? = null

    /**
     * True between a Tier-0 explicit-agent application and the moment the populated
     * agents list arrives. Distinguishes the explicitly-chosen-agent case — an agent that
     * must survive the transient `(agentsLoaded=true, agents=[])` window the agents flow
     * emits before the real list (see [loadAgents]) — from a genuinely zero-agent account
     * whose stale agents last-used must NOT strand the landing model-less. Only when this
     * flag is set does an empty-but-loaded agents list HOLD the current selection; otherwise
     * an empty agents list falls through to a config model. Cleared once the agent is
     * confirmed present in a non-empty list.
     */
    private var holdAgentForPopulate = false

    /**
     * Re-filters availableModels into UI state and, for EXISTING conversations,
     * corrects a selection that the latest models/configs invalidated.
     *
     * New-chat seeding is owned entirely by [seedInitialSelection] — this method
     * deliberately does not seed/fallback for new chats, so the two never race.
     */
    fun refilterModels(isNewConversation: Boolean) {
        val filtered = filterModelsByEndpoint(
            configRepository.availableModels.value,
            configRepository.endpointConfigs.value,
        )
        stateHandle.update { copy(availableModels = filtered) }

        // Don't validate against an empty models list — models haven't
        // loaded yet. When they arrive this method will be called again.
        if (filtered.isEmpty()) return

        // New chats: the seeder is the single authority for the selection.
        // It re-resolves on every input change (availableModels/endpointConfigs
        // are arms of its combine), so there is nothing to correct here.
        if (isNewConversation) return

        // For existing conversations, don't apply fallbacks until the
        // conversation model has been loaded.
        if (!conversationModelLoaded) return

        // Validate current selection. The previous version exempted the
        // agents endpoint from the `currentModel in modelsForEndpoint` check,
        // which let a stale selectedModel (a real model name carried over
        // from a non-agent endpoint) be re-used as an agent_id by the
        // sendMessage path — the server then rejects the chat because no
        // such agent exists. Treat agents like any other endpoint: validate
        // that selectedModel is actually one of the loaded agent IDs.
        // When modelsForEndpoint is absent (still loading), skip the check
        // rather than clobber the selection.
        val currentEndpoint = stateHandle.state.selectedEndpoint
        val currentModel = stateHandle.state.selectedModel
        val modelsForEndpoint = filtered[currentEndpoint]
        val selectionValid = currentModel != null &&
            (modelsForEndpoint == null || currentModel in modelsForEndpoint)

        if (selectionValid) return

        // --- Corrective fallback (existing conversations only) ---
        // The loaded conversation's model vanished server-side. Prefer the
        // last-used model (same agents-endpoint guard as the validation above —
        // a stale lastUsedModel that isn't an agent_id must not be restored as
        // the agents-endpoint selection), then fall back to the first model.
        val lastEndpoint = cachedLastUsedEndpoint
        val lastModel = cachedLastUsedModel
        if (lastEndpoint != null && lastModel != null) {
            val lastUsedRestorable = if (lastEndpoint == EndpointConstants.AGENTS) {
                // Agents never appear in `filtered` (/api/models has no "agents" key), so the
                // old `filtered[lastEndpoint]` check always failed and a valid agents last-used
                // was silently skipped to a config model. Validate the agent id against the
                // authoritative agents list instead (mirroring selectability()). Hold — don't
                // fall through to a config-model guess — until that list has loaded; loadAgents
                // re-runs refilterModels on success to un-stick this.
                when {
                    !agentsLoaded.value -> return
                    stateHandle.state.agents.any { it.id == lastModel } -> true
                    else -> false
                }
            } else {
                val lastModelsForEndpoint = filtered[lastEndpoint]
                lastModelsForEndpoint != null && lastModel in lastModelsForEndpoint
            }
            if (lastUsedRestorable) {
                Diag.w(
                    tag = "ModelSel",
                    attrs = mapOf(
                        "branch" to "lastUsed",
                        "from" to (currentModel ?: "null"),
                        "endpoint" to lastEndpoint,
                    ),
                ) { "refilterModels corrective fallback → last-used" }
                stateHandle.update {
                    copy(
                        selectedEndpoint = lastEndpoint,
                        selectedModel = lastModel,
                    )
                }
                return
            }
        }

        val firstEndpoint = filtered.entries.firstOrNull()
        if (firstEndpoint != null) {
            Diag.w(
                tag = "ModelSel",
                attrs = mapOf(
                    "branch" to "firstModel",
                    "from" to (currentModel ?: "null"),
                    "endpoint" to firstEndpoint.key,
                ),
            ) { "refilterModels corrective fallback → first model" }
            stateHandle.update {
                copy(
                    selectedEndpoint = firstEndpoint.key,
                    selectedModel = firstEndpoint.value.firstOrNull(),
                )
            }
        }
    }

    /**
     * Single authority for a NEW chat's initial model selection.
     *
     * Continuous (not one-shot): the NewChat landing ViewModel is retained in the
     * back stack, so when last-used changes (e.g. the user picks a different model
     * inside a conversation) this re-syncs the blank landing to it. Gated on
     * `conversationId == null` so a started conversation's model is never overridden.
     *
     * Deterministic: every input (last-used, config models, endpoint configs, agent
     * list) is an arm of one `combine`, so each change re-resolves the precedence
     * atomically — replacing the three coroutines that used to race to seed the
     * selection. Precedence, with WAIT-on-unresolved so a lower tier never wins
     * while a higher-precedence input is still loading:
     *   1. last-used (endpoint + model), if present and valid
     *   2. first agent, when the active endpoint is agents
     *   3. first available config model
     */
    fun seedInitialSelection(isNewConversation: Boolean) {
        stateHandle.scope.launch {
            combine(
                settingsDataStore.lastUsedEndpoint,
                settingsDataStore.lastUsedModel,
                configRepository.availableModels,
                configRepository.endpointConfigs,
                agentsInput(),
            ) { lastEndpoint, lastModel, rawModels, endpointCfgs, agents ->
                SeedInputs(
                    lastEndpoint = lastEndpoint,
                    lastModel = lastModel,
                    rawModels = rawModels,
                    endpointConfigs = endpointCfgs,
                    agents = agents.agents,
                    agentsLoaded = agents.loaded,
                    conversationId = agents.conversationId,
                )
            }.collect { input ->
                // Keep the cached last-used fresh for refilterModels' existing-
                // conversation corrective fallback. This runs for every conversation
                // (new and existing) — the old eager one-shot read in ChatViewModel
                // populated the cache for both, and dropping it would leave that
                // fallback reading nulls for existing conversations.
                cachedLastUsedEndpoint = input.lastEndpoint
                cachedLastUsedModel = input.lastModel
                // Seeding applies only to the blank new-chat landing. Never override
                // an existing/started conversation's model (loadConversationModel
                // owns those).
                if (!isNewConversation || input.conversationId != null) return@collect
                applySeed(input)
            }
        }
    }

    /**
     * The agents arm of the seeder's [combine]: the (agents, conversationId) pair
     * from state plus the [agentsLoaded] flag, so a load-completion with an
     * unchanged (empty) agent list — error or permission-denied — still re-triggers
     * the seeder and lets it fall through past the agents tier.
     */
    private fun agentsInput(): Flow<AgentsInput> = combine(
        stateHandle.stateFlow.map { it.agents to it.conversationId }.distinctUntilChanged(),
        agentsLoaded,
    ) { agentsAndConvId, loaded ->
        AgentsInput(
            agents = agentsAndConvId.first,
            conversationId = agentsAndConvId.second,
            loaded = loaded,
        )
    }

    private fun applySeed(input: SeedInputs) {
        val filtered = filterModelsByEndpoint(input.rawModels, input.endpointConfigs)
        val state = stateHandle.state

        val lastUsed = if (input.lastEndpoint != null && input.lastModel != null) {
            input.lastEndpoint to input.lastModel
        } else {
            null
        }

        // Tier 0: an explicit agent passed from the start-chat navigation wins over
        // everything (last-used / first-agent / first-model). Authoritative + one-shot:
        // apply the agents endpoint + this agent id directly (the agent was just
        // selected/created, so trust the id even if the agents list hasn't loaded it
        // yet — the chat sends the right agent_id and the label resolves once it does).
        // Cleared after applying so subsequent seeder emissions don't re-pin it.
        // CRITICAL: also pin [lastAppliedLastUsed] to the CURRENT last-used so the
        // last-used re-sync below (`lastUsed != lastAppliedLastUsed`) does NOT fire on
        // the next emission (e.g. when the agents list finishes loading) and clobber
        // the agent back to a non-agent last-used model. A genuinely NEW last-used
        // picked later still differs from this pinned value, so the re-sync correctly
        // follows an explicit later choice.
        pendingAgentOverride?.let { agentId ->
            pendingAgentOverride = null
            lastAppliedLastUsed = lastUsed
            // Hold this explicit agent across the transient empty-agents window until the
            // populated list arrives (see [holdAgentForPopulate]); without it the next
            // (agentsLoaded=true, agents=[]) emission would score the agent INVALID/PENDING
            // and clobber it to a config model.
            holdAgentForPopulate = true
            applySelection(EndpointConstants.AGENTS, agentId, reason = "tier0-agentOverride")
            return
        }
        val lastUsedSelectability = lastUsed?.let {
            selectability(it.first, it.second, filtered, input.endpointConfigs, input.agents, input.agentsLoaded)
        }
        fun applyLastUsed(): Boolean {
            if (lastUsed != null && lastUsedSelectability == Selectability.VALID) {
                applySelection(lastUsed.first, lastUsed.second, reason = "tier1-lastUsed")
                lastAppliedLastUsed = lastUsed
                return true
            }
            return false
        }

        // Re-sync: when last-used CHANGES to a new valid value, re-apply it even
        // over a currently-valid selection, so the retained landing follows a model
        // picked elsewhere. Also covers the very first valid last-used emission.
        if (lastUsed != lastAppliedLastUsed && applyLastUsed()) return

        // Clear the explicit-agent hold once the populated agents list confirms the held agent: the
        // transient empty-agents window is over and the selection is now genuinely VALID,
        // so a later last-used change should be free to re-sync normally.
        if (holdAgentForPopulate &&
            input.agentsLoaded &&
            input.agents.any { it.id == state.selectedModel }
        ) {
            holdAgentForPopulate = false
        }

        val currentSelectability = selectability(
            state.selectedEndpoint,
            state.selectedModel,
            filtered,
            input.endpointConfigs,
            input.agents,
            input.agentsLoaded,
        )
        when (currentSelectability) {
            // Already usable — leave it (the re-sync above handled last-used changes).
            Selectability.VALID -> return
            // The input needed to judge the current selection hasn't arrived yet.
            Selectability.PENDING -> {
                // An explicitly-chosen agent (Tier-0) in the transient empty-agents
                // window. An empty list can neither confirm nor refute the agent id, so HOLD
                // it and wait for the populated list (which re-triggers this seeder) instead
                // of downgrading to last-used / a config model. Gated on [holdAgentForPopulate]
                // so a genuinely zero-agent account (no explicit agent intent) does NOT hold —
                // it falls through below to a config model rather than stranding the landing.
                if (holdAgentForPopulate &&
                    state.selectedEndpoint == EndpointConstants.AGENTS &&
                    input.agentsLoaded &&
                    input.agents.isEmpty()
                ) {
                    return
                }
                // Otherwise: don't reseed through the lower tiers (that would clobber a
                // legitimately-loading choice), but DO let a known-good last-used take over
                // rather than waiting on an input that may never resolve. If last-used can't
                // apply (also empty/pending), fall through to the tier ladder so the landing
                // still resolves to a model rather than sitting empty forever.
                if (applyLastUsed()) return
                if (state.selectedEndpoint != EndpointConstants.AGENTS) return
            }
            // Definitively unusable — fall through to tier resolution.
            Selectability.INVALID -> {}
        }

        // Tier 1: last-used.
        if (lastUsed != null) {
            when (lastUsedSelectability) {
                Selectability.VALID -> {
                    applyLastUsed()
                    return
                }
                // WAIT: last-used's endpoint/agents not loaded yet — don't fall
                // through to a lower tier and lose to it. EXCEPTION: an agents last-used
                // whose list is loaded-but-EMPTY is PENDING (the empty-list hold rule), but
                // here there is no explicit agent to hold (Tier-0 didn't fire / hold cleared),
                // so it's a genuinely zero-agent account — fall through to Tier-2/3 rather
                // than waiting forever and stranding the landing model-less.
                Selectability.PENDING -> {
                    val agentsLoadedEmpty = lastUsed.first == EndpointConstants.AGENTS &&
                        input.agentsLoaded &&
                        input.agents.isEmpty()
                    if (!agentsLoadedEmpty) return
                }
                // Stale (removed server-side) or absent — fall through.
                Selectability.INVALID, null -> {}
            }
        }

        // Tier 2: first agent, when the active endpoint is agents.
        if (state.selectedEndpoint == EndpointConstants.AGENTS) {
            if (!input.agentsLoaded) return // WAIT for the agent list
            val firstAgent = input.agents.firstOrNull()
            if (firstAgent != null) {
                applySelection(EndpointConstants.AGENTS, firstAgent.id, reason = "tier2-firstAgent")
                return
            }
            // Agents loaded and genuinely empty — fall through.
        }

        // Tier 3: first available config model.
        if (filtered.isEmpty()) return // WAIT for models
        val firstEntry = filtered.entries.firstOrNull() ?: return
        applySelection(firstEntry.key, firstEntry.value.firstOrNull(), reason = "tier3-firstModel")
    }

    private fun applySelection(endpoint: String, model: String?, reason: String) {
        val previous = stateHandle.state
        val changed = previous.selectedEndpoint != endpoint || previous.selectedModel != model
        stateHandle.update {
            copy(selectedEndpoint = endpoint, selectedModel = model)
        }
        if (changed) {
            Diag.d(
                tag = "ModelSel",
                attrs = mapOf(
                    "reason" to reason,
                    "endpoint" to endpoint,
                    "model" to (model ?: "null"),
                ),
            ) { "applySelection" }
        }
    }

    /**
     * Three-state validity for a candidate (endpoint, model):
     * - VALID: usable right now
     * - PENDING: the input needed to judge it hasn't loaded yet (agents list, or
     *   this endpoint's model list) — callers should WAIT, not fall through
     * - INVALID: definitively not usable (null, or absent from a loaded list)
     *
     * Agents are validated against the authoritative fetched [agents] list, not
     * `availableModels` (agents are fetched separately and may not appear there).
     */
    private fun selectability(
        endpoint: String?,
        model: String?,
        filtered: Map<String, List<String>>,
        endpointConfigs: Map<String, EndpointConfig>,
        agents: List<Agent>,
        agentsLoaded: Boolean,
    ): Selectability {
        if (endpoint == null || model == null) return Selectability.INVALID
        if (endpoint == EndpointConstants.AGENTS) {
            return when {
                !agentsLoaded -> Selectability.PENDING
                // An empty-but-loaded agents list is the transient pre-populate window
                // (loadAgents flips agentsLoaded=true before publishing the list): it can
                // neither confirm nor refute a specific agent id, so PENDING (hold), not
                // INVALID. The caller distinguishes "hold an explicit agent" from "no agents
                // exist" via holdAgentForPopulate.
                agents.isEmpty() -> Selectability.PENDING
                agents.any { it.id == model } -> Selectability.VALID
                else -> Selectability.INVALID
            }
        }
        val modelsForEndpoint = filtered[endpoint]
        return when {
            modelsForEndpoint != null && model in modelsForEndpoint -> Selectability.VALID
            modelsForEndpoint != null -> Selectability.INVALID // models loaded, model gone
            // modelsForEndpoint == null: distinguish "this endpoint's models haven't
            // loaded yet" (PENDING) from "this endpoint is no longer configured"
            // (INVALID). Once configs have loaded, an endpoint absent from them was
            // removed server-side — don't WAIT on it forever.
            endpointConfigs.isNotEmpty() && endpoint !in endpointConfigs -> Selectability.INVALID
            else -> Selectability.PENDING
        }
    }

    private enum class Selectability { VALID, PENDING, INVALID }

    private data class SeedInputs(
        val lastEndpoint: String?,
        val lastModel: String?,
        val rawModels: Map<String, List<String>>,
        val endpointConfigs: Map<String, EndpointConfig>,
        val agents: List<Agent>,
        val agentsLoaded: Boolean,
        val conversationId: String?,
    )

    private data class AgentsInput(
        val agents: List<Agent>,
        val conversationId: String?,
        val loaded: Boolean,
    )

    fun onModelSelected(endpoint: String, model: String) {
        stateHandle.update {
            copy(
                selectedEndpoint = endpoint,
                selectedModel = model,
            )
        }
        // Keep cached values in sync so refilterModels uses the latest choice
        cachedLastUsedEndpoint = endpoint
        cachedLastUsedModel = model
        stateHandle.scope.launch {
            settingsDataStore.setLastUsedModel(endpoint, model)
        }
    }

    /**
     * Fetches the agent list into state. Auto-selecting the first agent for a new
     * chat is NOT done here — [seedInitialSelection] owns selection. This sets
     * [agentsLoaded] on every exit path so the seeder can distinguish "agents
     * still loading" (wait) from "agents loaded and empty" (fall through).
     */
    fun loadAgents(isNewConversation: Boolean) {
        stateHandle.scope.launch {
            // Skip the fetch entirely when the role denies AGENTS.USE; otherwise
            // the server would return 403 and we'd have to decide whether it's a
            // genuine 403 (rate limit, tenancy) vs. permission denial.
            if (permissionGate.awaitRole()?.hasAccess(PermissionType.AGENTS, Permission.USE) == false) {
                agentsLoaded.value = true
                // Un-stick the corrective-fallback hold (see below) even on this path.
                refilterModels(isNewConversation)
                return@launch
            }
            when (val result = agentRepository.getAgents()) {
                is Result.Success -> {
                    // Set agentsLoaded BEFORE publishing the agent list so the
                    // seeder emission carrying the agents already sees the flag —
                    // otherwise tier 2 could fall through to a config model in the
                    // one-emission window before the flag flips.
                    agentsLoaded.value = true
                    stateHandle.update { copy(agents = result.data) }
                }
                is Result.Error -> {
                    Logger.e(result.exception) { "Failed to load agents" }
                    stateHandle.update { copy(error = "Could not load available agents") }
                    agentsLoaded.value = true
                }
                is Result.Loading -> return@launch
            }
            // The existing-conversation corrective fallback in [refilterModels] HOLDS
            // (returns early) for an agents last-used while the agents list is unloaded.
            // Re-run on EVERY terminal path that flips [agentsLoaded] — success, error,
            // AND denial — so the hold is always released; an error/denied load (empty
            // list) correctly falls through to a config model instead of staying stuck.
            refilterModels(isNewConversation)
        }
    }

    fun loadMcpServers() {
        stateHandle.scope.launch {
            when (val serversResult = mcpRepository.listServers()) {
                is Result.Success -> {
                    val servers = serversResult.data
                    // Enrich servers with connection status
                    val statusResult = mcpRepository.getConnectionStatus()
                    val statusMap = (statusResult as? Result.Success)?.data ?: emptyMap()
                    val enriched = servers.map { server ->
                        val status = statusMap[server.name]
                        server.copy(isConnected = status?.isConnected ?: false)
                    }
                    stateHandle.update {
                        copy(mcpServers = enriched.map { it.toDisplayData() })
                    }
                }
                is Result.Error -> {
                    Logger.d(serversResult.exception) { "Failed to load MCP servers: ${serversResult.message}" }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun toggleMcpServer(serverName: String) {
        val current = stateHandle.state.selectedMcpServerNames
        val updated = if (serverName in current) current - serverName else current + serverName
        stateHandle.update { copy(selectedMcpServerNames = updated) }
        stateHandle.scope.launch { settingsDataStore.setSelectedMcpServers(updated) }
    }

    fun toggleTool(toolName: String) {
        if (toolName == ToolConstants.WEB_SEARCH) {
            // Web search is backed by modelParameters.webSearch (single source of truth).
            val current = stateHandle.state.modelParameters.webSearch
            stateHandle.update {
                copy(modelParameters = modelParameters.copy(webSearch = !current))
            }
        } else if (toolName == ToolConstants.URL_CONTEXT) {
            // URL context (Google-only) is backed by modelParameters.urlContext, like web search.
            val current = stateHandle.state.modelParameters.urlContext
            stateHandle.update {
                copy(modelParameters = modelParameters.copy(urlContext = !current))
            }
        } else if (toolName == ToolConstants.CODE_INTERPRETER && !stateHandle.state.isCodeInterpreterAvailable) {
            // Code interpreter is not available on this server; ignore toggle attempt.
            return
        } else {
            val current = stateHandle.state.enabledTools
            val updated = if (toolName in current) current - toolName else current + toolName
            stateHandle.update { copy(enabledTools = updated) }
            stateHandle.scope.launch { settingsDataStore.setEnabledTools(updated) }
        }
    }

    fun showModelParameters() {
        stateHandle.update { copy(showModelParameters = true) }
    }

    fun hideModelParameters() {
        stateHandle.update { copy(showModelParameters = false) }
    }

    fun updateModelParameters(parameters: ModelParameters) {
        stateHandle.update { copy(modelParameters = parameters) }
    }
}

/**
 * Filters availableModels to the endpoints the user's server has enabled, dropping
 * endpoints with empty model lists. Single source of truth for "which models are
 * usable" — shared by [ModelSelectionDelegate] (seeding/validation) and the model
 * selector UI so they can't drift apart.
 */
internal fun filterModelsByEndpoint(
    rawModels: Map<String, List<String>>,
    endpointConfigs: Map<String, EndpointConfig>,
): Map<String, List<String>> =
    if (endpointConfigs.isEmpty()) {
        rawModels.filterValues { it.isNotEmpty() }
    } else {
        rawModels.filterKeys { it in endpointConfigs }.filterValues { it.isNotEmpty() }
    }

// --- Display data mapping extensions ---

internal fun McpServer.toDisplayData() = McpServerDisplayData(
    name = name,
    title = title,
    description = description,
    isConnected = isConnected,
)
