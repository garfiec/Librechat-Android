package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.AskUserQuestionItem
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
    private var batched by mutableStateOf(false)

    /** Stands in for the Scaffold's imePadding: raising it shrinks the list's viewport. */
    private var keyboardInset by mutableStateOf(0.dp)

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

    /**
     * The keyboard must not cost the user the field they just tapped.
     *
     * The viewport-shrink handler jumped to the tail of the last item, which during a pause is the
     * card — so focusing any field but the last one scrolled it out of sight the moment the
     * keyboard came up. The inset is raised directly rather than by summoning a real IME, because
     * the handler keys on the viewport shrinking and nothing else.
     */
    @Test
    fun theKeyboardLeavesTheFocusedAnswerFieldOnScreen() {
        paused = true
        batched = true
        setChat()
        advanceFrames(SETTLE_FRAMES)

        // Read the card from the top, the way its author intended, and answer the first question.
        focusAnswerField(index = 0, anchor = FIRST_QUESTION)

        val before = fieldVsViewport(0)
        assertTrue("the field was already off screen before the keyboard: $before", before.isVisible)

        composeRule.runOnUiThread { keyboardInset = KEYBOARD_HEIGHT }
        advanceFrames(CHASE_FRAMES)

        val after = fieldVsViewport(0)
        assertTrue("the keyboard pushed the focused field off screen: $after", after.isVisible)
    }

    /**
     * The other half of leaving this to foundation: a field low enough that the keyboard really
     * does cover it still has to be lifted clear, and nothing in this file does that any more.
     */
    @Test
    fun theKeyboardLiftsTheLastAnswerFieldClear() {
        paused = true
        batched = true
        setChat()
        advanceFrames(SETTLE_FRAMES)

        focusAnswerField(index = BATCH.lastIndex, anchor = LAST_QUESTION)

        composeRule.runOnUiThread { keyboardInset = KEYBOARD_HEIGHT }
        advanceFrames(CHASE_FRAMES)

        val after = fieldVsViewport(BATCH.lastIndex)
        assertTrue("the keyboard covered the focused field: $after", after.isVisible)
    }

    // ── harness ───────────────────────────────────────────────────────────────

    private fun setChat() {
        composeRule.setContent {
            // ParsedMarkdownCache is normally provided by ChatRoot; the harness renders
            // MessageList directly, so it supplies its own.
            val markdownCache = remember { ParsedMarkdownCache() }
            CompositionLocalProvider(LocalParsedMarkdownCache provides markdownCache) {
                LibreChatTheme {
                    Box(Modifier.fillMaxSize().padding(bottom = keyboardInset)) {
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
    }

    private fun focusAnswerField(index: Int, anchor: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(anchor, substring = true))
        advanceFrames(FOCUS_FRAMES)
        composeRule.onAllNodes(hasSetTextAction())[index].performClick()
        advanceFrames(FOCUS_FRAMES)
    }

    /** Where the focused answer field sits relative to the list's own (clipping) bounds. */
    private fun fieldVsViewport(index: Int): Placement {
        val field = composeRule.onAllNodes(hasSetTextAction())[index].fetchSemanticsNode().boundsInRoot
        val list = composeRule.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        return Placement(field.top, field.bottom, list.top, list.bottom)
    }

    data class Placement(
        val fieldTop: Float,
        val fieldBottom: Float,
        val listTop: Float,
        val listBottom: Float,
    ) {
        /**
         * A node clipped entirely out of the list reports `Rect.Zero`, which satisfies any naive
         * bounds comparison — so an empty rect has to be rejected outright or this asserts nothing.
         */
        val isVisible: Boolean
            get() = fieldBottom > fieldTop && fieldTop >= listTop && fieldBottom <= listBottom
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
            // A batch renders a field per question, which is the only shape that puts a field
            // anywhere but flush against the bottom of the card.
            questions = if (batched) BATCH else null,
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
        const val FOCUS_FRAMES = 60

        val KEYBOARD_HEIGHT: Dp = 340.dp

        const val FIRST_QUESTION = "Which region should the cluster live in?"
        const val LAST_QUESTION = "Follow-up question number 4?"
        val BATCH = (1..4).map { index ->
            AskUserQuestionItem(
                id = "q$index",
                question = if (index == 1) FIRST_QUESTION else "Follow-up question number $index?",
                description = "Some extra detail for question $index, long enough to take a line.",
            )
        }
    }
}
