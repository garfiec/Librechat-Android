package com.garfiec.librechat.core.common.network

import kotlin.coroutines.CoroutineContext

/**
 * Marks a coroutine as background prefetch work, exempting its requests from
 * [RequestActivityTracker].
 *
 * Must stay a coroutine-context element, not a request attribute: any path that failed to carry it
 * would make the prefetcher count itself as user activity and deadlock against its own idle gate,
 * silently. The Ktor interceptor reads this from the *calling* coroutine's context, which is what
 * makes it visible there — `RequestActivityPluginTest` pins that.
 */
object PrefetchMarker : CoroutineContext.Element {

    override val key: CoroutineContext.Key<*> get() = Key

    object Key : CoroutineContext.Key<PrefetchMarker>
}

fun CoroutineContext.isPrefetch(): Boolean = this[PrefetchMarker.Key] != null
