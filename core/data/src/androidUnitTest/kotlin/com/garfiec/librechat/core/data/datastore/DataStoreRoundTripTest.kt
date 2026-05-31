package com.garfiec.librechat.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        val store = SettingsDataStore(ds)

        assertThat(store.autoScrollEnabled.first()).isTrue()
        assertThat(store.showThinkingBlocks.first()).isTrue()
        assertThat(store.autoReadEnabled.first()).isFalse()
        assertThat(store.dismissKeyboardOnSend.first()).isTrue()
        assertThat(store.chatFontSize.first()).isEqualTo(ChatFontSize.MEDIUM)
        assertThat(store.latexRenderer.first()).isEqualTo(LatexRenderer.KATEX)
        assertThat(store.showAvatars.first()).isTrue()
        assertThat(store.showBubbles.first()).isFalse()
    }

    @Test
    fun settingsDataStore_roundTrip_booleans() = runTest(testDispatcher) {
        val ds = createDataStore("settings-bool")
        val store = SettingsDataStore(ds)

        store.setAutoScrollEnabled(false)
        store.setShowThinkingBlocks(false)
        store.setAutoReadEnabled(true)
        store.setDismissKeyboardOnSend(false)
        store.setShowImageDescriptions(true)

        assertThat(store.autoScrollEnabled.first()).isFalse()
        assertThat(store.showThinkingBlocks.first()).isFalse()
        assertThat(store.autoReadEnabled.first()).isTrue()
        assertThat(store.dismissKeyboardOnSend.first()).isFalse()
        assertThat(store.showImageDescriptions.first()).isTrue()
    }

    @Test
    fun settingsDataStore_roundTrip_enums() = runTest(testDispatcher) {
        val ds = createDataStore("settings-enums")
        val store = SettingsDataStore(ds)

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
        val store = SettingsDataStore(ds)

        store.setLastUsedModel("anthropic", "claude-3.5-sonnet")
        assertThat(store.lastUsedEndpoint.first()).isEqualTo("anthropic")
        assertThat(store.lastUsedModel.first()).isEqualTo("claude-3.5-sonnet")

        store.setSelectedVoiceId("voice-en-us-001")
        assertThat(store.selectedVoiceId.first()).isEqualTo("voice-en-us-001")
    }

    @Test
    fun settingsDataStore_roundTrip_floats() = runTest(testDispatcher) {
        val ds = createDataStore("settings-floats")
        val store = SettingsDataStore(ds)

        store.setTtsSpeechRate(1.5f)
        store.setTtsPitch(0.8f)
        assertThat(store.ttsSpeechRate.first()).isEqualTo(1.5f)
        assertThat(store.ttsPitch.first()).isEqualTo(0.8f)
    }

    @Test
    fun settingsDataStore_roundTrip_mcpServers() = runTest(testDispatcher) {
        val ds = createDataStore("settings-mcp")
        val store = SettingsDataStore(ds)

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
        val store = ConfigCacheDataStore(ds, json)

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
        val store = ConfigCacheDataStore(ds, json)

        assertThat(store.loadStartupConfig()).isNull()
        assertThat(store.loadEndpointConfigs()).isNull()
        assertThat(store.loadAvailableModels()).isNull()
    }
}
