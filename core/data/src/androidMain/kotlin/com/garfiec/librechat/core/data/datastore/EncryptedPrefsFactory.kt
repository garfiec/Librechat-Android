package com.garfiec.librechat.core.data.datastore

import co.touchlab.kermit.Logger

/**
 * Build a value that depends on the Android keystore / `EncryptedSharedPreferences`, recovering from a
 * corrupt keystore or keyset instead of letting the exception escape `startKoin` into an
 * unrecoverable crash loop.
 *
 * [create] is attempted; on any [Exception] (a broken OEM keystore, or a keyset that no longer
 * decrypts after a backup-restore / OS update — these surface as `GeneralSecurityException`,
 * `IOException` or `ProviderException`, all [Exception] subtypes) the corrupt state is wiped via
 * [wipe] and [create] is retried once. A second failure returns `null`, signalling the caller to fall
 * back to a non-persistent store rather than crash.
 *
 * Catches [Exception], not [Throwable]: an `OutOfMemoryError` / `LinkageError` is a genuine failure
 * that must not be swallowed. A [wipe] that itself throws is logged and ignored so it can never mask
 * the retry.
 *
 * Extracted as a free function so the recovery logic is unit-testable with plain fakes — Robolectric
 * can't run a real `EncryptedSharedPreferences`.
 */
internal fun <T : Any> createWithRecovery(create: () -> T, wipe: () -> Unit): T? =
    try {
        create()
    } catch (e: Exception) {
        Logger.e(e) { "Encrypted store creation failed — wiping corrupt keystore state and retrying" }
        try {
            wipe()
        } catch (wipeError: Exception) {
            Logger.e(wipeError) { "Wipe of corrupt encrypted store failed; retrying creation anyway" }
        }
        try {
            create()
        } catch (retryError: Exception) {
            Logger.e(retryError) {
                "Encrypted store creation failed after wipe — falling back to in-memory session store"
            }
            null
        }
    }
