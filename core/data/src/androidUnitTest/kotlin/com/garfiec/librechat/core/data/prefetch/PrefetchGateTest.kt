package com.garfiec.librechat.core.data.prefetch

import com.garfiec.librechat.core.common.lifecycle.ForegroundSignal
import com.garfiec.librechat.core.common.network.ConnectivityObserver
import com.garfiec.librechat.core.common.network.NetworkConditionObserver
import com.garfiec.librechat.core.common.network.RequestActivityTracker
import com.garfiec.librechat.core.common.power.PowerStateObserver
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The gate's verdict and the per-condition readout are one expression, and these tests are what hold
 * them together: a checklist that disagreed with the boolean the engine obeys would send someone
 * chasing a condition that is not the one stopping their prefetcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefetchGateTest {

    private val foreground = MutableStateFlow(true)
    private val enabled = MutableStateFlow(true)
    private val unmetered = MutableStateFlow(true)
    private val allowMetered = MutableStateFlow(false)
    private val powerConstrained = MutableStateFlow(false)
    private val connected = MutableStateFlow(true)

    private val tracker = RequestActivityTracker()

    private fun gate(): PrefetchGate {
        val settings = mockk<SettingsDataStore>()
        every { settings.prefetchEnabled } returns enabled
        every { settings.prefetchOnMeteredEnabled } returns allowMetered

        val network = object : NetworkConditionObserver {
            override val isUnmetered: Flow<Boolean> = unmetered
        }
        val connectivity = object : ConnectivityObserver {
            override val isConnected: Flow<Boolean> = connected
        }
        val power = object : PowerStateObserver {
            override val isPowerConstrained: Flow<Boolean> = powerConstrained
        }
        val signal = ForegroundSignal().apply { set(foreground.value) }

        return PrefetchGate(
            foregroundSignal = signal,
            settingsDataStore = settings,
            networkConditionObserver = network,
            connectivityObserver = connectivity,
            powerStateObserver = power,
            requestActivityTracker = tracker,
        )
    }

    @Test
    fun `the gate is open when every condition is met`() = runTest {
        val conditions = gate().conditions().first()

        assertThat(conditions.isOpen).isTrue()
        assertThat(gate().isOpen().first()).isTrue()
    }

    @Test
    fun `a metered connection closes the gate unless the override is on`() = runTest {
        unmetered.value = false

        assertThat(gate().conditions().first().networkAllowed).isFalse()
        assertThat(gate().isOpen().first()).isFalse()

        allowMetered.value = true

        assertThat(gate().conditions().first().networkAllowed).isTrue()
        assertThat(gate().isOpen().first()).isTrue()
    }

    @Test
    fun `battery saver closes the gate`() = runTest {
        powerConstrained.value = true

        val conditions = gate().conditions().first()

        assertThat(conditions.powerAvailable).isFalse()
        assertThat(conditions.isOpen).isFalse()
    }

    @Test
    fun `a user request in flight closes the gate`() = runTest {
        tracker.begin()

        val conditions = gate().conditions().first()

        assertThat(conditions.appIdle).isFalse()
        assertThat(conditions.isOpen).isFalse()

        tracker.end()

        assertThat(gate().conditions().first().appIdle).isTrue()
    }

    @Test
    fun `switching prefetching off closes the gate`() = runTest {
        enabled.value = false

        val conditions = gate().conditions().first()

        assertThat(conditions.enabled).isFalse()
        assertThat(conditions.isOpen).isFalse()
    }

    /**
     * The gate is already closed by the metering check when there is no network, so connectivity is
     * diagnostic only — but the readout is built on it, and collapsing the two tells a user in
     * airplane mode to wait for Wi-Fi.
     */
    @Test
    fun `connectivity is reported separately from metering`() = runTest {
        connected.value = false
        unmetered.value = false

        val offline = gate().conditions().first()
        assertThat(offline.connected).isFalse()
        assertThat(offline.networkAllowed).isFalse()

        // The override makes an unusable connection "allowed" — which is exactly why the readout
        // needs connectivity as its own signal rather than inferring it from networkAllowed.
        allowMetered.value = true
        val overridden = gate().conditions().first()
        assertThat(overridden.networkAllowed).isTrue()
        assertThat(overridden.connected).isFalse()
        assertThat(overridden.isOpen).isTrue()
    }

    /**
     * The readout renders each condition separately, so an unmet one must not be able to hide behind
     * another. Every condition off at once has to report every condition off.
     */
    @Test
    fun `each unmet condition is reported independently`() = runTest {
        enabled.value = false
        foreground.value = false
        unmetered.value = false
        powerConstrained.value = true
        tracker.begin()

        val conditions = gate().conditions().first()

        assertThat(conditions.enabled).isFalse()
        assertThat(conditions.foreground).isFalse()
        assertThat(conditions.networkAllowed).isFalse()
        assertThat(conditions.powerAvailable).isFalse()
        assertThat(conditions.appIdle).isFalse()
    }
}
