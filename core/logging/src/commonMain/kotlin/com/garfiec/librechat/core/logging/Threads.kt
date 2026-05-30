package com.garfiec.librechat.core.logging

/** Best-effort current thread/queue name for log records. Never throws. */
internal expect fun currentThreadName(): String
