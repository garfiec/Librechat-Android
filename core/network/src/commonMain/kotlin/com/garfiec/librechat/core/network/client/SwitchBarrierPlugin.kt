package com.garfiec.librechat.core.network.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.http.DEFAULT_PORT
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase

/**
 * Installs the [SwitchGate] as a per-request barrier: in a phase inserted *before*
 * [HttpRequestPipeline.Before], it captures the `(baseUrl, accountId, bearer)` snapshot, stores it in
 * the request attributes ([RequestIdentityKey]) for the auth phase to read, and resolves the request
 * URL against the snapshot's base URL right here — before `defaultRequest` runs.
 *
 * Because the base URL is applied here (setting a non-empty host), Ktor's `DefaultRequest.mergeUrls`
 * at [HttpRequestPipeline.Before] early-returns (`requestUrl.host.isNotEmpty()`), so `defaultRequest`
 * never overwrites the snapshot URL with the *live* provider value — closing the window where a switch
 * between the barrier and `Before` would send a request to the new server carrying the old snapshot's
 * bearer. Absolute (cross-host) request URLs already carry a host, so the base is not applied to them,
 * exactly as before.
 *
 * This barrier subsumes [ServerUrlReadyPlugin]'s role on the clients it is installed on:
 * [SwitchGate.captureSnapshot] awaits the account/URL readiness itself. The refresh client stays on
 * [ServerUrlReadyPlugin] (ungated) so a seed/switch-time token operation can't deadlock behind it.
 */
class SwitchBarrierPlugin private constructor(
    private val switchGate: SwitchGate,
) {
    class Config {
        lateinit var switchGate: SwitchGate
    }

    companion object : HttpClientPlugin<Config, SwitchBarrierPlugin> {
        override val key = AttributeKey<SwitchBarrierPlugin>("SwitchBarrier")
        private val SwitchBarrierPhase = PipelinePhase("SwitchBarrier")

        override fun prepare(block: Config.() -> Unit): SwitchBarrierPlugin =
            SwitchBarrierPlugin(Config().apply(block).switchGate)

        override fun install(plugin: SwitchBarrierPlugin, scope: HttpClient) {
            scope.requestPipeline.insertPhaseBefore(HttpRequestPipeline.Before, SwitchBarrierPhase)
            scope.requestPipeline.intercept(SwitchBarrierPhase) {
                val snapshot = plugin.switchGate.captureSnapshot()
                context.attributes.put(RequestIdentityKey, snapshot)
                if (snapshot.baseUrl.isNotEmpty()) {
                    applyBaseUrl(context.url, snapshot.baseUrl)
                }
            }
        }

        /**
         * Merge [baseUrlString] into [requestUrl] with the same semantics as Ktor's
         * `DefaultRequest.mergeUrls`: only fill a request that has no host of its own (a relative API
         * call), preserving the request's path (concatenated onto any base path), fragment, and query.
         * An absolute request URL (host already set — a presigned CDN fetch) is left untouched.
         */
        private fun applyBaseUrl(requestUrl: URLBuilder, baseUrlString: String) {
            if (requestUrl.host.isNotEmpty()) return
            val resultUrl = URLBuilder(Url(baseUrlString))
            if (requestUrl.port != DEFAULT_PORT) {
                resultUrl.port = requestUrl.port
            }
            resultUrl.encodedPathSegments =
                concatenatePath(resultUrl.encodedPathSegments, requestUrl.encodedPathSegments)
            if (requestUrl.encodedFragment.isNotEmpty()) {
                resultUrl.encodedFragment = requestUrl.encodedFragment
            }
            // Preserve the request's own query (e.g. the SSE `?resume=true`). LibreChat base URLs are
            // host-root with no query of their own, so there is nothing to merge from the base side.
            resultUrl.encodedParameters = requestUrl.encodedParameters
            requestUrl.takeFrom(resultUrl)
        }

        private fun concatenatePath(parent: List<String>, child: List<String>): List<String> {
            if (child.isEmpty()) return parent
            if (parent.isEmpty()) return child
            // Child path is absolute (starts from "/") — it replaces the base path entirely.
            if (child.first().isEmpty()) return child
            return buildList(parent.size + child.size - 1) {
                for (index in 0 until parent.size - 1) add(parent[index])
                addAll(child)
            }
        }
    }
}
