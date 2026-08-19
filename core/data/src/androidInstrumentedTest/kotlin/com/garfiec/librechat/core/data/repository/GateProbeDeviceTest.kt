package com.garfiec.librechat.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.garfiec.librechat.core.common.BackendBuildClass
import com.garfiec.librechat.core.common.DetectedBackend
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.config.StartupConfig
import com.garfiec.librechat.core.model.response.Category
import com.garfiec.librechat.core.network.api.FavoritesApi
import com.garfiec.librechat.core.network.api.FilesApi
import com.garfiec.librechat.core.network.api.FilesExtApi
import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import com.garfiec.librechat.core.common.result.ApiException
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device exercise of the three-state version gate against a REAL LibreChat server.
 *
 * The unit tests pin the decision table; this pins the half they cannot: that a real
 * `POST /api/files/usage` / `GET /api/user/settings/favorites/tools` is actually issued (or
 * actually withheld) over real HTTP, on a real device, for each server identity. The server
 * identity is supplied directly here rather than detected, so one running server can stand in
 * for every population the gate has to tell apart.
 *
 * Requires the docker rig on 10.0.2.2:3080. Not a CI test — it is a measurement instrument.
 */
@RunWith(AndroidJUnit4::class)
class GateProbeDeviceTest {

    private val requests = AtomicInteger(0)
    private val statuses = java.util.concurrent.CopyOnWriteArrayList<Int>()

