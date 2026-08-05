package com.garfiec.librechat.core.network.di

import com.garfiec.librechat.core.common.di.KoinQualifiers
import com.garfiec.librechat.core.network.client.GatewayDetectionPlugin
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.plugins.pluginOrNull
import org.junit.After
import org.junit.Test
import org.koin.core.KoinApplication
import org.koin.core.qualifier.Qualifier

/**
 * Which clients carry gateway detection, asserted on the graph the app actually builds. Nothing else
 * in the suite can notice a missing install: a per-client test installs the plugin itself.
 */
class GatewayDetectionInstallTest {

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
    fun `the main client detects gateway rejections`() {
        assertThat(client(null).pluginOrNull(GatewayDetectionPlugin)).isNotNull()
    }

    /** Without it the sign-in page reaches the SSE parser and the chat hangs with no error at all. */
    @Test
    fun `the streaming client detects gateway rejections`() {
        assertThat(client(KoinQualifiers.Streaming).pluginOrNull(GatewayDetectionPlugin)).isNotNull()
    }

    /** Without it the gateway's 302 classifies as a transient server error and the session never recovers. */
    @Test
    fun `the refresh client detects gateway rejections`() {
        assertThat(client(KoinQualifiers.Refresh).pluginOrNull(GatewayDetectionPlugin)).isNotNull()
    }
}
