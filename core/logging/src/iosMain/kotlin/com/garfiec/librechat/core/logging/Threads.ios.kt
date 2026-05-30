package com.garfiec.librechat.core.logging

import platform.Foundation.NSThread

internal actual fun currentThreadName(): String = runCatching {
    val current = NSThread.currentThread
    current.name?.takeIf { it.isNotBlank() } ?: if (current.isMainThread) "main" else "ios-bg"
}.getOrDefault("ios-?")
