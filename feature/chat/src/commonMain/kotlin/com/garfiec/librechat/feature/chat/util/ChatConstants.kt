package com.garfiec.librechat.feature.chat.util

import com.garfiec.librechat.core.model.NEW_CHAT_DRAFT_KEY as CORE_NEW_CHAT_DRAFT_KEY

/**
 * Draft key used for new chats that don't have a conversation ID yet. Aliases the canonical constant
 * in :core:model so the runtime key and the legacy-claim's special-case can never drift apart.
 */
const val NEW_CHAT_DRAFT_KEY = CORE_NEW_CHAT_DRAFT_KEY
