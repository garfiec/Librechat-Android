package com.garfiec.librechat.core.logging

import com.garfiec.librechat.core.common.AppInfo
import com.garfiec.librechat.core.common.BackendVersion

/**
 * Emits a single structured "startup header" record at process start so every diagnostic export
 * begins with the build + device context needed to triage the records that follow. This is the
 * one place that snapshots app version, git SHA, OS, and device model into the log.
 *
 * Privacy: contains only build metadata and coarse, non-identifying device facts. NEVER add the
 * server URL, account, token, or any user data here — exports may be shared in bug reports.
 *
 * [supportedBackendVersion] is a build-time constant, so it's stamped here immediately. The
 * *detected* server version isn't known until `/api/config` is fetched; that comparison lives in
 * the redacted `ServerConfig` snapshot (see `ConfigRepositoryImpl.logConfigSnapshot`), which
 * carries both the resolved detected version and this supported version.
 */
fun logStartupHeader(
    appInfo: AppInfo,
    platformInfo: PlatformInfo,
    supportedBackendVersion: String = BackendVersion.SUPPORTED_BACKEND_VERSION,
) {
    // Defensive: the startup path must never crash the app on a logging failure.
    runCatching {
        Diag.i(
            tag = "Startup",
            origin = LogOrigin.CLIENT,
            attrs = buildMap {
                put("versionName", appInfo.versionName)
                put("versionCode", appInfo.versionCode.toString())
                put("gitSha", appInfo.gitSha)
                put("osName", platformInfo.osName)
                put("osVersion", platformInfo.osVersion)
                put("deviceModel", platformInfo.deviceModel)
                put("supportedBackendVersion", supportedBackendVersion)
            },
        ) { "app startup" }
    }
}
