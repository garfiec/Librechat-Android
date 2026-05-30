package com.garfiec.librechat.core.logging.io

/**
 * Resolves the platform-specific directory the diagnostic log buffer lives in.
 *
 * Android: `filesDir/diag_logs` (app-private, persists across restarts, not OS-evictable like cache).
 * iOS: `Library/Application Support/diag_logs` (excluded from iCloud backup).
 */
interface LogDirProvider {
    fun logDir(): String
}
