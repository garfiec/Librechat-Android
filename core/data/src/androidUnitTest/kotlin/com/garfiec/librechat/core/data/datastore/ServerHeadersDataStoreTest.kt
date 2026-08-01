package com.garfiec.librechat.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Persistence contract for per-server gateway headers (issue #287).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerHeadersDataStoreTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    private val server = "https://chat.example.com"
    private val other = "https://other.example.com"
    private val headers = mapOf("CF-Access-Client-Id" to "id", "CF-Access-Client-Secret" to "secret")

    private fun createDataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { File(tmpFolder.root, "$name.preferences_pb") }

    private fun store(ds: DataStore<Preferences>) =
        ServerHeadersDataStore(ds, json, CoroutineScope(testDispatcher), testDispatcher)

    @Test
    fun `headers written are visible to the very next read with no restart`() = runTest(testDispatcher) {
        // ServerUrlViewModel saves and then immediately probes the server. A snapshot cache would make
        // the first Connect after entering a token fail and the second succeed — which reads as
        // flakiness, not as a bug anyone can report.
        val subject = store(createDataStore("headers-live"))
        subject.awaitWarm()

        subject.setHeaders(server, headers)

        assertThat(subject.headersFor(server)).isEqualTo(headers)
    }

    @Test
    fun `headers are scoped per server`() = runTest(testDispatcher) {
        val subject = store(createDataStore("headers-scope"))
        subject.setHeaders(server, headers)

        assertThat(subject.headersFor(other)).isEmpty()
    }

    @Test
    fun `url variants of the same deployment resolve to the same headers`() = runTest(testDispatcher) {
        // beginAdd pins its URL with trimTrailingSlash(), not normalizeServerUrl — so the strings
        // genuinely differ between call sites while the deployment is the same. Everything must derive.
        val subject = store(createDataStore("headers-normalize"))
        subject.setHeaders("https://chat.example.com/", headers)

        assertThat(subject.headersFor("https://Chat.Example.com:443")).isEqualTo(headers)
    }

    @Test
    fun `a blank or unparseable base URL yields empty rather than throwing`() = runTest(testDispatcher) {
        // Routine, not exotic: cold start before warm-up, and ServerUrlViewModel setting
        // setServerUrl("") after every failed probe. Both normalizeServerUrl and ServerId throw on
        // these, and this runs on the request path where a throw surfaces as a network failure.
        val subject = store(createDataStore("headers-blank"))
        subject.setHeaders(server, headers)

        assertThat(subject.headersFor("")).isEmpty()
        assertThat(subject.headersFor("   ")).isEmpty()
        assertThat(subject.headersFor("ftp://nope")).isEmpty()
        assertThat(subject.headersFor("https://")).isEmpty()
    }

    @Test
    fun `an empty map removes the entry`() = runTest(testDispatcher) {
        val subject = store(createDataStore("headers-clear"))
        subject.setHeaders(server, headers)
        subject.setHeaders(server, emptyMap())

        assertThat(subject.headersFor(server)).isEmpty()
    }

    @Test
    fun `reserved and malformed pairs never survive a round trip`() = runTest(testDispatcher) {
        val subject = store(createDataStore("headers-sanitize"))
        subject.setHeaders(
            server,
            mapOf(
                "CF-Access-Client-Id" to "keep",
                "Authorization" to "Basic nope",
                "User-Agent" to "curl/8",
            ),
        )

        assertThat(subject.headersFor(server)).containsExactly("CF-Access-Client-Id", "keep")
    }

    @Test
    fun `headers survive a new store instance over the same preferences`() = runTest(testDispatcher) {
        // Logging out must not cost the user their gateway credential — it is what lets them log
        // back IN. Nothing sweeps the srv: namespace, so this is really asserting that absence.
        val ds = createDataStore("headers-persist")
        store(ds).setHeaders(server, headers)

        val reopened = store(ds)
        reopened.awaitWarm()

        assertThat(reopened.headersFor(server)).isEqualTo(headers)
    }
}
