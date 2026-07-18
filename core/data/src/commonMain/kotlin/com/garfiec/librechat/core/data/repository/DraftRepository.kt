package com.garfiec.librechat.core.data.repository

interface DraftRepository {
    suspend fun getDraft(conversationId: String): String?

    /**
     * Like [getDraft] but suspends through the cold-start / post-migration warming window until the
     * active account resolves, then reads. Used to restore a draft into the composer on screen entry:
     * a one-shot [getDraft] there races identity resolution and returns null while the account is
     * still [com.garfiec.librechat.core.common.identity.AccountState.Warming], so the saved draft
     * would only reappear on a later launch.
     */
    suspend fun awaitDraft(conversationId: String): String?
    suspend fun saveDraft(conversationId: String, text: String)
    suspend fun deleteDraft(conversationId: String)
}
