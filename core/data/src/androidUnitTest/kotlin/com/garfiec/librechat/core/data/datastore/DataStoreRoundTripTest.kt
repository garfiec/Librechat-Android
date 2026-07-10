package com.garfiec.librechat.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.model.ModelRef
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verifies DataStore preference round-trips after KMP migration.
 * Each test writes preferences via the DataStore class, then reads
 * them back and asserts the values match.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreRoundTripTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    private fun createDataStore(name: String): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create {
            File(tmpFolder.root, "$name.preferences_pb")
        }
    }

    // A resolved account so the account-scoped stores (SettingsDataStore last_used_*, RoleCache) select
    // a keyed slot rather than reading null; server provider so ConfigCache can derive a serverId.
    private fun resolvedAccountProvider(id: String = "srv:test-account"): ActiveAccountProvider =
        InMemoryActiveAccountProvider().apply { set(AccountId(id)) }

    private val fakeServerUrlProvider = object : ServerUrlProvider {
        override fun getBaseUrl(): String = "https://chat.example.com"
    }

    private fun settingsStore(
        ds: DataStore<Preferences>,
        accountProvider: ActiveAccountProvider = resolvedAccountProvider(),
    ) = SettingsDataStore(ds, accountProvider, CoroutineScope(testDispatcher), testDispatcher)

    // --- ServerDataStore ---

    @Test
    fun serverDataStore_setAndGetUrl() = runTest(testDispatcher) {
        val ds = createDataStore("server")
        val store = ServerDataStore(
            dataStore = ds,
            appScope = CoroutineScope(testDispatcher),
            ioDispatcher = testDispatcher,
        )

        assertThat(store.getBaseUrl()).isEmpty()

        store.setServerUrl("https://chat.example.com")
        assertThat(store.getBaseUrl()).isEqualTo("https://chat.example.com")
    }

    @Test
    fun serverDataStore_trimsTrailingSlash() = runTest(testDispatcher) {
        val ds = createDataStore("server-slash")
        val store = ServerDataStore(
            dataStore = ds,
            appScope = CoroutineScope(testDispatcher),
            ioDispatcher = testDispatcher,
        )

        store.setServerUrl("https://chat.example.com/")
        assertThat(store.getBaseUrl()).isEqualTo("https://chat.example.com")
    }

    @Test
    fun serverDataStore_hasServerUrl() = runTest(testDispatcher) {
        val ds = createDataStore("server-has")
        val store = ServerDataStore(
            dataStore = ds,
            appScope = CoroutineScope(testDispatcher),
            ioDispatcher = testDispatcher,
        )

        assertThat(store.hasServerUrl().first()).isFalse()
        store.setServerUrl("https://chat.example.com")
        assertThat(store.hasServerUrl().first()).isTrue()
    }

    // --- ThemeDataStore ---

    @Test
    fun themeDataStore_defaultIsSystem() = runTest(testDispatcher) {
        val ds = createDataStore("theme")
        val store = ThemeDataStore(ds, CoroutineScope(testDispatcher), testDispatcher)

        assertThat(store.themeMode.first()).isEqualTo(ThemeMode.SYSTEM)
    }

    @Test
    fun themeDataStore_roundTrip_allModes() = runTest(testDispatcher) {
        val ds = createDataStore("theme-all")
        val store = ThemeDataStore(ds, CoroutineScope(testDispatcher), testDispatcher)

        store.setThemeMode(ThemeMode.LIGHT)
        assertThat(store.themeMode.first()).isEqualTo(ThemeMode.LIGHT)

        store.setThemeMode(ThemeMode.DARK)
        assertThat(store.themeMode.first()).isEqualTo(ThemeMode.DARK)

        store.setThemeMode(ThemeMode.SYSTEM)
        assertThat(store.themeMode.first()).isEqualTo(ThemeMode.SYSTEM)
    }

    // --- SettingsDataStore ---

    @Test
    fun settingsDataStore_defaults() = runTest(testDispatcher) {
        val ds = createDataStore("settings")
        val store = settingsStore(ds)

        assertThat(store.autoScrollEnabled.first()).isTrue()
        assertThat(store.showThinkingBlocks.first()).isTrue()
        assertThat(store.autoReadEnabled.first()).isFalse()
        assertThat(store.dismissKeyboardOnSend.first()).isTrue()
        assertThat(store.chatFontSize.first()).isEqualTo(ChatFontSize.MEDIUM)
        assertThat(store.latexRenderer.first()).isEqualTo(LatexRenderer.KATEX)
        assertThat(store.showAvatars.first()).isTrue()
        assertThat(store.showBubbles.first()).isFalse()
        assertThat(store.selectedLanguage.first()).isEqualTo("system")
        assertThat(store.sttOnDevice.first()).isTrue()
        assertThat(store.sttEndOfSpeech.first()).isFalse()
    }

    @Test
    fun settingsDataStore_roundTrip_booleans() = runTest(testDispatcher) {
        val ds = createDataStore("settings-bool")
        val store = settingsStore(ds)

        store.setAutoScrollEnabled(false)
        store.setShowThinkingBlocks(false)
        store.setAutoReadEnabled(true)
        store.setDismissKeyboardOnSend(false)
        store.setShowImageDescriptions(true)
        store.setSttOnDevice(false)
        store.setSttEndOfSpeech(true)

        assertThat(store.autoScrollEnabled.first()).isFalse()
        assertThat(store.showThinkingBlocks.first()).isFalse()
        assertThat(store.autoReadEnabled.first()).isTrue()
        assertThat(store.dismissKeyboardOnSend.first()).isFalse()
        assertThat(store.showImageDescriptions.first()).isTrue()
        assertThat(store.sttOnDevice.first()).isFalse()
        assertThat(store.sttEndOfSpeech.first()).isTrue()
    }

    @Test
    fun settingsDataStore_roundTrip_enums() = runTest(testDispatcher) {
        val ds = createDataStore("settings-enums")
        val store = settingsStore(ds)

        store.setLatexRenderer(LatexRenderer.NATIVE)
        assertThat(store.latexRenderer.first()).isEqualTo(LatexRenderer.NATIVE)

        store.setChatFontSize(ChatFontSize.LARGE)
        assertThat(store.chatFontSize.first()).isEqualTo(ChatFontSize.LARGE)

        store.setChatFontSize(ChatFontSize.SMALL)
        assertThat(store.chatFontSize.first()).isEqualTo(ChatFontSize.SMALL)
    }

    @Test
    fun settingsDataStore_roundTrip_strings() = runTest(testDispatcher) {
        val ds = createDataStore("settings-strings")
        val store = settingsStore(ds)

        store.setLastUsedModel("anthropic", "claude-3.5-sonnet")
        assertThat(store.lastUsedEndpoint.first()).isEqualTo("anthropic")
        assertThat(store.lastUsedModel.first()).isEqualTo("claude-3.5-sonnet")

        store.setSelectedVoiceId("voice-en-us-001")
        assertThat(store.selectedVoiceId.first()).isEqualTo("voice-en-us-001")

        store.setSelectedLanguage("zh-Hans")
        assertThat(store.selectedLanguage.first()).isEqualTo("zh-Hans")
    }

    @Test
    fun settingsDataStore_lastUsed_suppressedWhileWarming_thenEmitsOnResolve() = runTest(testDispatcher) {
        val ds = createDataStore("settings-lastused-warming")
        val provider = InMemoryActiveAccountProvider() // starts Warming
        val store = SettingsDataStore(ds, provider, CoroutineScope(testDispatcher), testDispatcher)

        val emissions = mutableListOf<String?>()
        val job = launch { store.lastUsedModel.collect { emissions.add(it) } }
        advanceUntilIdle()
        // While the account is Warming the flow is suppressed (no null emission that a seeder would
        // mistake for "no last-used saved").
        assertThat(emissions).isEmpty()

        provider.set(AccountId("srv:acctX"))
        store.setLastUsedModel("openAI", "gpt-4o")
        advanceUntilIdle()
        assertThat(emissions.last()).isEqualTo("gpt-4o")

        job.cancel()
    }

    // --- SettingsDataStore: model usage ranking (home-screen shortcuts) ---

    @Test
    fun settingsDataStore_modelUsage_ranksByCountDescending() = runTest(testDispatcher) {
        val ds = createDataStore("settings-usage-rank")
        val store = settingsStore(ds)

        repeat(3) { store.incrementModelUsage("openAI", "gpt-4o") }
        store.incrementModelUsage("anthropic", "claude-3.5-sonnet")
        repeat(2) { store.incrementModelUsage("openAI", "gpt-4o-mini") }

        assertThat(store.topUsedModels(10).first())
            .containsExactly(
                ModelRef("openAI", "gpt-4o"),
                ModelRef("openAI", "gpt-4o-mini"),
                ModelRef("anthropic", "claude-3.5-sonnet"),
            )
            .inOrder()
    }

    @Test
    fun settingsDataStore_modelUsage_tieBrokenByRecency() = runTest(testDispatcher) {
        val ds = createDataStore("settings-usage-tie")
        val store = settingsStore(ds)

        store.incrementModelUsage("openAI", "gpt-4o")
        store.incrementModelUsage("anthropic", "claude-3.5-sonnet")

        // Equal counts → the more recently used ranks first.
        assertThat(store.topUsedModels(10).first())
            .containsExactly(
                ModelRef("anthropic", "claude-3.5-sonnet"),
                ModelRef("openAI", "gpt-4o"),
            )
            .inOrder()
    }

    @Test
    fun settingsDataStore_modelUsage_respectsLimit() = runTest(testDispatcher) {
        val ds = createDataStore("settings-usage-limit")
        val store = settingsStore(ds)

        repeat(3) { store.incrementModelUsage("openAI", "gpt-4o") }
        repeat(2) { store.incrementModelUsage("openAI", "gpt-4o-mini") }
        store.incrementModelUsage("anthropic", "claude-3.5-sonnet")

        assertThat(store.topUsedModels(2).first())
            .containsExactly(
                ModelRef("openAI", "gpt-4o"),
                ModelRef("openAI", "gpt-4o-mini"),
            )
            .inOrder()
    }

    @Test
    fun settingsDataStore_modelUsage_customEndpointWithSpaceRoundTrips() = runTest(testDispatcher) {
        val ds = createDataStore("settings-usage-space")
        val store = settingsStore(ds)

        // The composite storage key must survive endpoints/models containing spaces.
        store.incrementModelUsage("My Custom Endpoint", "some model v1")

        assertThat(store.topUsedModels(5).first())
            .containsExactly(ModelRef("My Custom Endpoint", "some model v1"))
    }

    @Test
    fun settingsDataStore_modelUsage_blankInputsIgnored() = runTest(testDispatcher) {
        val ds = createDataStore("settings-usage-blank")
        val store = settingsStore(ds)

        store.incrementModelUsage("", "gpt-4o")
        store.incrementModelUsage("openAI", "")

        assertThat(store.topUsedModels(5).first()).isEmpty()
    }

    @Test
    fun settingsDataStore_modelUsage_recordedWhileWarming_landsOnResolve() = runTest(testDispatcher) {
        val ds = createDataStore("settings-usage-warming")
        val provider = InMemoryActiveAccountProvider() // starts Warming (mirrors a fresh post-login send)
        val store = SettingsDataStore(ds, provider, CoroutineScope(testDispatcher), testDispatcher)

        // The very first send can fire before the account has re-homed. The write must await
        // resolution rather than snapshot a null id and silently drop the usage tick.
        val recorded = launch { store.incrementModelUsage("anthropic", "claude-sonnet-5") }
        advanceUntilIdle()
        assertThat(recorded.isActive).isTrue() // still awaiting resolution, not dropped

        provider.set(AccountId("srv:acctY"))
        recorded.join() // resolution unblocks the awaited write; join past the DataStore commit

        assertThat(store.topUsedModels(5).first())
            .containsExactly(ModelRef("anthropic", "claude-sonnet-5"))
    }

    @Test
    fun settingsDataStore_roundTrip_floats() = runTest(testDispatcher) {
        val ds = createDataStore("settings-floats")
        val store = settingsStore(ds)

        store.setTtsSpeechRate(1.5f)
        store.setTtsPitch(0.8f)
        assertThat(store.ttsSpeechRate.first()).isEqualTo(1.5f)
        assertThat(store.ttsPitch.first()).isEqualTo(0.8f)
    }

    @Test
    fun settingsDataStore_roundTrip_mcpServers() = runTest(testDispatcher) {
        val ds = createDataStore("settings-mcp")
        val store = settingsStore(ds)

        store.setSelectedMcpServers(setOf("server-a", "server-b"))
        assertThat(store.selectedMcpServers.first()).containsExactly("server-a", "server-b")

        store.setSelectedMcpServers(emptySet())
        assertThat(store.selectedMcpServers.first()).isEmpty()
    }

    // --- ConfigCacheDataStore ---

    @Test
    fun configCacheDataStore_roundTrip_availableModels() = runTest(testDispatcher) {
        val ds = createDataStore("config-cache")
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val store = ConfigCacheDataStore(ds, json, fakeServerUrlProvider)

        val models = mapOf(
            "openAI" to listOf("gpt-4o", "gpt-4o-mini"),
            "anthropic" to listOf("claude-3.5-sonnet", "claude-3-haiku"),
        )

        store.saveAvailableModels(models)
        val loaded = store.loadAvailableModels()

        assertThat(loaded).isNotNull()
        assertThat(loaded!!["openAI"]).containsExactly("gpt-4o", "gpt-4o-mini")
        assertThat(loaded["anthropic"]).containsExactly("claude-3.5-sonnet", "claude-3-haiku")
    }

    @Test
    fun configCacheDataStore_returnsNullWhenEmpty() = runTest(testDispatcher) {
        val ds = createDataStore("config-empty")
        val json = Json { ignoreUnknownKeys = true }
        val store = ConfigCacheDataStore(ds, json, fakeServerUrlProvider)

        assertThat(store.loadStartupConfig()).isNull()
        assertThat(store.loadEndpointConfigs()).isNull()
        assertThat(store.loadAvailableModels()).isNull()
    }
}
