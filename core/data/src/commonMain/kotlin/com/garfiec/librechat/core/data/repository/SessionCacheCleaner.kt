package com.garfiec.librechat.core.data.repository

interface SessionCacheCleaner {
    /**
     * Clears the account-blind file caches (image / artifact / shared-file dirs under the platform
     * cache root). Account-scoped state — role permissions, keyed tokens, `acct:`-scoped prefs — is
     * reaped separately by the account teardown (`AccountScopedPrefsPurger` + the token store), so
     * this cleaner is deliberately account-blind and never touches a live account's permissions.
     */
    fun clearFileCaches()
}
