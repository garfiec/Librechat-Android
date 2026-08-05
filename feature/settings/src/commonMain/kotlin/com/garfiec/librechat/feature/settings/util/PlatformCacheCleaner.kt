package com.garfiec.librechat.feature.settings.util

interface PlatformCacheCleaner {
    suspend fun clearCache()

    /**
     * Bytes held in the cache directory — images and downloaded files.
     *
     * Deliberately **not** the Room database. Two reasons, and both would make the number a lie:
     * [clearCache] does not touch it (`librechat.db` lives outside the cache directory), and SQLite
     * does not release pages on delete without a `VACUUM`, so a conversation-cache figure would not
     * move after clearing or pruning. A number that never changes reads as a bug.
     */
    suspend fun cacheSizeBytes(): Long
}
