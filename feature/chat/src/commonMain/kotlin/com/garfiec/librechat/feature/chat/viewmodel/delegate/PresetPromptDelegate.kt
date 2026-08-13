package com.garfiec.librechat.feature.chat.viewmodel.delegate

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.PresetRepository
import com.garfiec.librechat.core.data.repository.PromptRepository
import com.garfiec.librechat.core.model.Preset
import com.garfiec.librechat.core.model.PromptGroup
import com.garfiec.librechat.core.ui.components.ModelParameters
import com.garfiec.librechat.feature.chat.model.PresetDisplayData
import com.garfiec.librechat.feature.chat.model.PromptMentionDisplayData
import com.garfiec.librechat.feature.chat.prompts.PromptInsertion
import com.garfiec.librechat.feature.chat.prompts.resolvePromptInsertion
import com.garfiec.librechat.feature.chat.prompts.resolvePromptText
import com.garfiec.librechat.feature.chat.viewmodel.PendingVariablePrompt
import com.garfiec.librechat.feature.chat.viewmodel.PresetPromptHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PresetPromptDelegate(
    private val handle: PresetPromptHandle,
    private val presetRepository: PresetRepository,
    private val promptRepository: PromptRepository,
) {

    // Keep domain objects for internal operations (loadPreset, handleSlashCommand, etc.)
    private var cachedPresets: List<Preset> = emptyList()
    private var cachedPromptGroups: List<PromptGroup> = emptyList()

    // The PromptRepository.revision the picker's list was built from, and the one the in-flight
    // fetch is for. Null until a load succeeds.
    private var loadedRevision: Long? = null
    private var requestedRevision: Long? = null
    private var loadJob: Job? = null

    fun loadPresets() {
        handle.scope.launch {
            when (val result = presetRepository.getAll()) {
                is Result.Success -> {
                    cachedPresets = result.data
                    handle.update {
                        presetPrompts = presetPrompts.copy(presets = result.data.map { it.toDisplayData() })
                    }
                }
                is Result.Error -> {
                    // Presets are non-critical; don't block the user
                    Logger.d(result.exception) { "Failed to load presets: ${result.message}" }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Loads every visible prompt group for the composer's `/` picker.
     *
     * Uses the unpaginated route deliberately: the picker is a search surface, and a page limit
     * would hide groups with no indication that anything was omitted.
     */
    fun loadAvailablePrompts() {
        launchLoad(promptRepository.revision.value)
    }

    /**
     * Re-reads the picker's prompts, but only when a prompt actually changed since the list was
     * loaded. A load that failed leaves [loadedRevision] null, so returning to the chat retries it.
     */
    fun refreshAvailablePromptsIfStale() {
        val current = promptRepository.revision.value
        if (current == loadedRevision) return
        // Already fetching exactly this revision (the initial load, or a previous entry): joining
        // it is what keeps the first composition from duplicating `init`'s load.
        if (loadJob?.isActive == true && requestedRevision == current) return
        launchLoad(current)
    }

    private fun launchLoad(revision: Long) {
        // Cancel-replace: the fetches are unordered and the write below is last-writer-wins, so a
        // superseded load could otherwise land on top of a newer list and re-list a deleted prompt.
        loadJob?.cancel()
        requestedRevision = revision
        loadJob = handle.scope.launch {
            when (val result = promptRepository.getAllGroups()) {
                is Result.Success -> {
                    val groups = result.data.filter { it.id != null }
                    cachedPromptGroups = groups
                    loadedRevision = revision
                    handle.update {
                        presetPrompts = presetPrompts.copy(availablePrompts = groups.map { it.toDisplayData() })
                    }
                }
                is Result.Error -> {
                    // Prompts are non-critical; don't block the user
                    Logger.d(result.exception) { "Failed to load prompts: ${result.message}" }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun savePreset(name: String) {
        val state = handle.state
        val params = state.modelParameters
        val dyn = params.dynamicValues
        val preset = Preset(
            title = name,
            endpoint = state.selectedEndpoint.takeIf { it.isNotBlank() },
            model = state.selectedModel,
            // ModelParameters has non-nullable typed slots seeded with the
            // "neutral" default for each field, so we can't tell "user set
            // 1.0" from "user never touched the slider". Pragmatic
            // compromise: omit when the value still matches DEFAULT — that
            // skips persisting OpenAI-style defaults into a preset the
            // user may later load on Anthropic / Google. The cost is that
            // a user who deliberately set temperature=1.0 sees it not
            // saved; the four sliders affected (temperature, top_p,
            // frequency/presence penalty) all have UPSTREAM defaults that
            // equal these mobile defaults, so on reload the value is
            // restored from upstream's schema default.
            temperature = params.temperature.toDouble().takeIf { it != ModelParameters.DEFAULT.temperature.toDouble() },
            topP = params.topP.toDouble().takeIf { it != ModelParameters.DEFAULT.topP.toDouble() },
            topK = params.topK,
            // maxOutputTokens is the canonical wire key; older clients also
            // expect maxTokens, but emit only one to avoid an ambiguous
            // payload — pickers/loaders coalesce via `?: preset.maxTokens`.
            maxTokens = null,
            maxOutputTokens = params.maxOutputTokens,
            maxContextTokens = params.maxContextTokens,
            frequencyPenalty = params.frequencyPenalty.toDouble().takeIf { it != ModelParameters.DEFAULT.frequencyPenalty.toDouble() },
            presencePenalty = params.presencePenalty.toDouble().takeIf { it != ModelParameters.DEFAULT.presencePenalty.toDouble() },
            system = params.customInstructions.takeIf { it.isNotBlank() },
            promptPrefix = params.customInstructions.takeIf { it.isNotBlank() },
            modelLabel = params.customName.takeIf { it.isNotBlank() },
            chatGptLabel = params.customName.takeIf { it.isNotBlank() },
            stop = dyn["stop"]?.takeIf { it.isNotBlank() }?.split("\n")?.filter { it.isNotBlank() },
            effort = dyn["effort"],
            reasoningEffort = dyn["reasoning_effort"],
            reasoningSummary = dyn["reasoning_summary"],
            verbosity = dyn["verbosity"],
            useResponsesApi = dyn["useResponsesApi"]?.toBooleanStrictOrNull(),
            disableStreaming = dyn["disableStreaming"]?.toBooleanStrictOrNull(),
            thinking = params.thinking,
            thinkingBudget = params.thinkingBudget.toIntOrNull(),
            thinkingDisplay = dyn["thinkingDisplay"],
            thinkingLevel = dyn["thinkingLevel"],
            promptCache = dyn["promptCache"]?.toBooleanStrictOrNull(),
            promptCacheTtl = dyn["promptCacheTtl"]?.takeIf { it == "5m" || it == "1h" },
            webSearch = params.webSearch,
            urlContext = params.urlContext,
            imageDetail = dyn["imageDetail"],
            fileTokenLimit = params.fileTokenLimit,
            tags = dyn["tags"]?.takeIf { it.isNotBlank() }?.split("\n")?.filter { it.isNotBlank() },
            region = dyn["region"],
            resendFiles = params.resendFiles,
        )
        handle.scope.launch {
            try {
                presetRepository.create(preset)
                loadPresets()
            } catch (e: Exception) {
                Logger.e(e) { "Could not save preset" }
                handle.setError("Could not save preset")
            }
        }
    }

    fun loadPreset(displayData: PresetDisplayData) {
        val preset = cachedPresets.find { it.presetId == displayData.presetId } ?: return
        handle.update {
            selection = selection.copy(
                selectedEndpoint = preset.endpoint ?: selection.selectedEndpoint,
                selectedModel = preset.model ?: selection.selectedModel,
                // Authoritative load: a preset represents a complete saved
                // configuration. Merging from current parameters would let
                // unrelated fields (e.g. a temperature from the previous
                // endpoint) leak through unchanged, producing a hybrid the
                // user never saved.
                modelParameters = ModelParameters.DEFAULT.mergedFromPreset(preset),
            )
        }
    }

    fun deletePreset(presetId: String) {
        handle.scope.launch {
            when (presetRepository.delete(presetId)) {
                is Result.Success -> loadPresets()
                is Result.Error -> {
                    handle.setError("Could not delete preset")
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun editPreset(preset: Preset) {
        handle.scope.launch {
            when (presetRepository.update(preset)) {
                is Result.Success -> loadPresets()
                is Result.Error -> {
                    handle.setError("Could not update preset")
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Handles a pick from the `/` picker.
     *
     * The composer holds nothing but the `/query` at this point — the picker only opens when `/` is
     * the first character — so replacing its whole contents is what strips the query.
     */
    fun handleSlashCommand(displayData: PromptMentionDisplayData) {
        val group = cachedPromptGroups.find { it.id == displayData.id } ?: return
        when (val insertion = resolvePromptInsertion(group)) {
            // Nothing to insert; leave the composer alone rather than writing the group's name.
            null -> return

            is PromptInsertion.Ready -> {
                handle.update { composer = composer.copy(inputText = insertion.text) }
                recordUseFor(displayData.id)
            }

            is PromptInsertion.NeedsVariables -> handle.update {
                composer = composer.copy(inputText = "")
                presetPrompts = presetPrompts.copy(
                    pendingVariablePrompt = PendingVariablePrompt(
                        groupId = displayData.id,
                        template = insertion.template,
                        variables = insertion.variables,
                    ),
                )
            }
        }
    }

    /** Inserts the filled-in template and closes the variable dialog. */
    fun confirmVariablePrompt(interpolated: String) {
        val pending = handle.state.presetPrompts.pendingVariablePrompt ?: return
        handle.update {
            composer = composer.copy(inputText = interpolated)
            presetPrompts = presetPrompts.copy(pendingVariablePrompt = null)
        }
        // Mirrors web: the usage ping fires on confirm, so a cancelled dialog records nothing.
        recordUseFor(pending.groupId)
    }

    fun dismissVariablePrompt() {
        handle.update { presetPrompts = presetPrompts.copy(pendingVariablePrompt = null) }
    }

    /** Text handed over from the prompts library across the navigation boundary. */
    fun insertPromptText(text: String) {
        if (text.isBlank()) return
        handle.update { composer = composer.copy(inputText = text) }
    }

    /**
     * Fire-and-forget telemetry ping for `POST /api/prompts/groups/:id/use` (v0.8.5+).
     * Errors are swallowed — analytics is never a user-facing failure.
     */
    private fun recordUseFor(groupId: String) {
        handle.scope.launch {
            when (val result = promptRepository.recordPromptGroupUse(groupId)) {
                is Result.Error -> Logger.d(result.exception) { "recordPromptGroupUse failed (non-fatal): ${result.message}" }
                else -> Unit
            }
        }
    }
}

// --- Display data mapping extensions ---

/**
 * Hydrates a [ModelParameters] from a [Preset]. Typed fields map back to the typed
 * properties on [ModelParameters]; everything else lands in [ModelParameters.dynamicValues]
 * so dropdown / switch / text controls round-trip without losing data.
 */
internal fun ModelParameters.mergedFromPreset(preset: Preset): ModelParameters {
    // Start from an empty map: a preset's dynamicValues fully describe the
    // dynamic-keyed parameters it was saved with. Carrying current entries
    // forward would re-introduce stale dynamic keys from a different endpoint.
    val dyn = mutableMapOf<String, String>()
    preset.stop?.takeIf { it.isNotEmpty() }?.let { dyn["stop"] = it.joinToString("\n") }
    preset.effort?.let { dyn["effort"] = it }
    preset.reasoningEffort?.let { dyn["reasoning_effort"] = it }
    preset.reasoningSummary?.let { dyn["reasoning_summary"] = it }
    preset.verbosity?.let { dyn["verbosity"] = it }
    preset.useResponsesApi?.let { dyn["useResponsesApi"] = it.toString() }
    preset.disableStreaming?.let { dyn["disableStreaming"] = it.toString() }
    preset.thinkingDisplay?.let { dyn["thinkingDisplay"] = it }
    preset.thinkingLevel?.let { dyn["thinkingLevel"] = it }
    preset.promptCache?.let { dyn["promptCache"] = it.toString() }
    preset.promptCacheTtl?.let { dyn["promptCacheTtl"] = it }
    preset.imageDetail?.let { dyn["imageDetail"] = it }
    preset.tags?.takeIf { it.isNotEmpty() }?.let { dyn["tags"] = it.joinToString("\n") }
    preset.region?.let { dyn["region"] = it }
    return copy(
        temperature = preset.temperature?.toFloat() ?: temperature,
        topP = preset.topP?.toFloat() ?: topP,
        topK = preset.topK ?: topK,
        maxOutputTokens = preset.maxOutputTokens ?: preset.maxTokens ?: maxOutputTokens,
        maxContextTokens = preset.maxContextTokens ?: maxContextTokens,
        frequencyPenalty = preset.frequencyPenalty?.toFloat() ?: frequencyPenalty,
        presencePenalty = preset.presencePenalty?.toFloat() ?: presencePenalty,
        customName = preset.modelLabel ?: preset.chatGptLabel ?: customName,
        customInstructions = preset.system ?: preset.promptPrefix ?: customInstructions,
        thinking = preset.thinking ?: thinking,
        thinkingBudget = preset.thinkingBudget?.toString() ?: thinkingBudget,
        webSearch = preset.webSearch ?: webSearch,
        urlContext = preset.urlContext ?: urlContext,
        fileTokenLimit = preset.fileTokenLimit ?: fileTokenLimit,
        resendFiles = preset.resendFiles ?: resendFiles,
        dynamicValues = dyn,
    )
}

internal fun Preset.toDisplayData() = PresetDisplayData(
    presetId = presetId,
    title = title ?: "Untitled Preset",
    endpointLabel = endpoint,
    model = model,
)

/**
 * Groups without an `_id` are dropped before this runs — the id is what selection looks the group
 * up by, so a group that can't be identified can't be acted on either.
 */
internal fun PromptGroup.toDisplayData() = PromptMentionDisplayData(
    id = requireNotNull(id) { "prompt group must have an id to be selectable" },
    name = name,
    command = command,
    oneliner = oneliner,
    category = category,
    promptText = resolvePromptText(this),
)
