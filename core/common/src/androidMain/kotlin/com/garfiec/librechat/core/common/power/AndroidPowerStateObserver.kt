package com.garfiec.librechat.core.common.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class AndroidPowerStateObserver(
    private val context: Context,
) : PowerStateObserver {

    override val isPowerConstrained: Flow<Boolean> = callbackFlow {
        val powerManager = context.getSystemService(PowerManager::class.java)

        fun emitCurrent() {
            trySend(powerManager?.isPowerSaveMode == true)
        }

        emitCurrent()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = emitCurrent()
        }
        // The broadcast carries no payload, so the mode is re-read rather than parsed out of it.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()
}
