package com.garfiec.librechat.core.data.repository

import co.touchlab.kermit.Logger

/**
 * Subdirectories under the platform cache root that are cleared on logout.
 */
internal val CACHE_SUBDIRECTORIES = listOf(
    "image_cache",
    "artifacts",
    "shared_images",
    "shared_files",
    "camera_photos",
    "pdf_preview",
    "voice_recording",
    "audio",
    "tts",
    "voice_test",
)

/**
 * Deletes a directory and all its contents at the given [path].
 */
internal expect fun deleteDirectoryRecursively(path: String)

/**
 * Common implementation of [SessionCacheCleaner] that iterates [CACHE_SUBDIRECTORIES] and deletes
 * each from the provided [cacheRoot] directory. Account-blind: role permissions and other
 * account-scoped state are reaped by the account teardown, not here.
 */
class CommonSessionCacheCleaner(
    private val cacheRoot: String,
) : SessionCacheCleaner {
    override fun clearFileCaches() {
        try {
            for (subdir in CACHE_SUBDIRECTORIES) {
                deleteDirectoryRecursively("$cacheRoot/$subdir")
            }
        } catch (e: Exception) {
            Logger.w(e) { "Failed to clear session caches on logout" }
        }
    }
}
