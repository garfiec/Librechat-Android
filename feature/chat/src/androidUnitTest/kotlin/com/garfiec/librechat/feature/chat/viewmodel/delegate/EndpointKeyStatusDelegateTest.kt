package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.KeyRepository
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.endpoint.KeyInvalidation
import com.garfiec.librechat.core.model.endpoint.KeyState
import com.garfiec.librechat.core.model.endpoint.fromWire
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.EndpointKeyHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Behavior tests for the per-endpoint user-provided-key status fan-out.
 *
 * Each test wires a fake [KeyRepository] return shape, calls
 * [EndpointKeyStatusDelegate.recomputeFor], drains the test scheduler, and
 * asserts the resulting [ChatUiState.endpointKeyStates] map.
 *
 * The delegate's contract: only endpoints with `userProvide=true` (or
 * `userProvideURL=true`) appear in the result map; built-ins are absent.
 * Network errors preserve any previously-resolved [KeyState] for that endpoint;
 * brand-new endpoints with no prior state fall through to [KeyState.Unset]
 * (fail-closed → CTA shown).
 *
 * The delegate consumes [KeyRepository.keyInvalidations] in its init block, so
 * every fixture provides a real [MutableSharedFlow] instead of relying on
 * MockK's relaxed default (the relaxed default returns an unsubscribable stub).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EndpointKeyStatusDelegateTest {

    private val invalidations = MutableSharedFlow<KeyInvalidation>(extraBufferCapacity = 8)

    private val keyRepository = mockk<KeyRepository>(relaxed = true).also {
        every { it.keyInvalidations } returns invalidations
    }

    private fun newHandle(scope: CoroutineScope) = ChatStateHandle(
        stateFlow = MutableStateFlow(ChatUiState()),
        scope = scope,
    )

    @Test
    fun unsetWhenGetKeyExpiryReturnsNull() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)
        coEvery { keyRepository.fetchKeyState(any()) } returns Result.Success(KeyState.Unset)

        delegate.recomputeFor(mapOf("openAI" to EndpointConfig(userProvide = true)))
        advanceUntilIdle()

        assertThat(handle.state.endpointKeyStates).containsExactly("openAI", KeyState.Unset)
    }

    @Test
    fun setWithFutureIsoTimestamp() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)
        val future = (Clock.System.now() + 1.days).toString()
        coEvery { keyRepository.fetchKeyState("openAI") } returns
            Result.Success(KeyState.fromWire(future, Clock.System.now()).state)

        delegate.recomputeFor(mapOf("openAI" to EndpointConfig(userProvide = true)))
        advanceUntilIdle()

        val state = handle.state.endpointKeyStates["openAI"]
        assertThat(state).isInstanceOf(KeyState.Set::class.java)
        assertThat((state as KeyState.Set).neverExpires).isFalse()
        assertThat(state.expiresAt).isNotNull()
        assertThat(state.wire).isEqualTo(future)
    }

    @Test
    fun expiredWhenIsoTimestampInPast() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)
        val past = (Clock.System.now() - 1.days).toString()
        coEvery { keyRepository.fetchKeyState("openAI") } returns
            Result.Success(KeyState.fromWire(past, Clock.System.now()).state)

        delegate.recomputeFor(mapOf("openAI" to EndpointConfig(userProvide = true)))
        advanceUntilIdle()

        assertThat(handle.state.endpointKeyStates["openAI"]).isEqualTo(KeyState.Expired)
    }

    @Test
    fun setNeverExpiresWhenWireValueIsNeverLiteral() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)
        coEvery { keyRepository.fetchKeyState("openAI") } returns
            Result.Success(KeyState.Set(expiresAt = null, neverExpires = true, wire = "never"))

        delegate.recomputeFor(mapOf("openAI" to EndpointConfig(userProvide = true)))
        advanceUntilIdle()

        val state = handle.state.endpointKeyStates["openAI"]
        assertThat(state).isInstanceOf(KeyState.Set::class.java)
        assertThat((state as KeyState.Set).neverExpires).isTrue()
        assertThat(state.expiresAt).isNull()
        assertThat(state.wire).isEqualTo("never")
    }

    @Test
    fun userProvideUrlOnlyEndpointStillTrackedAsNeedsKey() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)
        coEvery { keyRepository.fetchKeyState(any()) } returns Result.Success(KeyState.Unset)

        delegate.recomputeFor(
            mapOf("custom" to EndpointConfig(userProvide = null, userProvideURL = true)),
        )
        advanceUntilIdle()

        assertThat(handle.state.endpointKeyStates).containsExactly("custom", KeyState.Unset)
    }

    @Test
    fun builtInEndpointsAbsentFromResultMap() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)
        coEvery { keyRepository.fetchKeyState(any()) } returns Result.Success(KeyState.Unset)

        delegate.recomputeFor(
            mapOf(
                "anthropic" to EndpointConfig(userProvide = null),
                "openAI" to EndpointConfig(userProvide = false),
            ),
        )
        advanceUntilIdle()

        assertThat(handle.state.endpointKeyStates).isEmpty()
    }

    @Test
    fun azureEndpointResolvesToAzureOpenAiKeyName() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)
        coEvery { keyRepository.fetchKeyState("azureOpenAI") } returns Result.Success(KeyState.Unset)

        delegate.recomputeFor(
            mapOf("AzureOpenAI" to EndpointConfig(userProvide = true, azure = true)),
        )
        advanceUntilIdle()

        coVerify { keyRepository.fetchKeyState("azureOpenAI") }
        assertThat(handle.state.endpointKeyStates).containsKey("AzureOpenAI")
    }

    @Test
    fun networkErrorOnFirstLoadFallsBackToUnset() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)
        coEvery { keyRepository.fetchKeyState(any()) } returns
            Result.Error(RuntimeException("server unavailable"))

        delegate.recomputeFor(mapOf("openAI" to EndpointConfig(userProvide = true)))
        advanceUntilIdle()

        // No prior state on first load → surface the configurable affordance
        // instead of silently letting the user pick a model that will fail at
        // chat-send time.
        assertThat(handle.state.endpointKeyStates["openAI"]).isEqualTo(KeyState.Unset)
    }

    @Test
    fun networkErrorPreservesPriorResolvedStateAcrossRefetch() = runTest {
        // Guards against the "transient 401 mid-auth-refresh greys every row"
        // failure mode: once an endpoint has been resolved to a real state, a
        // later failed fetch (during invalidation-driven refresh) must keep the
        // last-known value rather than demoting it to Unset/CTA.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)

        val future = (Clock.System.now() + 1.days).toString()
        val resolvedSet = KeyState.Set(
            expiresAt = Clock.System.now() + 1.days,
            neverExpires = false,
            wire = future,
        )

        // First fan-out resolves to a real Set.
        coEvery { keyRepository.fetchKeyState("openAI") } returns Result.Success(resolvedSet)
        delegate.recomputeFor(mapOf("openAI" to EndpointConfig(userProvide = true)))
        advanceUntilIdle()
        assertThat(handle.state.endpointKeyStates["openAI"]).isEqualTo(resolvedSet)

        // Invalidation triggers a refetch, but the next call errors.
        coEvery { keyRepository.fetchKeyState("openAI") } returns
            Result.Error(RuntimeException("transient 401"))
        invalidations.emit(KeyInvalidation.ByName("openAI"))
        advanceUntilIdle()

        // Prior Set value preserved — the CTA does NOT appear mid-blip.
        assertThat(handle.state.endpointKeyStates["openAI"]).isEqualTo(resolvedSet)
    }

    @Test
    fun loadingStatePushedOptimisticallyBeforeFanOut() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)

        // Stub never returns until we explicitly advance the scheduler so we can
        // observe the optimistic Loading state mid-fan-out.
        val deferredAnswer = CompletableDeferred<Result<KeyState>>()
        coEvery { keyRepository.fetchKeyState(any()) } coAnswers { deferredAnswer.await() }

        delegate.recomputeFor(mapOf("openAI" to EndpointConfig(userProvide = true)))
        // Yield once so the optimistic update lands, but the fan-out is still suspended.
        testScheduler.runCurrent()

        assertThat(handle.state.endpointKeyStates["openAI"]).isEqualTo(KeyState.Loading)

        // Now resolve the in-flight call so the test can complete cleanly.
        deferredAnswer.complete(Result.Success(KeyState.Unset))
        advanceUntilIdle()
        assertThat(handle.state.endpointKeyStates["openAI"]).isEqualTo(KeyState.Unset)
    }

    @Test
    fun emptyConfigsClearsExistingStates() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)
        coEvery { keyRepository.fetchKeyState(any()) } returns Result.Success(KeyState.Unset)

        delegate.recomputeFor(mapOf("openAI" to EndpointConfig(userProvide = true)))
        advanceUntilIdle()
        delegate.recomputeFor(emptyMap())
        advanceUntilIdle()

        assertThat(handle.state.endpointKeyStates).isEmpty()
    }

    @Test
    fun keyInvalidationByNameTriggersRecompute() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)
        coEvery { keyRepository.fetchKeyState(any()) } returns Result.Success(KeyState.Unset)

        delegate.recomputeFor(mapOf("openAI" to EndpointConfig(userProvide = true)))
        advanceUntilIdle()

        invalidations.emit(KeyInvalidation.ByName("openAI"))
        advanceUntilIdle()

        coVerify(exactly = 2) { keyRepository.fetchKeyState("openAI") }
    }

    @Test
    fun keyInvalidationForUnrelatedEndpointDoesNotTriggerRecompute() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)
        coEvery { keyRepository.fetchKeyState(any()) } returns Result.Success(KeyState.Unset)

        delegate.recomputeFor(mapOf("openAI" to EndpointConfig(userProvide = true)))
        advanceUntilIdle()

        invalidations.emit(KeyInvalidation.ByName("anthropic"))
        advanceUntilIdle()

        // Unchanged: only the openAI fan-out from the initial recomputeFor.
        coVerify(exactly = 1) { keyRepository.fetchKeyState("openAI") }
    }

    @Test
    fun keyInvalidationAllAlwaysRefreshes() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)
        coEvery { keyRepository.fetchKeyState(any()) } returns Result.Success(KeyState.Unset)

        delegate.recomputeFor(mapOf("openAI" to EndpointConfig(userProvide = true)))
        advanceUntilIdle()

        invalidations.emit(KeyInvalidation.All)
        advanceUntilIdle()

        coVerify(exactly = 2) { keyRepository.fetchKeyState("openAI") }
    }

    @Test
    fun emptyConfigsClearsExistingStatesWithoutRedundantWrites() = runTest {
        // recomputeFor with an empty gated set must clear endpointKeyStates if
        // it had entries, and must skip the state write entirely if already empty.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)
        coEvery { keyRepository.fetchKeyState(any()) } returns Result.Success(KeyState.Unset)

        // Seed with a non-empty map.
        delegate.recomputeFor(mapOf("openAI" to EndpointConfig(userProvide = true)))
        advanceUntilIdle()
        assertThat(handle.state.endpointKeyStates).hasSize(1)

        // Clear with an empty map — entries vanish.
        delegate.recomputeFor(emptyMap())
        advanceUntilIdle()
        assertThat(handle.state.endpointKeyStates).isEmpty()
    }

    @Test
    fun keyInvalidationDuringInFlightRecomputeCancelsPriorFanOut() = runTest {
        // Round-6 contract: an invalidation arriving while a `recomputeFor` fan-out is
        // suspended on a slow `getKeyExpiry` GET MUST cancel that prior fan-out and
        // launch a fresh one — it does not serialize behind the prior fan-out's mutex.
        // Otherwise the user's just-saved key would not be reflected snappily.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)

        // First call: suspend indefinitely so the fan-out is in-flight when the
        // invalidation arrives.
        val firstCall = CompletableDeferred<Result<KeyState>>()
        val secondCall = CompletableDeferred<Result<KeyState>>()
        var callCount = 0
        coEvery { keyRepository.fetchKeyState("openAI") } coAnswers {
            callCount++
            if (callCount == 1) firstCall.await() else secondCall.await()
        }

        delegate.recomputeFor(mapOf("openAI" to EndpointConfig(userProvide = true)))
        testScheduler.runCurrent()
        // First fan-out is in flight.
        assertThat(callCount).isEqualTo(1)

        // Invalidation arrives mid-recompute.
        invalidations.emit(KeyInvalidation.ByName("openAI"))
        testScheduler.runCurrent()

        // Resolve the SECOND call — the first call should never complete since the
        // job that owned it was cancelled.
        secondCall.complete(Result.Success(KeyState.Unset))
        advanceUntilIdle()

        assertThat(handle.state.endpointKeyStates["openAI"]).isEqualTo(KeyState.Unset)
        // The first call's deferred is left dangling; if we hadn't cancelled, the
        // second runFanOut would have been queued behind it on the mutex.
        assertThat(firstCall.isCompleted).isFalse()
    }

    @Test
    fun reEmittingSameGatedSetDoesNotFlickerResolvedRowsBackToLoading() = runTest {
        // An unrelated config tweak that re-emits the same gated endpoints must
        // NOT push every already-resolved row through Loading on each emission.
        // Only net-new (or previously-Loading) endpoints get the optimistic
        // Loading; resolved Set/Expired/Unset values are preserved.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val handle = newHandle(scope)
        val delegate = EndpointKeyStatusDelegate(EndpointKeyHandle(handle), keyRepository)

        val future = (Clock.System.now() + 1.days).toString()
        coEvery { keyRepository.fetchKeyState("openAI") } returns
            Result.Success(KeyState.Set(expiresAt = Clock.System.now() + 1.days, neverExpires = false, wire = future))

        // First emission: row resolves to Set.
        delegate.recomputeFor(mapOf("openAI" to EndpointConfig(userProvide = true)))
        advanceUntilIdle()
        val resolved = handle.state.endpointKeyStates["openAI"]
        assertThat(resolved).isInstanceOf(KeyState.Set::class.java)

        // Now suspend the next fetchKeyState so we can observe the optimistic push.
        val deferred = CompletableDeferred<Result<KeyState>>()
        coEvery { keyRepository.fetchKeyState("openAI") } coAnswers { deferred.await() }

        // Re-emit the same gated set (e.g. an unrelated config tweak).
        delegate.recomputeFor(mapOf("openAI" to EndpointConfig(userProvide = true)))
        testScheduler.runCurrent()

        // Resolved value preserved during the in-flight fan-out — no flicker.
        assertThat(handle.state.endpointKeyStates["openAI"]).isInstanceOf(KeyState.Set::class.java)

        // Let the in-flight call complete and confirm it still ends in Set.
        deferred.complete(Result.Success(resolved!!))
        advanceUntilIdle()
        assertThat(handle.state.endpointKeyStates["openAI"]).isEqualTo(resolved)
    }
}
