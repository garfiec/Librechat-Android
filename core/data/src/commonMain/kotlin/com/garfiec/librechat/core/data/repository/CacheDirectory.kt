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
 *
 * [cacheRoot] must stay a function: resolving the platform cache directory is a disk read, and this
 * cleaner is a constructor dependency of `AuthRepositoryImpl`, so an eagerly-evaluated root puts that
 * read on the main thread when `NavHostViewModel` is first built. Deferred, it lands on the teardown
 * paths that call [clearFileCaches] — logout and last-account removal — which already do file I/O.
 *
 * A platform root that cannot be resolved therefore throws here rather than at DI resolution, and is
 * caught below: the caches go uncleared with a warning instead of taking down the first frame.
 */
class CommonSessionCacheCleaner(
    private val cacheRoot: () -> String,
) : SessionCacheCleaner {
    override fun clearFileCaches() {
        try {
            val root = cacheRoot()
            for (subdir in CACHE_SUBDIRECTORIES) {
                deleteDirectoryRecursively("$root/$subdir")
            }
        } catch (e: Exception) {
            Logger.w(e) { "Failed to clear session caches on logout" }
        }
    }
}
