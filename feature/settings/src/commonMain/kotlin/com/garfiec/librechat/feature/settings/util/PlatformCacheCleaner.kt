package com.garfiec.librechat.feature.settings.util

interface PlatformCacheCleaner {
    suspend fun clearCache()
}
