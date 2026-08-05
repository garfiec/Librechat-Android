package com.garfiec.librechat.feature.settings.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidCacheCleaner(private val context: Context) : PlatformCacheCleaner {
    override suspend fun clearCache() {
        withContext(Dispatchers.IO) {
            context.cacheDir.deleteRecursively()
        }
    }

    override suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        // Walks the same directory clearCache() empties, so the figure drops to roughly zero when the
        // user taps Clear — which is the only behaviour that makes a size readout worth showing.
        runCatching {
            context.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }
}
