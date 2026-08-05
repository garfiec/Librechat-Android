package com.garfiec.librechat.core.common.network

import kotlinx.coroutines.flow.Flow

/**
 * Whether the active network is one it is polite to spend bandwidth on.
 *
 * Distinct from [ConnectivityObserver], which answers "can we reach the server at all" — a metered
 * connection is fully connected, and everything the user asks for should still go over it. This
 * governs only work the user did not ask for.
 *
 * Emits `false` while there is no network, so a caller that gates on it does not also need a
 * connectivity check.
 */
interface NetworkConditionObserver {
    /** True when the active network is unmetered (Wi-Fi/Ethernet, not a cellular or hotspot link). */
    val isUnmetered: Flow<Boolean>
}
