package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.data.endpoint.EndpointDispatch
import com.garfiec.librechat.feature.chat.components.AttachedFile
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.QueueHandle
import com.garfiec.librechat.feature.chat.viewmodel.QueuedMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * The `POST /api/files/usage` renewal heartbeat (upstream #14470). The route now takes a bounded
 * hold rather than releasing the file, so a queue nobody touches again lapses — and it is metered
 * per user with a scored violation on a breach, which makes an over-eager tick a correctness
 * problem, not just waste.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageQueueHoldRenewalTest {

    private val stateFlow = MutableStateFlow(ChatUiState())
    private val scope = TestScope()

    /**
     * The delegate runs its loop on a scope of its own so the never-completing ticker cannot fail
     * the test at teardown; sharing the scheduler is what makes virtual time drive it.
     */
    private val delegateScope = CoroutineScope(StandardTestDispatcher(scope.testScheduler))
    private val stateHandle = ChatStateHandle(stateFlow, delegateScope)

    /** Advanced in lockstep with virtual time — the delegate measures elapsed time with this, not
     *  with `delay`'s return. */
    private val timeSource = TestTimeSource()

    private val renewedBatches = mutableListOf<List<String>>()

    /** Set to gate a renewal mid-flight; null means the call returns immediately. */
    private var renewalGate: CompletableDeferred<Unit>? = null

    /** Whether the fake server exposes `POST /api/files/usage`. */
    private var holdSupported = true

    private val delegate = MessageQueueDelegate(
        handle = QueueHandle(stateHandle),
        activeAccountProvider = InMemoryActiveAccountProvider(AccountState.Resolved(AccountId("srv:user-1"))),
        sendWithSpec = { _, _ -> },
        onQueuedDropped = {},
        markFilesUsed = { ids ->
            renewedBatches.add(ids)
            renewalGate?.await()
        },
        holdRenewalSupported = { holdSupported },
        timeSource = timeSource,
    )

    /**
     * Runs [body] against the shared scheduler and always tears the ticker down inside the test
     * body. `runTest` drains the scheduler on its way out, and the heartbeat always has a timed
     * task pending — leaving it alive advances virtual time forever instead of finishing.
     */
    private fun heartbeatTest(body: TestScope.() -> Unit) = runTest(scope.testScheduler) {
        try {
            scope.body()
        } finally {
            delegateScope.cancel()
        }
    }

    private fun spec(id: String, vararg fileIds: String) = QueuedMessage(
        localId = id,
        text = id,
        endpoint = "openAI",
        model = "gpt-4",
        agentId = null,
        dispatch = EndpointDispatch(endpointType = null, key = null, modelDisplayLabel = null),
        attachments = fileIds.map { AttachedFile(uri = it, name = it, fileId = it) },
    )

    /** Moves the scheduler and the delegate's clock together, then lets due work run. */
    private fun TestScope.elapse(duration: Duration) {
        timeSource += duration
        advanceTimeBy(duration)
        runCurrent()
    }

    /** Drops the enqueue-time touch so the assertions only see heartbeat renewals. */
    private fun TestScope.enqueueQuietly(spec: QueuedMessage) {
        delegate.enqueue(spec)
        runCurrent()
        renewedBatches.clear()
    }

    @Test
    fun `no renewal before the interval elapses`() = heartbeatTest {
        enqueueQuietly(spec("a", "file-1"))

        elapse(29.minutes)

        assertThat(renewedBatches).isEmpty()
    }

    @Test
    fun `renews the whole queue once the interval elapses`() = heartbeatTest {
        enqueueQuietly(spec("a", "file-1", "file-2"))
        enqueueQuietly(spec("b", "file-2", "file-3"))

        elapse(30.minutes)

        // One request covering every queued id, deduped across items.
        assertThat(renewedBatches).hasSize(1)
        assertThat(renewedBatches.single()).containsExactly("file-1", "file-2", "file-3")
    }

    @Test
    fun `keeps renewing on each subsequent interval`() = heartbeatTest {
        enqueueQuietly(spec("a", "file-1"))

        elapse(30.minutes)
        elapse(30.minutes)
        elapse(30.minutes)

        assertThat(renewedBatches).hasSize(3)
    }

    /**
     * The overlap guard. A tick whose request has not settled must not be joined by the ticks
     * behind it — against a metered, violation-scoring route, a pile-up is the failure mode.
     */
    @Test
    fun `a tick cannot overlap a renewal that is still in flight`() = heartbeatTest {
        enqueueQuietly(spec("a", "file-1"))
        val gate = CompletableDeferred<Unit>()
        renewalGate = gate

        elapse(30.minutes)
        assertThat(renewedBatches).hasSize(1)

        // Two further intervals pass while the first request is still hanging.
        elapse(90.minutes)
        assertThat(renewedBatches).hasSize(1)

        // It settles; the loop resumes and spaces the next one a full interval out.
        renewalGate = null
        gate.complete(Unit)
        runCurrent()
        assertThat(renewedBatches).hasSize(1)

        elapse(30.minutes)
        assertThat(renewedBatches).hasSize(2)
    }

    /**
     * The loop delays before it renews. That ordering is what keeps a kill/relaunch cycle from
     * becoming a burst of unspaced requests against a route that scores a limit breach.
     */
    @Test
    fun `enqueueing does not itself fire a heartbeat renewal`() = heartbeatTest {
        enqueueQuietly(spec("a", "file-1"))

        runCurrent()

        assertThat(renewedBatches).isEmpty()
    }

    /** The enqueue-time touch is unchanged by the heartbeat: a new item is held immediately
     *  rather than waiting up to an interval for the first tick. */
    @Test
    fun `enqueue still touches its own attachments immediately`() = heartbeatTest {
        delegate.enqueue(spec("a", "file-1", "file-2"))
        runCurrent()

        assertThat(renewedBatches).hasSize(1)
        assertThat(renewedBatches.single()).containsExactly("file-1", "file-2")
    }

    /** A second enqueue joins the running loop instead of starting a rival one. */
    @Test
    fun `a second enqueue does not double the renewals`() = heartbeatTest {
        enqueueQuietly(spec("a", "file-1"))
        elapse(10.minutes)
        enqueueQuietly(spec("b", "file-2"))

        elapse(20.minutes)

        assertThat(renewedBatches).hasSize(1)
        assertThat(renewedBatches.single()).containsExactly("file-1", "file-2")
    }

    /** A chat that never queues an upload never starts a heartbeat at all. */
    @Test
    fun `a queue of text-only items issues no request`() = heartbeatTest {
        enqueueQuietly(spec("a"))

        elapse(90.minutes)

        assertThat(renewedBatches).isEmpty()
    }

    /**
     * Once the queue drains there is nothing left to hold, so the loop stops rather than waking
     * forever on an idle chat — and a later enqueue starts a fresh one.
     */
    @Test
    fun `the loop stops when the queue drains and restarts on the next enqueue`() = heartbeatTest {
        enqueueQuietly(spec("a", "file-1"))
        elapse(30.minutes)
        assertThat(renewedBatches).hasSize(1)

        delegate.drainNext(awaitSettle = false)
        renewedBatches.clear()
        elapse(90.minutes)
        assertThat(renewedBatches).isEmpty()

        enqueueQuietly(spec("b", "file-2"))
        elapse(30.minutes)

        assertThat(renewedBatches).hasSize(1)
        assertThat(renewedBatches.single()).containsExactly("file-2")
    }

    /**
     * The path that reaches the queue without an enqueue: a drain empties it, the loop exits, the
     * send gate refuses, and `requeueRefusedDrain` puts the item straight back. Nothing touched
     * those files on the way through, so without a restart here the attachment sits in a queue no
     * heartbeat is protecting — the exact lapse this feature exists to prevent. Queued-item
     * edit commit and cancel re-enter the same way.
     */
    @Test
    fun `reinserting a refused drain restarts the heartbeat`() = heartbeatTest {
        enqueueQuietly(spec("a", "file-1"))

        val drained = spec("a", "file-1")
        delegate.drainNext(awaitSettle = false)
        runCurrent()
        // The loop notices the empty queue and exits on its next tick.
        elapse(30.minutes)
        renewedBatches.clear()

        delegate.reinsert(0, drained)
        elapse(30.minutes)

        assertThat(renewedBatches).hasSize(1)
        assertThat(renewedBatches.single()).containsExactly("file-1")
    }

    /** A text-only reinsert has nothing to hold, so it must not resurrect the ticker. */
    @Test
    fun `reinserting a text-only item starts no heartbeat`() = heartbeatTest {
        delegate.reinsert(0, spec("a"))

        elapse(90.minutes)

        assertThat(renewedBatches).isEmpty()
    }

    /**
     * `markFilesUsed` already no-ops when the route is absent, but the loop would still wake every
     * interval for the ViewModel's whole life. A null `DetectedBackend` fails the gate closed, so
     * this is also the cold-start state.
     */
    @Test
    fun `no heartbeat starts when the server has no usage route`() = heartbeatTest {
        holdSupported = false

        enqueueQuietly(spec("a", "file-1"))
        elapse(90.minutes)

        assertThat(renewedBatches).isEmpty()
    }

    /**
     * `delay` is a scheduling hint, not a measurement — Android schedules on uptime and iOS on
     * wall time, so neither return proves the interval elapsed. Driving the scheduler ahead of the
     * injected clock is the only way to exercise that guard; every other test moves them in
     * lockstep, where it can never fire.
     */
    @Test
    fun `a scheduler running ahead of the clock does not renew early`() = heartbeatTest {
        enqueueQuietly(spec("a", "file-1"))

        // An hour of scheduler time, no elapsed clock time.
        advanceTimeBy(60.minutes)
        runCurrent()
        assertThat(renewedBatches).isEmpty()

        // The clock catches up and the next tick renews once.
        elapse(30.minutes)
        assertThat(renewedBatches).hasSize(1)
    }
}
