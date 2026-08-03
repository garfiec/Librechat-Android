package com.garfiec.librechat.feature.chat.viewmodel

/**
 * Carries prompt text from the prompts library back to the chat composer.
 *
 * The library is its own navigation entry, so "Use in chat" can't hand text to the chat screen
 * directly — it pops the back stack and the chat entry (whose ViewModel is still alive) picks the
 * text up on resume.
 *
 * Deliberately *not* routed through `DraftRepository`: drafts for an unsaved chat share the single
 * `__new_chat__` key across every conversation, so staging text there leaks between threads.
 *
 * Single-slot and take-once, mirroring [NewChatSelectionHandoff]. Text staged but never collected
 * dies with the process, which is the right failure mode — a prompt silently appearing in the
 * composer on next launch would be worse than losing it.
 */
class PromptInsertionHandoff {

    private var pending: String? = null

    fun put(text: String) {
        pending = text
    }

    /** Returns and clears the staged text, or null when nothing is waiting. */
    fun take(): String? {
        val current = pending
        pending = null
        return current
    }
}
