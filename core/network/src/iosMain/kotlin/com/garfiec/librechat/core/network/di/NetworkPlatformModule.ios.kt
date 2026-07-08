package com.garfiec.librechat.core.network.di

import com.garfiec.librechat.core.network.sse.SseHttpTransport
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin
import org.koin.core.module.Module
import org.koin.dsl.module

actual val networkPlatformModule: Module = module {
    single<HttpClientEngineFactory<*>> { Darwin }
    // iOS SSE transport: NWConnection-based, takes (TokenManager, SwitchGate) — the
    // NSURLSession-bypass path. Android's actual takes the Ktor streaming HttpClient instead.
    single { SseHttpTransport(get(), get()) }
}
