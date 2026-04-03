package com.librechat.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.librechat.android.core.common.di.commonModule
import com.librechat.android.core.data.di.dataModule
import com.librechat.android.core.network.client.TokenManager
import com.librechat.android.core.network.di.networkModule
import com.librechat.android.feature.agents.di.agentsModule
import com.librechat.android.feature.auth.di.authModule
import com.librechat.android.feature.chat.di.chatModule
import com.librechat.android.feature.conversations.di.conversationsModule
import com.librechat.android.feature.files.di.filesModule
import com.librechat.android.feature.settings.di.settingsModule
import com.librechat.android.feature.voice.di.voiceModule
import com.librechat.android.navigation.appModule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import timber.log.Timber

class LibreChatApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        try {
            startKoin {
                if (BuildConfig.DEBUG) {
                    androidLogger(org.koin.core.logger.Level.DEBUG)
                }
                androidContext(this@LibreChatApplication)
                allowOverride(false)
                modules(
                    commonModule,
                    networkModule,
                    dataModule,
                    appModule,
                    authModule,
                    chatModule,
                    conversationsModule,
                    settingsModule,
                    agentsModule,
                    filesModule,
                    voiceModule,
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Koin initialization failed")
            throw e // Always rethrow — DI failure is unrecoverable
        }
    }

    override fun newImageLoader(): ImageLoader {
        val tokenManager: TokenManager = get()

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = runBlocking { tokenManager.getAccessToken() }
                val requestBuilder = chain.request().newBuilder()
                    // The backend's uaParser middleware (ua-parser-js) rejects requests
                    // without a recognized browser User-Agent with 403. This must match
                    // the UA string used by the Ktor HttpClient.
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                    )
                if (token != null) {
                    requestBuilder.addHeader("Authorization", "Bearer $token")
                }
                val request = requestBuilder.build()
                Timber.d("Coil image request: %s", request.url)
                val response = chain.proceed(request)
                if (!response.isSuccessful) {
                    Timber.w(
                        "Coil image request failed: %s -> %d %s",
                        request.url,
                        response.code,
                        response.message,
                    )
                }
                response
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(250L * 1024 * 1024) // 250 MB
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
