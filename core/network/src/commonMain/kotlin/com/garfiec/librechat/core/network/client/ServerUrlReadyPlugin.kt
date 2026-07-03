package com.garfiec.librechat.core.network.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase

/**
 * Awaits the account/server cold-start readiness before each request is built.
 *
 * [ServerDataStore] resolves the persisted base URL asynchronously off the Main thread, so
 * during a narrow cold-start window [ServerUrlProvider.getBaseUrl] (which the non-suspend
 * `defaultRequest` block reads) can transiently return an empty string. Rather than relying on
 * every individual caller to remember to await, this plugin resolves the readiness at the
 * network layer: it runs in a phase inserted *before* [HttpRequestPipeline.Before] — where
 * Ktor's `DefaultRequest` applies the URL — so by the time `defaultRequest` reads
 * `getBaseUrl()` the warm-up has completed. After warm-up it is a no-op suspension point.
 *
 * When an [AccountReadyGate] is configured it is awaited **first**: the roster seed reconciles the
 * token mirror to the durable active pointer and drives the server URL from the active entry, so
 * gating on it closes the cold-start window where a request would fly with a stale mirror bearer or a
 * pre-roster server URL. The gate's own seed awaits the same URL warm-up, so `awaitBaseUrl()` after it
 * is a no-op. The token-refresh client is deliberately left ungated so a seed-time mirror reconcile
 * can never deadlock behind an in-flight refresh.
 */
class ServerUrlReadyPlugin private constructor(
    private val serverUrlProvider: ServerUrlProvider,
    private val accountReadyGate: AccountReadyGate?,
) {
    class Config {
        lateinit var serverUrlProvider: ServerUrlProvider
        var accountReadyGate: AccountReadyGate? = null
    }

    companion object : HttpClientPlugin<Config, ServerUrlReadyPlugin> {
        override val key = AttributeKey<ServerUrlReadyPlugin>("ServerUrlReady")
        private val AwaitServerUrlPhase = PipelinePhase("AwaitServerUrl")

        override fun prepare(block: Config.() -> Unit): ServerUrlReadyPlugin {
            val config = Config().apply(block)
            return ServerUrlReadyPlugin(config.serverUrlProvider, config.accountReadyGate)
        }

        override fun install(plugin: ServerUrlReadyPlugin, scope: HttpClient) {
            scope.requestPipeline.insertPhaseBefore(HttpRequestPipeline.Before, AwaitServerUrlPhase)
            scope.requestPipeline.intercept(AwaitServerUrlPhase) {
                plugin.accountReadyGate?.awaitReady()
                plugin.serverUrlProvider.awaitBaseUrl()
            }
        }
    }
}
