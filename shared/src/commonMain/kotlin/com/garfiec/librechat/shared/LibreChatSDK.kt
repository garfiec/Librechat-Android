package com.garfiec.librechat.shared

import com.garfiec.librechat.core.network.api.AuthApi
import com.garfiec.librechat.core.network.api.ChatApi
import com.garfiec.librechat.core.network.client.TokenManager
import com.garfiec.librechat.core.network.sse.SseClient

/**
 * Top-level entry point that iOS uses to interact with the KMP business logic.
 * Aggregates the key APIs needed for SSE streaming and token access.
 *
 * Login is NOT exposed here: sign-in must go through `AuthRepository` (driven by the shared
 * Compose auth UI), which establishes the active account and re-homes the staged tokens under
 * the account-keyed slots. A bare `setTokens` without that follow-up would leave the session
 * unresolved and clobber the active-account mirror, so no SDK login shortcut is provided.
 *
 * SSE usage: call `sseClient.connect(streamPath)` directly — the transport it
 * needs is injected into `SseClient` at construction time.
 * SKIE converts the returned `Flow<StreamEvent>` to `AsyncSequence` on iOS.
 */
class LibreChatSDK(
    val authApi: AuthApi,
    val chatApi: ChatApi,
    val sseClient: SseClient,
    val tokenManager: TokenManager,
)
