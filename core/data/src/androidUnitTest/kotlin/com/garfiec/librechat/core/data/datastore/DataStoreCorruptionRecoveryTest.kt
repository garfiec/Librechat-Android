package com.garfiec.librechat.core.data.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.garfiec.librechat.core.data.di.settingsCorruptionHandler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Locks the shared [settingsCorruptionHandler] wired into both platform DI modules
 * (`DataPlatformModule.android.kt` / `.ios.kt`): a corrupt settings file must heal to empty
 * preferences instead of throwing `CorruptionException` on every read forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreCorruptionRecoveryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val key = stringPreferencesKey("k")

    @Test
    fun corruptFile_healsToEmpty_thenRoundTrips() = runTest {
        val file = File(tmpFolder.root, "corrupt.preferences_pb")
        // Non-empty garbage: the preferences serializer fails to parse it as a protobuf and raises
        // CorruptionException (an empty file, by contrast, is a valid empty store — not corrupt).
        file.writeBytes("not a valid preferences proto".encodeToByteArray())

        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = settingsCorruptionHandler(),
        ) { file }

        // Without the handler this first read throws; instead it heals the file to empty prefs.
        assertThat(store.data.first()[key]).isNull()

        // And the healed store is fully writable + readable afterwards.
        store.edit { it[key] = "value" }
        assertThat(store.data.first()[key]).isEqualTo("value")
    }
}
