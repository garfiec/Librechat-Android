package com.garfiec.librechat.core.data.prefetch

/**
 * iOS configures Coil with a memory cache only (`LibreChatApp`), so there is no disk cache for a
 * warm to survive into — a fetched image would be evicted long before the user opened the
 * conversation, having spent their bandwidth for nothing.
 *
 * Give iOS a disk cache and this becomes a real implementation.
 */
class NoopAttachmentWarmer : AttachmentWarmer {
    override val isSupported: Boolean = false
    override suspend fun warm(url: String) = Unit
}
