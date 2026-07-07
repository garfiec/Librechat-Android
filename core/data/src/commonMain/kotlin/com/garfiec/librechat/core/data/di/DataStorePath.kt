package com.garfiec.librechat.core.data.di

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import co.touchlab.kermit.Logger

/**
 * The base name used for the DataStore preferences file on all platforms.
 */
internal const val DATASTORE_FILE_NAME = "librechat_settings"

/**
 * Shared corruption handler for the settings DataStore. A corrupt preferences file (interrupted write,
 * backup-restore) would otherwise throw `CorruptionException` on every read/edit forever — permanently
 * bricking a startup that reads it eagerly. Heal by replacing with empty prefs: the user re-onboards /
 * re-logs in (nothing recoverable is lost; a corrupt file is unreadable anyway; on iOS the server URL
 * restores from the keychain fallback in `ServerDataStore`). One definition so both platforms' DI
 * modules can't drift apart.
 */
internal fun settingsCorruptionHandler(): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { e ->
        Logger.e(e) { "Settings DataStore corrupted — replacing with empty preferences" }
        emptyPreferences()
    }
