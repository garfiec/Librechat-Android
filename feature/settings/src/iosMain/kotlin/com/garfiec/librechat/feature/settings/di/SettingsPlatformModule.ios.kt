package com.garfiec.librechat.feature.settings.di

import co.touchlab.kermit.Logger
import com.garfiec.librechat.feature.settings.util.ContentReader
import com.garfiec.librechat.feature.settings.util.PlatformCacheCleaner
import com.garfiec.librechat.feature.settings.viewmodel.delegate.IosSpeechSettingsDelegate
import com.garfiec.librechat.feature.settings.viewmodel.delegate.SpeechSettingsFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfURL
import platform.posix.memcpy

actual val settingsPlatformModule: Module = module {
    single {
        @OptIn(ExperimentalForeignApi::class)
        object : ContentReader {
            override fun readBytes(uri: Any): ByteArray? {
                val nsUrl = uri as? NSURL
                if (nsUrl == null) {
                    Logger.w("SettingsContentReader") { "readBytes called with non-NSURL: ${uri::class}" }
                    return null
                }
                val data = NSData.dataWithContentsOfURL(nsUrl)
                if (data == null) {
                    Logger.w("SettingsContentReader") { "Failed to read data from: $nsUrl" }
                    return null
                }
                val size = data.length.toInt()
                if (size == 0) return ByteArray(0)
                val result = ByteArray(size)
                result.usePinned { pinned ->
                    memcpy(pinned.addressOf(0), data.bytes, data.length)
                }
                return result
            }
        }
    } bind ContentReader::class
    single {
        @OptIn(ExperimentalForeignApi::class)
        object : PlatformCacheCleaner {
            override suspend fun clearCache() {
                withContext(Dispatchers.Default) {
                    try {
                        val paths = NSSearchPathForDirectoriesInDomains(
                            NSCachesDirectory,
                            NSUserDomainMask,
                            true,
                        )
                        val cachePath = paths.firstOrNull() as? String
                        if (cachePath == null) {
                            Logger.w("CacheCleaner") { "Could not resolve caches directory" }
                            return@withContext
                        }
                        val fm = NSFileManager.defaultManager
                        val contents = fm.contentsOfDirectoryAtPath(cachePath, null) as? List<*>
                        contents?.forEach { item ->
                            val name = item as? String ?: return@forEach
                            fm.removeItemAtPath("$cachePath/$name", null)
                        }
                        Logger.i("CacheCleaner") { "Cleared iOS caches directory" }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(e) { "Failed to clear caches" }
                    }
                }
            }
        }
    } bind PlatformCacheCleaner::class
    single<SpeechSettingsFactory> {
        SpeechSettingsFactory { stateHandle ->
            IosSpeechSettingsDelegate(
                stateHandle = stateHandle,
                speechRepository = get(),
                settingsDataStore = get(),
            )
        }
    }
}
