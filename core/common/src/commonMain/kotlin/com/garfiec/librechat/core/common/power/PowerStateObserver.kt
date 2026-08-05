package com.garfiec.librechat.core.common.power

import kotlinx.coroutines.flow.Flow

/**
 * Whether the device is asking apps to do less.
 *
 * Battery saver is an explicit instruction from the user, not a hint, so background work that is
 * optional by definition should stop while it is on.
 */
interface PowerStateObserver {
    val isPowerConstrained: Flow<Boolean>
}
