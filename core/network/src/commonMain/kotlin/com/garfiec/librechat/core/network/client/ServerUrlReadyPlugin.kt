package com.garfiec.librechat.core.network.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase

/**
 * Awaits the server URL's async warm-up before each request is built.
 *
 * [ServerDataStore] resolves the persisted base URL asynchronously off the Main thread, so
 * during a narrow cold-start window [ServerUrlProvider.getBaseUrl] (which the non-suspend
 * `defaultRequest` block reads) can transiently return an empty string. Rather than relying on
 * every individual caller to remember to await, this plugin resolves the readiness at the
 * network layer: it runs in a phase inserted *before* [HttpRequestPipeline.Before] — where
 * Ktor's `DefaultRequest` applies the URL — so by the time `defaultRequest` reads
 * `getBaseUrl()` the warm-up has completed. After warm-up it is a no-op suspension point.
 */
class ServerUrlReadyPlugin private constructor(
    private val serverUrlProvider: ServerUrlProvider,
) {
    class Config {
        lateinit var serverUrlProvider: ServerUrlProvider
    }

    companion object : HttpClientPlugin<Config, ServerUrlReadyPlugin> {
        override val key = AttributeKey<ServerUrlReadyPlugin>("ServerUrlReady")
        private val AwaitServerUrlPhase = PipelinePhase("AwaitServerUrl")

        override fun prepare(block: Config.() -> Unit): ServerUrlReadyPlugin {
            val config = Config().apply(block)
            return ServerUrlReadyPlugin(config.serverUrlProvider)
        }

        override fun install(plugin: ServerUrlReadyPlugin, scope: HttpClient) {
            scope.requestPipeline.insertPhaseBefore(HttpRequestPipeline.Before, AwaitServerUrlPhase)
            scope.requestPipeline.intercept(AwaitServerUrlPhase) {
                plugin.serverUrlProvider.awaitBaseUrl()
            }
        }
    }
}
