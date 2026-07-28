package com.garfiec.librechat.feature.chat

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.Test

/**
 * A share must reach the composer the user is looking at.
 *
 * Several chat screens are alive at once — the `NewChat` landing sits in the back stack beneath an
 * open `Chat` — and the previous design pinged them all and let the first to wake claim the text.
 * On a phone that is the landing, which is not on screen: the user shared into a conversation and
 * their text went somewhere they could only find by navigating back.
 *
 * Uris need Robolectric, so these all share text; routing does not depend on the payload.
 */
class ShareIntentConsumerTest {

    private val share = SharedContent(text = "shared from another app")

    @Before
    fun setUp() = ShareIntentConsumer.resetForTest()

    @Test
    fun `a share addressed to an open chat never reaches the landing`() = runTest {
        ShareIntentConsumer.setPendingShare(share)
        ShareIntentConsumer.dispatchTo("conv_A")

        // The landing ViewModel is alive and collecting, and must come away empty.
        val leaked = withTimeoutOrNull(1_000) { ShareIntentConsumer.sharesFor(null).first() }
        assertThat(leaked).isNull()
        assertThat(ShareIntentConsumer.sharesFor("conv_A").first()).isEqualTo(share)
    }

    @Test
    fun `a share addressed to the landing never reaches an open chat`() = runTest {
        ShareIntentConsumer.setPendingShare(share)
        ShareIntentConsumer.dispatchTo(null)

        val leaked = withTimeoutOrNull(1_000) { ShareIntentConsumer.sharesFor("conv_A").first() }
        assertThat(leaked).isNull()
        assertThat(ShareIntentConsumer.sharesFor(null).first()).isEqualTo(share)
    }

    /** Cold start: the share is addressed before the target screen has composed. */
    @Test
    fun `a share dispatched before its target subscribes is drained on subscribe`() = runTest {
        ShareIntentConsumer.setPendingShare(share)
        ShareIntentConsumer.dispatchTo(null)

        assertThat(ShareIntentConsumer.sharesFor(null).first()).isEqualTo(share)
    }

    /**
     * The nav host's effect re-runs on recomposition and on an activity recreation, and the launch
     * intent is sticky — so dispatch has to be the thing that can only fire once per share.
     */
    @Test
    fun `a share is delivered once however often dispatch is called`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val received = mutableListOf<SharedContent>()
        val collector = launch(dispatcher) { ShareIntentConsumer.sharesFor(null).collect { received += it } }

        ShareIntentConsumer.setPendingShare(share)
        ShareIntentConsumer.dispatchTo(null)
        ShareIntentConsumer.dispatchTo(null)

        assertThat(received).containsExactly(share)
        collector.cancel()
    }

    /** Nothing staged — a stray dispatch must not replay the last share. */
    @Test
    fun `dispatching with nothing staged delivers nothing`() = runTest {
        val delivered = withTimeoutOrNull(1_000) {
            ShareIntentConsumer.dispatchTo(null)
            ShareIntentConsumer.sharesFor(null).first()
        }
        assertThat(delivered).isNull()
    }

    /**
     * Two landing ViewModels briefly coexist when a payload-carrying `NewChat` replaces the bare
     * one; the outgoing generation's disposal must not evict its successor's channel.
     */
    @Test
    fun `a recreated landing still receives after its predecessor is disposed`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val received = mutableListOf<SharedContent>()

        val genA = launch(dispatcher) { ShareIntentConsumer.sharesFor(null).collect { } }
        val genB = launch(dispatcher) { ShareIntentConsumer.sharesFor(null).collect { received += it } }
        genA.cancel()

        ShareIntentConsumer.setPendingShare(share)
        ShareIntentConsumer.dispatchTo(null)

        assertThat(received).containsExactly(share)
        genB.cancel()
    }
}
