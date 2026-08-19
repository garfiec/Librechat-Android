package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.garfiec.librechat.core.model.AskUserQuestionRequest
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.PendingAction
import com.garfiec.librechat.core.model.PendingActionPayload
import com.garfiec.librechat.core.model.PendingActionTypes
import com.garfiec.librechat.core.ui.theme.LibreChatTheme
import com.garfiec.librechat.feature.chat.util.buildActiveMessagePath
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The streaming follower must stand down while a run is paused for human review.
 *
 * A pause does not end the run: `isStreaming` stays true across it, so the per-frame follower in
 * [MessageList] keeps pinning the list's tail to the bottom while the output it exists to follow
 * has stopped. That matters because the pause card is the thing that grows underneath — a long
 * question laying out, options expanding, an answer field taking a second line — and every one of
 * those pushes the tail down. A live follower chases it, so the top of the card (the question
 * itself) walks off screen while the user is reading it.
 *
 * Both tests apply the same stimulus — the last item grows — and differ only in whether a pause is
 * outstanding, so the control proves the measurement can actually detect chasing. Position is read
 * from the anchor's own bounds rather than from `LazyListState`, which [MessageList] owns
 * internally and does not expose; the anchor stays composed either way because a LazyColumn item
 * composes whole while any part of it is on screen.
 */
@RunWith(AndroidJUnit4::class)
class MessageListPauseScrollInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Grown after the first frame; recomposition feeds it back into the list. */
    private var streamingContent by mutableStateOf(SHORT_STREAM)
    private var questionDescription by mutableStateOf(SHORT_DESCRIPTION)
    private var paused by mutableStateOf(false)

    /**
     * The follower runs off `withFrameNanos`, which leaves Compose permanently non-idle for the
     * whole run — every finder call syncs on idle first, so on the automatic clock they all time
     * out. Driving the clock by hand is the only way to observe a frame loop at all.
     */
    @Before
    fun driveTheClockByHand() {
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun theFollowerChasesAGrowingTailWhileOutputStreams() {
        setChat()
        val anchorTop = settledTop(SHORT_STREAM)

        composeRule.runOnUiThread { streamingContent = LONG_STREAM }
        advanceFrames(CHASE_FRAMES)

        // The control: with no pause the follower is expected to chase, which is what proves the
        // measurement below can tell chasing from stillness.
        val moved = anchorTop - topOf(SHORT_STREAM)
        assertTrue("the follower did not chase a growing reply; moved ${moved}px", moved > CHASE_SLACK_PX)
    }

    @Test
    fun aPauseStopsTheFollowerChasingTheCardAsItGrows() {
        paused = true
        setChat()
        // Past the one-shot scroll that brings a new card into view — that one is wanted.
        val anchorTop = settledTop(QUESTION)

        composeRule.runOnUiThread { questionDescription = LONG_DESCRIPTION }
        advanceFrames(CHASE_FRAMES)

        // Same growth, same number of frames the control needed. Nothing may move.
        val moved = anchorTop - topOf(QUESTION)
        assertTrue("the follower chased the pause card by ${moved}px", abs(moved) < STILL_TOLERANCE_PX)
    }

    // ── harness ───────────────────────────────────────────────────────────────

    private fun setChat() {
        composeRule.setContent {
            // ParsedMarkdownCache is normally provided by ChatRoot; the harness renders
            // MessageList directly, so it supplies its own.
            val markdownCache = remember { ParsedMarkdownCache() }
            CompositionLocalProvider(LocalParsedMarkdownCache provides markdownCache) {
                LibreChatTheme {
                    MessageList(
                        displayMessages = buildActiveMessagePath(THREAD),
                        isStreaming = true,
                        streamingContent = streamingContent,
                        onSiblingNavigation = { _, _ -> },
                        onEditMessage = {},
                        onRegenerateMessage = {},
                        onCopyMessage = {},
                        // Rebuilt on every recomposition so a growing description reaches the
                        // card, while actionId stays put — a new id would re-fire the one-shot
                        // scroll and the test would be measuring that instead of the follower.
                        pendingAction = if (paused) askPause(questionDescription) else null,
                    )
                }
            }
        }
    }

    private fun topOf(text: String): Float =
        composeRule.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot.top

    /**
     * The anchor's position once the opening scroll is done.
     *
     * Both cases open with a scroll of their own — the jump that arms a run, the animation that
     * brings a new pause card into view — and a baseline read mid-flight would score that opening
     * scroll as the chase under test.
     */
    private fun settledTop(text: String): Float {
        advanceFrames(SETTLE_FRAMES)
        return topOf(text)
    }

    private fun advanceFrames(count: Int) {
        repeat(count) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.waitForIdle()
    }

    private fun askPause(description: String) = PendingAction(
        actionId = "action-1",
        conversationId = CONVO,
        payload = PendingActionPayload(
            type = PendingActionTypes.ASK_USER_QUESTION,
            question = AskUserQuestionRequest(question = QUESTION, description = description),
        ),
    )

    private companion object {
        const val CONVO = "convo-1"
        const val QUESTION = "Which region should the cluster live in?"

        /**
         * Long enough that the thread fills the viewport and the list can actually scroll.
         *
         * Parented into a chain rather than left flat: the list keys its items by the tree parent,
         * so a flat thread hands LazyColumn the same NO_PARENT key twelve times and measurement
         * throws before any of this can be observed.
         */
        val THREAD = (1..12).map { index ->
            Message(
                messageId = "m$index",
                conversationId = CONVO,
                parentMessageId = if (index == 1) null else "m${index - 1}",
                text = "Turn $index of the conversation, long enough to occupy a line or two.",
                isCreatedByUser = index % 2 == 1,
                sender = "TestBot",
            )
        }

        const val SHORT_STREAM = "Working on it"
        val LONG_STREAM = SHORT_STREAM + (1..60).joinToString("") { "\nreply line $it" }

        const val SHORT_DESCRIPTION = "Pick one."
        val LONG_DESCRIPTION = SHORT_DESCRIPTION + (1..60).joinToString("") { "\ndetail line $it" }

        /** Well past a single eased step, so a follower that runs at all clears it. */
        const val CHASE_SLACK_PX = 40f

        /** A list that never scrolled leaves the anchor exactly where it was; this is rounding. */
        const val STILL_TOLERANCE_PX = 2f

        /** Enough frames for the opening scroll to land and for a live follower to show itself. */
        const val SETTLE_FRAMES = 150
        const val CHASE_FRAMES = 150
    }
}
