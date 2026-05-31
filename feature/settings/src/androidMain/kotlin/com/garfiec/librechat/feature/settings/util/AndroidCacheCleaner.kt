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
}
