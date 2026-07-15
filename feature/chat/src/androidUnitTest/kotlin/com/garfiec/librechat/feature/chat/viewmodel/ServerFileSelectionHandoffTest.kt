package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.model.FileObject
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test

class ServerFileSelectionHandoffTest {

    private val files = listOf(
        FileObject(
            fileId = "file-1",
            filename = "document.pdf",
            filepath = "/files/user1/document.pdf",
            type = "application/pdf",
            bytes = 1024L,
            createdAt = "2026-02-19T10:00:00.000Z",
        ),
    )

    @Test
    fun publishIsDeliveredToTheMatchingConversationCollector() = runTest {
        val handoff = ServerFileSelectionHandoff()
        handoff.publish("conv_A", files)

        assertThat(handoff.selectionsFor("conv_A").first()).isEqualTo(files)
    }

    @Test
    fun aDifferentConversationNeverReceivesAnotherConversationsSelection() = runTest {
        val handoff = ServerFileSelectionHandoff()
        handoff.publish("conv_A", files)

        // conv_B has its own channel, so it must not consume conv_A's selection.
        val leaked = withTimeoutOrNull(1_000) { handoff.selectionsFor("conv_B").first() }
        assertThat(leaked).isNull()
        // conv_A's selection is still waiting for conv_A.
        assertThat(handoff.selectionsFor("conv_A").first()).isEqualTo(files)
    }

    @Test
    fun nullKeyLandingHasItsOwnChannelSeparateFromIdentifiedChats() = runTest {
        val handoff = ServerFileSelectionHandoff()
        handoff.publish(null, files)

        val leaked = withTimeoutOrNull(1_000) { handoff.selectionsFor("conv_A").first() }
        assertThat(leaked).isNull()
        assertThat(handoff.selectionsFor(null).first()).isEqualTo(files)
    }

    @Test
    fun recreatedSameKeyCollectorStillReceivesAfterPredecessorIsDisposed() = runTest {
        // Reproduces the agent-start / model-shortcut churn: navigateToTopLevel(NewChat(agentId))
        // replaces the bare NewChat() entry, so two null-keyed landing ViewModels briefly coexist
        // (successor composed before predecessor disposed) before the predecessor is torn down.
        val handoff = ServerFileSelectionHandoff()
        // Unconfined so each launch runs eagerly to its first suspension: both collectors subscribe
        // immediately, and the second (the replacement VM) becomes the current channel for the key
        // (newest wins) — matching reality, where the picker is opened only after the VM churn has
        // settled.
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val received = mutableListOf<List<FileObject>>()

        // Generation A subscribes (the outgoing landing VM), then Generation B on the same key.
        val genA = launch(dispatcher) { handoff.selectionsFor(null).collect { } }
        val genB = launch(dispatcher) { handoff.selectionsFor(null).collect { received += it } }

        // Predecessor is disposed. Its identity-guarded completion must NOT evict B's channel.
        genA.cancel()

        // The picker confirms: publish must reach the live successor, not an orphaned channel.
        handoff.publish(null, files)

        assertThat(received).containsExactly(files)
        genB.cancel()
    }

    @Test
    fun emptySelectionIsIgnored() = runTest {
        val handoff = ServerFileSelectionHandoff()
        handoff.publish("conv_A", emptyList())

        val delivered = withTimeoutOrNull(1_000) { handoff.selectionsFor("conv_A").first() }
        assertThat(delivered).isNull()
    }
}
