package com.garfiec.librechat.core.logging

internal actual fun currentThreadName(): String =
    runCatching { Thread.currentThread().name }.getOrDefault("android-?")
