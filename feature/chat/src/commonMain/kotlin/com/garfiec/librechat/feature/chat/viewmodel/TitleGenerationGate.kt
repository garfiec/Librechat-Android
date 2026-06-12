package com.garfiec.librechat.feature.chat.viewmodel

/** The server's placeholder title for a conversation whose title hasn't been generated yet. */
internal const val PLACEHOLDER_CONVERSATION_TITLE = "New Chat"

/**
 * Decides whether `handleFinal` should long-poll `gen_title` for the just-finished stream.
 *
 * Lives outside `ChatViewModel` (like `resolveEndpointDispatch`) so the contract is
 * pinned by tests: the VM that handles a brand-new chat's first Final is usually NOT the
 * landing VM — navigation to Chat(id) fires at the `created` event, so [isNewConversation]
 * is false there and [isHandedOffNewChat] (from consuming `NewChatSelectionHandoff`) is
 * what keeps title generation alive. Dropping either flag from this gate silently kills
 * title generation for one of the two paths.
 *
 * Gating on the flags rather than on the placeholder title alone is deliberate: the
 * server's gen_title endpoint long-polls ~15s before 404-ing for conversations whose
 * title will never generate, so firing it for every placeholder-titled conversation
 * would hold a connection open on every send in such chats.
 */
internal fun shouldRequestTitleGeneration(
    isNewConversation: Boolean,
    isHandedOffNewChat: Boolean,
    currentTitle: String?,
    alreadyRequested: Boolean,
): Boolean {
    val needsTitle = currentTitle.isNullOrBlank() || currentTitle == PLACEHOLDER_CONVERSATION_TITLE
    return (isNewConversation || isHandedOffNewChat) && needsTitle && !alreadyRequested
}