    private fun client(token: String?) = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        // Mirrors LibreChatHttpClient's validator. Without it Ktor hands a 404 back as an
        // ordinary response and the repository reads it as a SUCCESSFUL touch, so the latch
        // never fires.
        HttpResponseValidator {
            validateResponse { response ->
                if (!response.status.isSuccess()) {
                    throw ApiException(
                        statusCode = response.status.value,
                        message = response.status.description,
                    )
                }
            }
        }
        defaultRequest {
            url(BASE_URL)
            // The uaParser middleware soft-bans a non-browser UA on the first request.
            header(HttpHeaders.UserAgent, BROWSER_UA)
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            // The production client sets this in its own defaultRequest; without it a POST
            // carrying a serialized body fails inside ContentNegotiation and never reaches the
            // network, so a call the gate did issue is unobservable here.
            contentType(ContentType.Application.Json)
        }
    }

    /**
     * One login for the whole class. Logging in per test trips the server's login limiter
     * (`429`, no token in the body), which then surfaces as an unrelated-looking
     * NoSuchElementException inside the gate test.
     */
    private fun login(): String = cachedToken ?: runBlocking {
        val raw = client(null).post("api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$EMAIL","password":"$PASSWORD"}""")
        }.bodyAsText()
        val token = Json.parseToJsonElement(raw).jsonObject["token"]?.jsonPrimitive?.content
            ?: error("login did not return a token (rate limited?): ${raw.take(200)}")
        cachedToken = token
        token
    }

    /**
     * Counts gated-route RESPONSES, not requests. A request that dies client-side still fires
     * onRequest, so counting intentions lets "the call never left the device" pass as "the call
     * was made".
     */
    private fun countingClient(token: String) = client(token).config {
        install(
            createClientPlugin("count") {
                onResponse { response ->
                    val url = response.call.request.url.toString()
                    if (url.contains("/api/files/usage") || url.contains("/favorites/tools")) {
                        requests.incrementAndGet()
                        statuses.add(response.status.value)
                    }
                }
            },
        )
    }

    private fun files(detected: DetectedBackend?): FileRepository {
        val c = countingClient(login())
        return FileRepositoryImpl(FilesApi(c), FilesExtApi(c), FakeConfig(detected))
    }

    private fun favorites(detected: DetectedBackend?): ToolFavoritesRepository {
        val c = countingClient(login())
        return ToolFavoritesRepositoryImpl(FavoritesApi(c), FakeConfig(detected))
    }

    /**
     * Skips the whole class when the rig is not up, so a checkout without a local LibreChat on
     * 10.0.2.2:3080 reports "skipped" rather than a wall of connection failures.
     */
    @Before
    fun requireRig() {
        val reachable = runCatching {
            runBlocking { client(null).get("api/config").status.value }
        }.getOrNull()
        assumeTrue("device rig not reachable at $BASE_URL", reachable == 200)
    }

    /**
     * Which server the rig is currently impersonating, read from one representative route:
     * `200` = the real rc1 server (routes present), `404` = the rig's proxy standing in for a
     * server that predates them.
     *
     * Detected rather than assumed, because the class contains both halves of the gate and each
     * half is only meaningful against one of the two rigs. Without this, running the class in the
     * wrong mode fails tests that are working correctly.
     */
    private fun rigMode(): Int = runCatching {
        runBlocking { client(login()).get(MODE_PROBE_PATH).status.value }
        // The validator above turns a 404 into a thrown ApiException, so the status has to be
        // read off the exception - .getOrNull() would report "no status" and skip every time.
    }.fold({ it }, { (it as? ApiException)?.statusCode ?: -1 })

    private fun assumeRoutesPresent() =
        assumeTrue("rig proxy is in 404 mode; run this half against the real routes", rigMode() == 200)

    private fun assumeRoutesMissing() =
        assumeTrue("rig proxy is not in 404 mode; run this half with the routes suppressed", rigMode() == 404)

    // region usage hold

    @Test
    fun taggedOldServerIsNeverTouched(): Unit = runBlocking {
        val repo = files(DetectedBackend("0.8.7", BackendBuildClass.OFFICIAL, "2026-06-24"))

        val result = repo.markFilesUsed(listOf("probe-file-id"))

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(repo.supportsUsageHold()).isFalse()
        assertThat(requests.get()).isEqualTo(0)
    }

    @Test
    fun unplaceableServerIsTouched(): Unit = runBlocking {
        assumeRoutesPresent()
        val repo = files(null)

        val result = repo.markFilesUsed(listOf("probe-file-id"))

        assertThat(requests.get()).isEqualTo(1)
        // 200 proves the route is really there and really answered - not that the request was
        // merely attempted.
        assertThat(statuses).containsExactly(200)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(repo.supportsUsageHold()).isTrue()
    }

    @Test
    fun unplaceableServerLatchesAfterA404(): Unit = runBlocking {
        assumeRoutesMissing()
        val repo = files(null)

        val first = repo.markFilesUsed(listOf("probe-file-id"))
        val second = repo.markFilesUsed(listOf("probe-file-id"))

        assertThat(first).isInstanceOf(Result.Success::class.java)
        assertThat(second).isInstanceOf(Result.Success::class.java)
        assertThat(repo.supportsUsageHold()).isFalse()
        assertThat(requests.get()).isEqualTo(1)
        assertThat(statuses).containsExactly(404)
    }

    // endregion

    // region tool favorites

    @Test
    fun taggedOldServerIsNotProbedForFavorites(): Unit = runBlocking {
        val repo = favorites(DetectedBackend("0.8.7", BackendBuildClass.OFFICIAL, "2026-06-24"))

        repo.refresh()

        assertThat(repo.isSupported.value).isFalse()
        assertThat(requests.get()).isEqualTo(0)
    }

    @Test
    fun unplaceableServerIsProbedForFavorites(): Unit = runBlocking {
        assumeRoutesPresent()
        val repo = favorites(null)

        repo.refresh()

        assertThat(requests.get()).isEqualTo(1)
        assertThat(statuses).containsExactly(200)
        assertThat(repo.isSupported.value).isTrue()
    }

    @Test
    fun favoritesLatchAfterA404(): Unit = runBlocking {
        assumeRoutesMissing()
        val repo = favorites(null)

        repo.refresh()
        repo.refresh()
        repo.refresh()

        assertThat(repo.isSupported.value).isFalse()
        assertThat(requests.get()).isEqualTo(1)
        assertThat(statuses).containsExactly(404)
    }

    // endregion

    private class FakeConfig(detected: DetectedBackend?) : ConfigRepository {
        override val startupConfig = MutableStateFlow<StartupConfig?>(null)
        override val endpointConfigs = MutableStateFlow<Map<String, EndpointConfig>>(emptyMap())
        override val availableModels = MutableStateFlow<Map<String, List<String>>>(emptyMap())
        override val detectedBackendVersion: StateFlow<String?> = MutableStateFlow(detected?.version)
        override val detectedBackend: StateFlow<DetectedBackend?> = MutableStateFlow(detected)
        override suspend fun validateServerUrl(url: String) = error("unused")
        override suspend fun probeServerUrl() = error("unused")
        override suspend fun reloadForActiveServer() = Unit
        override suspend fun fetchStartupConfig() = error("unused")
        override suspend fun fetchEndpoints() = error("unused")
        override suspend fun fetchModels() = error("unused")
        override suspend fun checkBackendVersion() = error("unused")
        override suspend fun getCategories(): Result<List<Category>> = error("unused")
        override suspend fun clear() = Unit
    }

    private companion object {
        @Volatile
        private var cachedToken: String? = null
        const val MODE_PROBE_PATH = "api/user/settings/favorites/tools"
        const val BASE_URL = "http://10.0.2.2:3080/"
        const val EMAIL = "synctest@example.com"
        const val PASSWORD = "SyncTest#2026"
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
