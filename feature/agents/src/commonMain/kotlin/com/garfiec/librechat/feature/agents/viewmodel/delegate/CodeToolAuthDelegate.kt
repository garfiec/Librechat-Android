package com.garfiec.librechat.feature.agents.viewmodel.delegate

import com.garfiec.librechat.core.common.ToolConstants
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.AgentToolsRepository
import com.garfiec.librechat.feature.agents.viewmodel.AgentEditorStateHandle
import com.garfiec.librechat.feature.agents.viewmodel.ToolAuthState
import kotlinx.coroutines.launch

/**
 * Owns the Code Interpreter (`execute_code`) tool-key flow: verifying whether
 * the user/server already has a key (`GET /agents/tools/:id/auth`), installing
 * or revoking the user-provided key, and gating the capability toggle on that
 * auth state — turning the toggle on surfaces the key dialog when the tool is
 * unauthenticated rather than enabling a capability that would fail at runtime.
 */
class CodeToolAuthDelegate(
    private val stateHandle: AgentEditorStateHandle,
    private val agentToolsRepository: AgentToolsRepository,
) {

    fun onCodeInterpreterToggled(enabled: Boolean) {
        if (!enabled) {
            // Turning OFF never needs auth.
            stateHandle.update { copy(codeInterpreterEnabled = false) }
            return
        }
        // Turning ON: gate on the latest verify result. If the tool is
        // unauthenticated, surface the key dialog instead of flipping the
        // toggle -- the toggle flips on after a successful key install.
        when (stateHandle.state.codeToolAuthState) {
            ToolAuthState.Unauthenticated -> {
                stateHandle.update { copy(showCodeAuthDialog = true) }
            }
            ToolAuthState.Unknown -> {
                // Race: verify hasn't returned yet. Re-verify and bail; user
                // can retap once the result lands.
                verifyCodeToolAuth()
            }
            ToolAuthState.SystemDefined, ToolAuthState.UserProvided -> {
                stateHandle.update { copy(codeInterpreterEnabled = true) }
            }
        }
    }

    fun showCodeToolAuthDialog() {
        stateHandle.update { copy(showCodeAuthDialog = true) }
    }

    fun dismissCodeToolAuthDialog() {
        stateHandle.update { copy(showCodeAuthDialog = false) }
    }

    fun submitCodeToolApiKey(apiKey: String) {
        if (apiKey.isBlank()) return
        stateHandle.scope.launch {
            val result = agentToolsRepository.installToolKey(
                toolId = TOOL_EXECUTE_CODE,
                authFields = mapOf(CODE_AUTH_FIELD to apiKey),
            )
            when (result) {
                is Result.Success -> {
                    stateHandle.update {
                        copy(
                            codeToolAuthState = ToolAuthState.UserProvided,
                            codeInterpreterEnabled = true,
                            showCodeAuthDialog = false,
                        )
                    }
                }
                is Result.Error -> {
                    stateHandle.update { copy(error = result.message ?: "Failed to save API key") }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun revokeCodeToolApiKey() {
        stateHandle.scope.launch {
            val result = agentToolsRepository.removeToolKey(
                toolId = TOOL_EXECUTE_CODE,
                authFieldNames = listOf(CODE_AUTH_FIELD),
            )
            when (result) {
                is Result.Success -> {
                    stateHandle.update {
                        copy(
                            codeToolAuthState = ToolAuthState.Unauthenticated,
                            codeInterpreterEnabled = false,
                            showCodeAuthDialog = false,
                        )
                    }
                }
                is Result.Error -> {
                    stateHandle.update { copy(error = result.message ?: "Failed to revoke API key") }
                }
                is Result.Loading -> { /* no-op */ }
            }
        }
    }

    fun verifyCodeToolAuth() {
        stateHandle.scope.launch {
            val result = agentToolsRepository.verifyToolAuth(TOOL_EXECUTE_CODE)
            if (result is Result.Success) {
                val data = result.data
                val next = when {
                    data.authenticated != true -> ToolAuthState.Unauthenticated
                    data.isSystemDefined -> ToolAuthState.SystemDefined
                    data.isUserProvided -> ToolAuthState.UserProvided
                    // authenticated = true with an unknown message; treat as configured.
                    else -> ToolAuthState.SystemDefined
                }
                stateHandle.update { copy(codeToolAuthState = next) }
            }
            // On error, leave state at Unknown -- user can retap and we'll retry.
        }
    }

    private companion object {
        /** Tool id used with `GET /agents/tools/:id/auth`. */
        const val TOOL_EXECUTE_CODE = ToolConstants.EXECUTE_CODE

        /** Upstream auth-field name for Code Interpreter (hooks/Plugins/useAuthCodeTool.ts). */
        const val CODE_AUTH_FIELD = "LIBRECHAT_CODE_API_KEY"
    }
}
