package com.garfiec.librechat.core.data.datastore

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosTokenDataStore(
    refreshClient: Lazy<HttpClient>,
) : CommonTokenDataStore(refreshClient), ServerUrlKeychainFallback {

    init {
        initializeTokenCache()
    }

    override fun readValue(key: String): String? = keychainGet(key)

    override fun writeValue(key: String, value: String) = keychainSet(key, value)

    // Keychain has no batch API; writes are sequential (as the prior writeTokens already was).
    override fun writeValues(values: Map<String, String>) {
        values.forEach { (key, value) -> keychainSet(key, value) }
    }

    override fun removeValue(key: String) = keychainDelete(key)

    override fun readServerUrl(): String? = keychainGet(KEY_SERVER_URL)

    override fun writeServerUrl(url: String) {
        if (url.isBlank()) {
            keychainDelete(KEY_SERVER_URL)
        } else {
            keychainSet(KEY_SERVER_URL, url)
        }
    }

    override fun removeServerUrl() {
        keychainDelete(KEY_SERVER_URL)
    }

    override fun onKeystoreCorruption() {
        // Keychain access rarely "corrupts" like the Android keystore (isKeystoreException stays false
        // on iOS, so this isn't reached); clear the bare token slots defensively if it ever is.
        keychainDelete(KEY_ACCESS_TOKEN)
        keychainDelete(KEY_REFRESH_TOKEN)
    }

    // ---- Keychain helpers using CFDictionary directly ----
    // Prior implementation bridged a Kotlin Map<Any?,Any?> → NSDictionary, but
    // CFStringRef keys (kSecClass, etc.) are raw CPointers that don't survive
    // the Kotlin→ObjC bridging. Building a CFMutableDictionary with
    // CFDictionaryAddValue avoids that problem entirely.

    @OptIn(ExperimentalForeignApi::class)
    private fun keychainGet(key: String): String? {
        val cfService = CFBridgingRetain(SERVICE_NAME as NSString)
        val cfKey = CFBridgingRetain(key as NSString)
        val query = CFDictionaryCreateMutable(null, 5, null, null) ?: return null
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, cfService)
            CFDictionaryAddValue(query, kSecAttrAccount, cfKey)
            CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)

            memScoped {
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                if (status == errSecSuccess) {
                    val data = CFBridgingRelease(result.value) as? NSData ?: return null
                    return NSString.create(data = data, encoding = NSUTF8StringEncoding) as? String
                }
            }
        } finally {
            CFRelease(query)
            cfService?.let { CFRelease(it) }
            cfKey?.let { CFRelease(it) }
        }
        return null
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun keychainSet(key: String, value: String) {
        keychainDelete(key)
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: run {
            Logger.e { "keychainSet($key): dataUsingEncoding returned null" }
            return
        }
        val cfService = CFBridgingRetain(SERVICE_NAME as NSString)
        val cfKey = CFBridgingRetain(key as NSString)
        val cfData = CFBridgingRetain(data)
        val query = CFDictionaryCreateMutable(null, 5, null, null) ?: return
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, cfService)
            CFDictionaryAddValue(query, kSecAttrAccount, cfKey)
            CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
            CFDictionaryAddValue(query, kSecValueData, cfData)

            val status = SecItemAdd(query, null)
            if (status != errSecSuccess) {
                Logger.w { "keychainSet($key): SecItemAdd failed with status=$status" }
            }
        } finally {
            CFRelease(query)
            cfService?.let { CFRelease(it) }
            cfKey?.let { CFRelease(it) }
            cfData?.let { CFRelease(it) }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun keychainDelete(key: String) {
        val cfService = CFBridgingRetain(SERVICE_NAME as NSString)
        val cfKey = CFBridgingRetain(key as NSString)
        val query = CFDictionaryCreateMutable(null, 3, null, null) ?: return
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, cfService)
            CFDictionaryAddValue(query, kSecAttrAccount, cfKey)

            SecItemDelete(query)
        } finally {
            CFRelease(query)
            cfService?.let { CFRelease(it) }
            cfKey?.let { CFRelease(it) }
        }
    }

    companion object {
        private const val SERVICE_NAME = "com.garfiec.librechat.tokens"
        private const val KEY_SERVER_URL = "server_url"
    }
}
