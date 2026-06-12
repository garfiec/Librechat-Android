package com.garfiec.librechat.feature.chat.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the title-generation gate contract for `handleFinal`.
 *
 * The load-bearing case is the handed-off chat: navigation to Chat(id) fires at the
 * `created` event, so the VM that sees a brand-new chat's first Final has
 * `isNewConversation = false` and only `isHandedOffNewChat` keeps gen_title alive.
 * Removing either flag from [shouldRequestTitleGeneration] must fail here.
 */
class TitleGenerationGateTest {

    @Test
    fun handedOffNewChatWithPlaceholderTitleRequestsGeneration() {
        assertThat(
            shouldRequestTitleGeneration(
                isNewConversation = false,
                isHandedOffNewChat = true,
                currentTitle = PLACEHOLDER_CONVERSATION_TITLE,
                alreadyRequested = false,
            ),
        ).isTrue()
    }

    @Test
    fun landingVmNewConversationRequestsGeneration() {
        // Comparison mode defers navigation, so the landing VM (no conversationId)
        // can still be the one handling the first Final.
        assertThat(
            shouldRequestTitleGeneration(
                isNewConversation = true,
                isHandedOffNewChat = false,
                currentTitle = null,
                alreadyRequested = false,
            ),
        ).isTrue()
    }

    @Test
    fun existingConversationWithPlaceholderTitleDoesNotRequestGeneration() {
        // Deliberate: gen_title long-polls ~15s before 404-ing when no title will
        // generate, so placeholder title alone must not trigger it on every send.
        assertThat(
            shouldRequestTitleGeneration(
                isNewConversation = false,
                isHandedOffNewChat = false,
                currentTitle = PLACEHOLDER_CONVERSATION_TITLE,
                alreadyRequested = false,
            ),
        ).isFalse()
    }

    @Test
    fun alreadyRequestedNeverRequestsAgain() {
        assertThat(
            shouldRequestTitleGeneration(
                isNewConversation = false,
                isHandedOffNewChat = true,
                currentTitle = PLACEHOLDER_CONVERSATION_TITLE,
                alreadyRequested = true,
            ),
        ).isFalse()
    }

    @Test
    fun realTitleDoesNotRequestGeneration() {
        assertThat(
            shouldRequestTitleGeneration(
                isNewConversation = false,
                isHandedOffNewChat = true,
                currentTitle = "Kotlin coroutine cancellation",
                alreadyRequested = false,
            ),
        ).isFalse()
    }

    @Test
    fun blankTitleCountsAsNeedingGeneration() {
        assertThat(
            shouldRequestTitleGeneration(
                isNewConversation = true,
                isHandedOffNewChat = false,
                currentTitle = "  ",
                alreadyRequested = false,
            ),
        ).isTrue()
    }
}
