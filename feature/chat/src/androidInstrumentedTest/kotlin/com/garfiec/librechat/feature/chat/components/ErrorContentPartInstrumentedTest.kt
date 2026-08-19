package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.core.ui.theme.LibreChatTheme
import com.garfiec.librechat.feature.chat.util.MessageNode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The in-band half of the typed-error contract, driven through the real render path.
 *
 * `StreamErrorTypeTest` pins the classifier; a classifier that matches the payload proves nothing
 * about whether the render path calls it. Only rendering an `error` content part shows that the
 * thread and the snackbar say the same thing about the same failure.
 */
@RunWith(AndroidJUnit4::class)
class ErrorContentPartInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setError(part: MessageContentPart) {
        composeRule.setContent {
            val markdownCache = remember { ParsedMarkdownCache() }
            CompositionLocalProvider(LocalParsedMarkdownCache provides markdownCache) {
                LibreChatTheme {
                    MessageList(
                        displayMessages = listOf(
                            MessageNode(
                                Message(
                                    messageId = "m1",
                                    conversationId = CONVO,
                                    // rc1 persists a model-not-found turn with error:false and no
                                    // text — the content part is the whole record of the failure.
                                    text = "",
                                    error = false,
                                    isCreatedByUser = false,
                                    content = listOf(part),
                                ),
                                emptyList(),
                                0,
                                1,
                            ),
                        ),
                        isStreaming = false,
                        streamingContent = "",
                        onSiblingNavigation = { _, _ -> },
                        onEditMessage = {},
                        onRegenerateMessage = {},
                        onCopyMessage = {},
                        userName = USER_NAME,
                    )
                }
            }
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun isShowing(text: String): Boolean =
        composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aModelNotFoundErrorPartRendersTheActionableCopy() {
        setError(MessageContentPart(type = ContentType.ERROR, error = MODEL_NOT_FOUND_PAYLOAD))

        awaitText("The selected model is unavailable")
        assertTrue(
            "the raw provider payload is still on screen",
            !isShowing("langchain.com") && !isShowing("invalid_request_error"),
        )
    }

    @Test
    fun anUnrecognizedErrorPartKeepsTheServersOwnText() {
        // The contract the classifier documents, held at the render boundary too: a code this
        // build has never heard of must not be swallowed into a generic card.
        setError(MessageContentPart(type = ContentType.ERROR, error = UNKNOWN_PAYLOAD))

        awaitText(UNKNOWN_PAYLOAD)
    }

    @Test
    fun anErrorPartCarryingOnlyTextIsClassifiedToo() {
        // `error` is the usual carrier, but the render site falls back to `text`, and a payload
        // that arrives there must not skip classification on the way through.
        setError(MessageContentPart(type = ContentType.ERROR, text = MODEL_NOT_FOUND_PAYLOAD))

        awaitText("The selected model is unavailable")
    }

    private companion object {
        const val CONVO = "convo-1"
        const val USER_NAME = "TestUser"

        /** The rc1 shape: provider JSON with the LangChain troubleshooting URL in the prose. */
        const val MODEL_NOT_FOUND_PAYLOAD =
            """{"error":{"message":"404 The model does not exist or you do not have access to it. """ +
                """(invalid_request_error) Troubleshooting URL: """ +
                """https://js.langchain.com/docs/troubleshooting/errors/MODEL_NOT_FOUND/"}}"""

        const val UNKNOWN_PAYLOAD = "The provider is temporarily unavailable. Try again shortly."
    }
}
