package com.garfiec.librechat.feature.chat.components

import com.garfiec.librechat.feature.chat.viewmodel.DuringRunSendTarget
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The composer button must NAME what tapping it does.
 *
 * This is the half of the during-run send that no behavioural test reaches: routing is decided by
 * [com.garfiec.librechat.feature.chat.viewmodel.ChatUiState.duringRunSendTarget] and exercised in
 * `ChatViewModelDuringRunSendTest`, while the button's face is derived separately — and for a
 * while, from a different source (the user's steer/queue preference), so a live
 * `ask_user_question` pause got a button reading "add to queue" over a tap that answered the
 * question. Nothing failed, because nothing compared the two.
 */
class SendButtonModeTest {

    private fun mode(
        isStreaming: Boolean = true,
        canQueue: Boolean = true,
        target: DuringRunSendTarget = DuringRunSendTarget.QUEUE,
        isEditingQueued: Boolean = false,
        isAwaitingUploadSend: Boolean = false,
    ) = sendButtonModeFor(
        isStreaming = isStreaming,
        canQueue = canQueue,
        duringRunTarget = target,
        isEditingQueued = isEditingQueued,
        isAwaitingUploadSend = isAwaitingUploadSend,
    )

    // ── The button names its target ───────────────────────────────────────

    @Test
    fun `a send that will answer a paused run says so`() {
        assertThat(mode(target = DuringRunSendTarget.ANSWER_PAUSE)).isEqualTo(SendButtonMode.ANSWER)
    }

    @Test
    fun `a send that will steer the running reply says so`() {
        assertThat(mode(target = DuringRunSendTarget.STEER)).isEqualTo(SendButtonMode.STEER)
    }

    @Test
    fun `a send that will queue a follow-up says so`() {
        assertThat(mode(target = DuringRunSendTarget.QUEUE)).isEqualTo(SendButtonMode.QUEUE)
    }

    /**
     * The invariant behind the cases above: every target maps to its own face. A preference-derived
     * rule collapses two of them together, because a pause overrides the preference but not the icon.
     */
    @Test
    fun `every during-run target gets a distinct face`() {
        val faces = DuringRunSendTarget.entries.map { mode(target = it) }
        assertThat(faces.toSet().size).isEqualTo(DuringRunSendTarget.entries.size)
    }

    // ── The pre-existing rules still hold ─────────────────────────────────

    /** The "clear the box to reveal Stop" rule: nothing to send mid-run leaves Stop reachable. */
    @Test
    fun `a mid-run button with nothing to send is Stop`() {
        assertThat(mode(canQueue = false)).isEqualTo(SendButtonMode.STOP)
        assertThat(mode(canQueue = false, target = DuringRunSendTarget.ANSWER_PAUSE))
            .isEqualTo(SendButtonMode.STOP)
    }

    @Test
    fun `a queued edit commits instead of sending`() {
        assertThat(mode(isEditingQueued = true, target = DuringRunSendTarget.ANSWER_PAUSE))
            .isEqualTo(SendButtonMode.UPDATE)
    }

    /** A parked upload must never hide a mid-stream Stop; off-stream it becomes the spinner. */
    @Test
    fun `a send parked behind an upload keeps Stop while streaming`() {
        assertThat(mode(isAwaitingUploadSend = true)).isEqualTo(SendButtonMode.STOP)
        assertThat(mode(isStreaming = false, isAwaitingUploadSend = true))
            .isEqualTo(SendButtonMode.AWAITING)
    }

    @Test
    fun `an idle composer sends`() {
        assertThat(mode(isStreaming = false)).isEqualTo(SendButtonMode.SEND)
    }
}
