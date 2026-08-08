package com.garfiec.librechat.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.data.prefetch.PrefetchController
import com.garfiec.librechat.core.data.prefetch.PrefetchConversationStatus
import com.garfiec.librechat.core.data.prefetch.PrefetchRunState
import com.garfiec.librechat.core.data.prefetch.PrefetchStatus
import com.garfiec.librechat.core.data.prefetch.PrefetchStatusReporter
import com.garfiec.librechat.feature.settings.state.PrefetchDisplayStatus
import com.garfiec.librechat.feature.settings.state.toDisplayStatus
import com.garfiec.librechat.feature.settings.util.PlatformCacheCleaner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PrefetchActivityUiState(
    val status: PrefetchDisplayStatus = PrefetchDisplayStatus.Off,
    val warmedCount: Int = 0,
    val eligibleCount: Int = 0,
    val lastWarmedAt: Long? = null,
    val cachedMessageCount: Int = 0,
    val warmed: List<PrefetchConversationStatus> = emptyList(),
    val pending: List<PrefetchConversationStatus> = emptyList(),
    val conditions: List<PrefetchConditionRow> = emptyList(),
    /** Null until the walk of the cache directory finishes. */
    val imageCacheBytes: Long? = null,
) {
    /**
     * A manual pass is only offered when the gate would admit one. Anything else would either
     * override conditions the user set — spending mobile data they declined — or silently do
     * nothing, and a dead button is worse than a disabled one that says why.
     *
     * [PrefetchCondition.SERVER] is deliberately excluded. It reports the breaker, and clearing the
     * breaker is precisely what this button does — gating on it would disable the control in the one
     * situation it exists for, leaving a killed process as the only way back.
     *
     * Empty conditions mean the first status has not arrived, which is not the same as the gate
     * being open — so the control starts disabled rather than briefly offering a run that would be
     * dropped.
     */
    val canWarmNow: Boolean
        get() = conditions.isNotEmpty() && conditions.none { it.condition.gatesManualRun && !it.met }
}

data class PrefetchConditionRow(
    val condition: PrefetchCondition,
    val met: Boolean,
    /** Set on the network row when the device has no connection, rather than a declined one. */
    val offline: Boolean = false,
)

enum class PrefetchCondition(
    /** Whether an unmet value should block the manual run. False only for the breaker. */
    val gatesManualRun: Boolean,
) {
    ENABLED(gatesManualRun = true),
    FOREGROUND(gatesManualRun = true),
    NETWORK(gatesManualRun = true),
    POWER(gatesManualRun = true),
    IDLE(gatesManualRun = true),
    SERVER(gatesManualRun = false),
}

/**
 * Backs both the summary in Data settings and the activity screen.
 *
 * It owns no prefetch state of its own — every figure comes from [PrefetchStatusReporter], which
 * derives them from the watermark table and the live gate. The only thing this adds is the cache
 * directory size, which is a filesystem walk rather than a query and so is loaded once on demand.
 */
class PrefetchActivityViewModel(
    private val reporter: PrefetchStatusReporter,
    private val controller: PrefetchController,
    private val cacheCleaner: PlatformCacheCleaner,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val cacheFigures = MutableStateFlow(CacheFigures())

    private data class CacheFigures(val imageBytes: Long? = null, val messages: Int = 0)

    val uiState: StateFlow<PrefetchActivityUiState> =
        combineStatus().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = PrefetchActivityUiState(),
        )

    private fun combineStatus(): Flow<PrefetchActivityUiState> =
        combine(reporter.status(), cacheFigures) { status, figures -> status.toUiState(figures) }

    private fun PrefetchStatus.toUiState(figures: CacheFigures) = PrefetchActivityUiState(
        status = toDisplayStatus(),
        warmedCount = warmedCount,
        eligibleCount = eligibleCount,
        lastWarmedAt = lastWarmedAt,
        cachedMessageCount = figures.messages,
        warmed = warmed,
        pending = pending,
        conditions = listOf(
            // Listed even though the entry point into this screen is disabled while prefetching is
            // off: the route is serializable and restores from a saved back stack, and without this
            // row every condition then reads green while the one unmet condition is the one absent.
            PrefetchConditionRow(PrefetchCondition.ENABLED, conditions.enabled),
            PrefetchConditionRow(PrefetchCondition.FOREGROUND, conditions.foreground),
            PrefetchConditionRow(
                PrefetchCondition.NETWORK,
                conditions.networkAllowed,
                // An unmetered check answers false both for mobile data and for no radio at all, so
                // the row carries the distinction rather than reporting the second as the first.
                offline = !conditions.connected,
            ),
            PrefetchConditionRow(PrefetchCondition.POWER, conditions.powerAvailable),
            PrefetchConditionRow(PrefetchCondition.IDLE, conditions.appIdle),
            // Not a gate input: the breaker lives on the engine and stops passes after the gate has
            // already said yes. Listed alongside the conditions because from the user's side it is
            // one more reason nothing is happening, and the only one they cannot otherwise see.
            PrefetchConditionRow(PrefetchCondition.SERVER, runState != PrefetchRunState.Stopped),
        ),
        imageCacheBytes = figures.imageBytes,
    )

    /**
     * Requests a pass now, clearing the breaker first.
     *
     * The request is dropped by the controller if the gate has closed since the button was drawn,
     * so a stale tap cannot override the user's conditions.
     */
    fun warmNow() {
        controller.requestRun()
    }

    /**
     * Reads the cache figures, which are a recursive directory walk and a full table scan.
     *
     * Called by the activity screen, which is the only thing that renders them. Doing it on
     * construction instead would run both every time Data settings opens — which resolves this
     * ViewModel for the status summary alone — and throw the results away.
     */
    fun loadCacheFigures() {
        viewModelScope.launch {
            val bytes = withContext(ioDispatcher) { cacheCleaner.cacheSizeBytes() }
            val messages = reporter.cachedMessageCount()
            cacheFigures.value = CacheFigures(imageBytes = bytes, messages = messages)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
