package com.garfiec.librechat.core.network.client

import com.garfiec.librechat.core.common.network.PrefetchMarker
import com.garfiec.librechat.core.common.network.RequestActivityTracker
import com.garfiec.librechat.core.common.network.isPrefetch
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.util.AttributeKey
import kotlin.coroutines.coroutineContext

/**
 * Reports every request the user is waiting on to [RequestActivityTracker], so background work can
 * hold off while one is in flight.
 *
 * **Install first**, ahead of `HttpRequestRetry`. Ktor runs the first-installed `HttpSend`
 * interceptor outermost, so from here a retried, redirected or auth-replayed call is one unit of
 * user-visible work rather than three — which is what the gate should be measuring.
 *
 * Exemption comes from [PrefetchMarker] on the calling coroutine's context — see that class. The
 * `coroutineContext` read below must stay the caller's coroutine, or the prefetcher counts itself
 * and silently never runs; `RequestActivityPluginTest` asserts it.
 *
 * Deliberately **not** installed on the streaming client: `SseClient` reports the stream itself, so
 * installing here too would double-count the stream GET on Android only.
 */
class RequestActivityPlugin private constructor(
    private val tracker: RequestActivityTracker,
) {

    class Config {
        var tracker: RequestActivityTracker? = null
    }

    companion object : HttpClientPlugin<Config, RequestActivityPlugin> {
        override val key = AttributeKey<RequestActivityPlugin>("RequestActivity")

        override fun prepare(block: Config.() -> Unit): RequestActivityPlugin {
            val config = Config().apply(block)
            return RequestActivityPlugin(
                tracker = requireNotNull(config.tracker) {
                    "RequestActivityPlugin requires a RequestActivityTracker"
                },
            )
        }

        override fun install(plugin: RequestActivityPlugin, scope: HttpClient) {
            scope.plugin(HttpSend).intercept { request ->
                if (coroutineContext.isPrefetch()) {
                    execute(request)
                } else {
                    plugin.tracker.counted { execute(request) }
                }
            }
        }
    }
}
