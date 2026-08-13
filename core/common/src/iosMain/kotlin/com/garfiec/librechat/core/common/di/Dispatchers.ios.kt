package com.garfiec.librechat.core.common.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

// Dispatchers.IO, not Default: this carries blocking work — sockets, the Keychain read behind a
// token fetch, JSON decode — and on Native Default is a MultiWorkerDispatcher sized to the core
// count, shared with applicationScope. Since safeApiCall routes every API call through here (#326),
// leaving it on Default would let a cold-start fan-out occupy every CPU worker waiting on sockets.
// Native's Dispatchers.IO is its own worker pool, so that contention goes away; Room's iOS query
// context already uses it (DataPlatformModule.ios.kt).
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
