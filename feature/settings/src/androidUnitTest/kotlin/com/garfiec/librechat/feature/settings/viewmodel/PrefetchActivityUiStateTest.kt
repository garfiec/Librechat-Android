package com.garfiec.librechat.feature.settings.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** When the manual run is offered, which is the one control on the activity screen. */
class PrefetchActivityUiStateTest {

    private fun state(vararg unmet: PrefetchCondition) = PrefetchActivityUiState(
        conditions = PrefetchCondition.entries.map {
            PrefetchConditionRow(it, met = it !in unmet)
        },
    )

    @Test
    fun `a manual run is offered when the gate is open`() {
        assertThat(state().canWarmNow).isTrue()
    }

    /**
     * The button clears the breaker, so gating it on the breaker would disable the control in the
     * only situation it exists for — leaving killing the process as the sole way to recover.
     */
    @Test
    fun `a tripped breaker still allows a manual run`() {
        assertThat(state(PrefetchCondition.SERVER).canWarmNow).isTrue()
    }

    @Test
    fun `every gate condition blocks a manual run on its own`() {
        assertThat(state(PrefetchCondition.ENABLED).canWarmNow).isFalse()
        assertThat(state(PrefetchCondition.NETWORK).canWarmNow).isFalse()
        assertThat(state(PrefetchCondition.POWER).canWarmNow).isFalse()
        assertThat(state(PrefetchCondition.IDLE).canWarmNow).isFalse()
        assertThat(state(PrefetchCondition.FOREGROUND).canWarmNow).isFalse()
    }

    /**
     * Before the first status arrives nothing is known — which is not the same as the gate being
     * open, so the control must not offer a run that would be dropped.
     */
    @Test
    fun `no conditions loaded yet does not offer a run`() {
        assertThat(PrefetchActivityUiState().canWarmNow).isFalse()
    }
}
