package com.garfiec.librechat.core.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile

class TokenDataStore(
    context: Context,
    refreshClient: Lazy<HttpClient>,
) : CommonTokenDataStore(refreshClient) {

    private val appContext: Context = context.applicationContext

    /**
     * The encrypted store, or `null` once the device keystore is so broken that even a wipe + rebuild
     * can't produce a working one. A `null` routes every override onto [memoryFallback] — the session
     * lives only in memory until process death, then the user re-logs in — instead of crashing during
     * `startKoin`. Keystore/keyset corruption (backup-restore, OS update, broken OEM keystore) would
     * otherwise throw here and, via the eager `AccountRegistry` single, take down every launch.
     *
     * Reassigned at runtime (never back to a store known-broken) when a write rebuilds the keyset or
     * drops to memory mode; `@Volatile` so the unlocked hot-path reads (`isAuthenticated`,
     * `getAccessToken`) observe the swap.
     */
    @Volatile
    private var prefs: SharedPreferences? = createEncryptedPrefsWithRecovery()

    /**
     * Session-only backing used when [prefs] is `null` (the keystore never recovered). Reads/writes go
     * here instead; nothing survives process death, so the user re-authenticates each launch — a
     * degraded but usable app rather than a crash loop.
     */
    private val memoryFallback = ConcurrentHashMap<String, String>()

    init {
        initializeTokenCache()
    }

    override fun readValue(key: String): String? {
        val store = prefs ?: return memoryFallback[key]
        return try {
            store.getString(key, null)
        } catch (e: GeneralSecurityException) {
            // Keyset-level failure (broken master key / keyset — KeyStoreException is a subtype): every
            // entry is now undecryptable, so piecemeal-dropping just this key would leave the rest
            // throwing until some later write happens to rebuild. Rebuild the keyset here (fresh, empty
            // store) so subsequent reads and writes work again — mirroring the write path. The tokens are
            // unrecoverable either way; the user re-logs in.
            Logger.e(e) { "Token read hit a broken keyset — rebuilding the encrypted store" }
            rebuildStore()
            null
        } catch (e: SecurityException) {
            // One entry's ciphertext won't decrypt while the keyset itself is intact (writes still
            // succeed). Drop ONLY this entry so it stops throwing, and leave every other slot alone: a
            // whole-store rebuild here would log every retained account out over a single bad value.
            Logger.e(e) { "Token read failed to decrypt one entry — dropping it and returning empty" }
            runCatching { store.edit().remove(key).apply() }
            null
        }
    }

    override fun writeValue(key: String, value: String) =
        write(toMemory = { memoryFallback[key] = value }) { editor -> editor.putString(key, value) }

    override fun writeValues(values: Map<String, String>) =
        write(toMemory = { memoryFallback.putAll(values) }) { editor ->
            values.forEach { (key, value) -> editor.putString(key, value) }
        }

    override fun removeValue(key: String) =
        write(toMemory = { memoryFallback.remove(key) }) { editor -> editor.remove(key) }

    override fun onKeystoreCorruption() {
        // Called by the base refresh path when a keystore exception escaped a POST. The keyset is
        // unusable, so rebuild it (fresh master key + keyset) rather than clear entries against a
        // broken key; per-slot cleanup is the caller's job (invalidateRefresh). A rebuild that can't
        // produce a working store leaves [prefs] null (memory mode) — either way, best-effort and
        // never re-throwing.
        rebuildStore()
        memoryFallback.clear()
    }

    // The base classifies the refresh POST path with this narrow predicate (KeyStoreException only) on
    // purpose: a bare SecurityException escaping a POST can be a TLS fault that must NOT be treated as
    // a keystore logout. Storage ops use the broader [isCorruption] instead, and recover in place, so
    // decrypt/encrypt failures never reach this classifier.
    override fun isKeystoreException(e: Exception): Boolean = e is KeyStoreException

    /**
     * Apply an editor mutation, recovering from a corrupt keystore instead of crashing. An escaping
     * keystore throw matters because `setTokens`/`commitRefresh` run inside `safeApiCall`, which only
     * catches network/serialization types — the throw would otherwise reach the ViewModel scope. An
     * encrypt failure means the shared value key is unusable, so rebuild the store once (fresh keyset)
     * and retry; if the rebuild can't produce a working store, switch to [memoryFallback] for the rest
     * of the process. Either way the write lands somewhere — durable or in memory — so a caller like
     * `commitRefresh` never reports a persisted token it silently dropped. Non-keystore exceptions are
     * real bugs and re-thrown.
     */
    private inline fun write(toMemory: () -> Unit, mutate: (SharedPreferences.Editor) -> Unit) {
        val store = prefs
        if (store == null) {
            toMemory()
            return
        }
        try {
            store.edit().also(mutate).apply()
        } catch (e: Exception) {
            if (!isCorruption(e)) throw e
            Logger.e(e) { "Token write failed on a corrupt keystore — rebuilding the encrypted store" }
            val rebuilt = rebuildStore()
            if (rebuilt == null) {
                toMemory()
                return
            }
            try {
                rebuilt.edit().also(mutate).apply()
            } catch (retry: Exception) {
                if (!isCorruption(retry)) throw retry
                Logger.e(retry) { "Token write still failing after rebuild — using in-memory fallback" }
                prefs = null
                toMemory()
            }
        }
    }

    // EncryptedSharedPreferences throws SecurityException ("Could not decrypt value…") on a bad entry
    // and GeneralSecurityException / KeyStoreException (a subtype) on a broken keyset.
    private fun isCorruption(e: Exception): Boolean = e is SecurityException || e is GeneralSecurityException

    /** Wipe the corrupt keystore state and recreate the store, publishing the result to [prefs]. */
    private fun rebuildStore(): SharedPreferences? = createEncryptedPrefsWithRecovery().also { prefs = it }

    private fun createEncryptedPrefsWithRecovery(): SharedPreferences? = createWithRecovery(
        create = { createEncryptedPrefs(appContext) },
        wipe = { wipeEncryptedPrefs(appContext) },
    )

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun wipeEncryptedPrefs(context: Context) {
        // security-crypto keeps the Tink keysets INSIDE this same prefs file, so deleting it drops the
        // corrupt keysets along with the data. Also delete the master key entry so a corrupt /
        // inaccessible master key is regenerated on the retry. Best-effort on the keystore step — the
        // prefs deletion has already happened, so the retry can still succeed even if this throws.
        context.deleteSharedPreferences(PREFS_NAME)
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            Logger.e(e) { "Failed to delete master key entry during token store recovery" }
        }
    }

    companion object {
        private const val PREFS_NAME = "librechat_tokens"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
