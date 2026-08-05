package com.garfiec.librechat.core.network.di

import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.core.network.client.RequestActivityPlugin
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.plugins.pluginOrNull
import org.junit.After
import org.junit.Test
import org.koin.core.KoinApplication
import org.koin.core.qualifier.Qualifier

/**
 * Which clients report request activity, asserted on the graph the app actually builds.
 *
 * Both directions matter. Missing from the main client, background work never sees the user's
 * requests and runs on top of them. Present on the streaming client, a stream would be counted twice
 * on Android and once on iOS — whose SSE never touches a Ktor client — so the two platforms would
 * disagree about when the app is idle.
 */
class RequestActivityInstallTest {

    private var app: KoinApplication? = null

    @After
    fun tearDown() {
        app?.close()
    }

    private fun client(qualifier: Qualifier?): HttpClient {
        val koin = app ?: NetworkGraphTestFakes.koinApp().also { app = it }
        return NetworkGraphTestFakes.client(koin, qualifier)
    }

    @Test
    fun `the main client reports request activity`() {
        assertThat(client(null).pluginOrNull(RequestActivityPlugin)).isNotNull()
    }

    /** SseClient reports the stream itself, on both platforms; this would double-count on Android. */
    @Test
    fun `the streaming client does not report request activity`() {
        assertThat(client(KoinQualifiers.Streaming).pluginOrNull(RequestActivityPlugin)).isNull()
    }

    /**
     * A token refresh is plumbing the user never asked for, and it fires while their request is
     * already counted by the main client's plugin — counting it again would say "busy" for work that
     * is only happening because of work already counted.
     */
    @Test
    fun `the refresh client does not report request activity`() {
        assertThat(client(KoinQualifiers.Refresh).pluginOrNull(RequestActivityPlugin)).isNull()
    }
}
