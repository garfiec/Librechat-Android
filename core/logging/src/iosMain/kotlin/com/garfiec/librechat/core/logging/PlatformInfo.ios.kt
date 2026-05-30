package com.garfiec.librechat.core.logging

import platform.UIKit.UIDevice

internal class IosPlatformInfo : PlatformInfo {
    private val device = UIDevice.currentDevice

    // systemName/systemVersion/model are members of the main UIDevice interface (not an
    // Objective-C category), so they are accessed directly as properties without an
    // individual member import. Each is guarded so a null from UIKit never propagates.
    override val osName: String = device.systemName ?: "iOS"
    override val osVersion: String = device.systemVersion ?: "unknown"
    override val deviceModel: String = device.model ?: "unknown"
}
