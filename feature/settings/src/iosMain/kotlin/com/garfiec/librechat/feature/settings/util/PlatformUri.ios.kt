package com.garfiec.librechat.feature.settings.util

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun openUri(uri: String): Boolean {
    val url = NSURL.URLWithString(uri) ?: return false
    val app = UIApplication.sharedApplication
    if (!app.canOpenURL(url)) return false
    app.openURL(url)
    return true
}
