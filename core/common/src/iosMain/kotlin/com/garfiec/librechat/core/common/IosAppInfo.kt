package com.garfiec.librechat.core.common

import platform.Foundation.NSBundle

internal class IosAppInfo : AppInfo {
    private val bundle = NSBundle.mainBundle

    override val versionName: String =
        bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "unknown"

    override val versionCode: Long =
        (bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toLongOrNull() ?: 0L

    // Reads the Info.plist "GitSHA" key stamped by the "Stamp Git SHA" Xcode build phase
    // (git rev-parse --short=8). Falls back to "unknown" if absent. Android bakes it via BuildConfig.
    override val gitSha: String =
        bundle.objectForInfoDictionaryKey("GitSHA") as? String ?: "unknown"
}
