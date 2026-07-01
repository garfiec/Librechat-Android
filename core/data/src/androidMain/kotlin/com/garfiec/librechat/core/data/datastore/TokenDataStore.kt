package com.garfiec.librechat.core.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.ktor.client.HttpClient
import java.security.KeyStoreException

class TokenDataStore(
    context: Context,
    refreshClient: Lazy<HttpClient>,
) : CommonTokenDataStore(refreshClient) {

    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    init {
        initializeTokenCache()
    }

    override fun readValue(key: String): String? = prefs.getString(key, null)

    override fun writeValue(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun writeValues(values: Map<String, String>) {
        val editor = prefs.edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
    }

    override fun removeValue(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun onKeystoreCorruption() {
        // The encrypted store is unreadable; wipe it wholesale so a fresh master key can back new writes.
        prefs.edit().clear().apply()
    }

    override fun isKeystoreException(e: Exception): Boolean = e is KeyStoreException

    companion object {
        private const val PREFS_NAME = "librechat_tokens"
    }
}
