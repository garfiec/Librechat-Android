package com.garfiec.librechat.core.logging

import android.os.Build

internal class AndroidPlatformInfo : PlatformInfo {
    override val osName: String = "Android"
    override val osVersion: String = Build.VERSION.RELEASE ?: "unknown"
    override val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}"
}
