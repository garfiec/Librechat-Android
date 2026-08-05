package com.garfiec.librechat.core.data.prefetch

/**
 * Pulls image bytes into the platform image cache ahead of time.
 *
 * Keep image-library types out of this signature: it is what lets `:core:data` avoid a dependency
 * on the image loader, and what lets a platform with no disk cache bind a no-op.
 */
interface AttachmentWarmer {

    /**
     * True when warming actually caches anything on this platform. The settings UI hides the toggle
     * when it does not — a switch that silently does nothing is worse than an absent one.
     */
    val isSupported: Boolean

    /** Fetches [url] into the image cache. Failures are not worth reporting: this is opportunistic. */
    suspend fun warm(url: String)
}
