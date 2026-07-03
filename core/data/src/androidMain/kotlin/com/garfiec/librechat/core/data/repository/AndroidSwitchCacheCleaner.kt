package com.garfiec.librechat.core.data.repository

import android.webkit.CookieManager
import android.webkit.WebStorage
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android switch-cache clear: WebView storage (localStorage/IndexedDB behind inline artifacts) and
 * the process-global WebView cookie jar. The jar holds the OAuth `refreshToken` cookie for every
 * server the user has OAuth'd against — per-URL clearing would leave the outgoing account's cookie
 * live (it would be read against the *current* URL), so clear it whole; an interactive OAuth
 * re-issues on demand. WebView instances themselves die with their composables when the switch pops
 * the back stack. Best-effort: a clear failure must never abort the switch itself.
 */
class AndroidSwitchCacheCleaner : SwitchCacheCleaner {
    override suspend fun clearOnSwitch() {
        runCatching {
            withContext(Dispatchers.Main) {
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    flush()
                }
            }
        }.onFailure { Logger.w(it) { "Switch cache clear failed" } }
    }
}
