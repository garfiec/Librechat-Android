package com.garfiec.librechat.core.common.network

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

@OptIn(ExperimentalForeignApi::class)
class IosNetworkConditionObserver : NetworkConditionObserver {

    override val isUnmetered: Flow<Boolean> = callbackFlow {
        val monitor = nw_path_monitor_create()
        val queue = dispatch_queue_create("com.garfiec.librechat.network.conditions", null)

        nw_path_monitor_set_update_handler(monitor) { path ->
            // "Expensive" is cellular or a personal hotspot; "constrained" is Low Data Mode, which is
            // the user asking for exactly this restraint. Either one disqualifies the link.
            val satisfied = nw_path_get_status(path) == nw_path_status_satisfied
            trySend(satisfied && !nw_path_is_expensive(path) && !nw_path_is_constrained(path))
        }
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_start(monitor)

        awaitClose { nw_path_monitor_cancel(monitor) }
    }.distinctUntilChanged()
}
