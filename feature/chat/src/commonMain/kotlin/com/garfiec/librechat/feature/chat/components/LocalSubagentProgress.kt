package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.garfiec.librechat.feature.chat.viewmodel.SubagentTrace

/**
 * Live subagent traces (v0.8.6) keyed by the parent `subagent` tool_call id,
 * supplied by [ChatRoot] from `ChatUiState.subagentProgress`. The subagent
 * trace card reads this to render a child agent's activity WHILE it streams,
 * without threading the buffer through every content-renderer signature.
 *
 * Defaults to empty so previews and any composition outside the chat screen
 * simply show no live progress (the reload path uses persisted
 * `AgentToolCall.subagentContent` instead).
 */
val LocalSubagentProgress = staticCompositionLocalOf<Map<String, SubagentTrace>> { emptyMap() }
