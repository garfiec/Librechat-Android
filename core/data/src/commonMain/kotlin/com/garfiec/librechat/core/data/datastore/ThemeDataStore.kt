package com.garfiec.librechat.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

class ThemeDataStore(
    private val dataStore: DataStore<Preferences>,
    appScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Initial value seeded for the first compose frame. Warmed up asynchronously off the
     * Main thread (Koin instantiates this singleton on Main at startup, so the read must not
     * block). Until the warm-up resolves it stays [ThemeMode.SYSTEM]; callers gate themed
     * content on [isReady] so the persisted value is in place before the first frame draws.
     */
    @Volatile
    var initialThemeMode: ThemeMode = ThemeMode.SYSTEM
        private set

    private val _isReady = MutableStateFlow(false)

    /**
     * Flips true once the async warm-up has resolved [initialThemeMode] (success, failure, or
     * cancellation). The root composable holds off drawing themed content until this is true so
     * a dark-mode user on a light-system device never sees a first-frame flash of the wrong theme.
     */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        appScope.launch(ioDispatcher) {
            try {
                initialThemeMode = dataStore.data.map { prefs -> prefs[KEY_THEME].toThemeMode() }.first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the default; the themeMode flow still drives the live UI value.
            } finally {
                _isReady.value = true
            }
        }
    }

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[KEY_THEME].toThemeMode()
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME] = when (mode) {
                ThemeMode.SYSTEM -> "system"
                ThemeMode.LIGHT -> "light"
                ThemeMode.DARK -> "dark"
            }
        }
    }

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme_mode")

        private fun String?.toThemeMode(): ThemeMode = when (this) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }
}
