package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.model.FileObject
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
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
    fun emptySelectionIsIgnored() = runTest {
        val handoff = ServerFileSelectionHandoff()
        handoff.publish("conv_A", emptyList())

        val delivered = withTimeoutOrNull(1_000) { handoff.selectionsFor("conv_A").first() }
        assertThat(delivered).isNull()
    }
}
