package com.garfiec.librechat.feature.chat.components

import android.content.ClipboardManager
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.garfiec.librechat.core.model.ContentType
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.model.content.MessageContentPart
import com.garfiec.librechat.core.ui.theme.LibreChatTheme
import com.garfiec.librechat.feature.chat.util.MessageNode
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks for in-message text selection (issue #311).
 *
 * Compose exposes no semantics for SelectionContainer selection state on plain
 * BasicText, so assertions go through the composition locals the selection
 * machinery talks to:
 *  - [RecordingContextMenuProvider] via [LocalTextContextMenuToolbarProvider] —
 *    with foundation's `isNewContextMenuEnabled` default, the selection toolbar
 *    is requested through this provider (NOT the legacy `LocalTextToolbar`);
 *    `showTextContextMenu` runs exactly when a non-empty selection exists, and
 *    its data carries the live copy / select-all actions.
 *  - [RecordingClipboard] via [LocalClipboard] — invoking the copy item routes
 *    the selected text here, which both proves WHAT was selected and sidesteps
 *    the API 29+ background clipboard-read restriction.
 *
 * The host is the real gesture stack: MessageList (LazyColumn) → MessageBubble
 * (whole-bubble clickable) → MessageContentAndActions (SelectionContainer). No
 * ViewModel, no Koin — plain data in.
 */
@RunWith(AndroidJUnit4::class)
class MessageSelectionInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var menuProvider: RecordingContextMenuProvider
    private lateinit var clipboard: RecordingClipboard
    private lateinit var uriHandler: RecordingUriHandler

    @Before
    fun setUp() {
        menuProvider = RecordingContextMenuProvider()
        clipboard = RecordingClipboard()
        uriHandler = RecordingUriHandler()
    }

    // ─── Harness ────────────────────────────────────────────────────

    /**
     * Stands in for the platform selection-toolbar provider. [showTextContextMenu]
     * suspends for as long as the menu is "shown" (the selection machinery cancels
     * it to hide), so the recorded [lastDataProvider] stays live-queryable.
     */
    class RecordingContextMenuProvider : TextContextMenuProvider {
        var shownCount = 0

        /** Incremented when the machinery cancels a show — i.e. when the selection went away. */
        var hiddenCount = 0
        var lastDataProvider: TextContextMenuDataProvider? = null

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            shownCount++
            lastDataProvider = dataProvider
            try {
                awaitCancellation()
            } finally {
                hiddenCount++
            }
        }

        fun item(key: Any): TextContextMenuItem? = lastDataProvider
            ?.data()
            ?.components
            ?.filterIsInstance<TextContextMenuItem>()
            ?.firstOrNull { it.key == key }
    }

    private object NoOpSession : TextContextMenuSession {
        override fun close() = Unit
    }

    class RecordingClipboard : Clipboard {
        var lastEntry: ClipEntry? = null

        val lastText: String?
            get() = lastEntry?.clipData?.let { clip ->
                (0 until clip.itemCount).joinToString("") { clip.getItemAt(it).text?.toString().orEmpty() }
            }

        override suspend fun getClipEntry(): ClipEntry? = lastEntry

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            lastEntry = clipEntry
        }

        override val nativeClipboard: ClipboardManager
            get() = InstrumentationRegistry.getInstrumentation().targetContext
                .getSystemService(ClipboardManager::class.java)
    }

    class RecordingUriHandler : UriHandler {
        val opened = mutableListOf<String>()
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    private fun userMessage(id: String, text: String) = Message(
        messageId = id,
        conversationId = CONVO,
        text = text,
        isCreatedByUser = true,
    )

    private fun assistantMessage(id: String, text: String) = Message(
        messageId = id,
        conversationId = CONVO,
        text = text,
        isCreatedByUser = false,
        sender = SENDER,
    )

    private fun setChat(
        vararg messages: Message,
        isStreaming: Boolean = false,
        streamingContent: String = "",
    ) {
        composeRule.setContent {
            // ParsedMarkdownCache is normally provided by ChatRoot; the harness renders
            // MessageList directly, so it supplies its own.
            val markdownCache = remember { ParsedMarkdownCache() }
            CompositionLocalProvider(
                LocalTextContextMenuToolbarProvider provides menuProvider,
                LocalClipboard provides clipboard,
                LocalUriHandler provides uriHandler,
                LocalParsedMarkdownCache provides markdownCache,
            ) {
                LibreChatTheme {
                    MessageList(
                        displayMessages = messages.map { MessageNode(it, emptyList(), 0, 1) },
                        isStreaming = isStreaming,
                        streamingContent = streamingContent,
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

    /** Waits out the markdown parse: the node bearing [text] must be on screen. */
    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun longPressText(text: String) {
        awaitText(text)
        composeRule.onNodeWithText(text, substring = true, useUnmergedTree = true)
            .performTouchInput { longClick() }
        composeRule.waitForIdle()
    }

    private fun awaitSelectionMenu() {
        composeRule.waitUntil(timeoutMillis = 5_000) { menuProvider.shownCount > 0 }
    }


    private fun invokeMenuItem(key: Any, label: String) {
        val item = menuProvider.item(key)
        assertNotNull("selection toolbar offered no $label action", item)
        composeRule.runOnIdle { item!!.onClick(NoOpSession) }
        composeRule.waitForIdle()
    }

    /** A long-press selects a word, so anything shorter means the gesture degraded. */
    private fun isWordLike(text: String): Boolean =
        text.length >= 3 && text.all { it.isLetterOrDigit() || it == '_' }

    private fun copyViaMenu(): String {
        invokeMenuItem(TextContextMenuKeys.CopyKey, "copy")
        val text = clipboard.lastText
        assertNotNull("copy wrote nothing to the clipboard", text)
        return text!!
    }

    // ─── Tests ──────────────────────────────────────────────────────

    @Test
    fun longPressOnProseSelectsWordAndCopies() {
        setChat(assistantMessage("m1", "Alpha beta gamma delta epsilon."))

        longPressText("beta gamma")
        awaitSelectionMenu()

        val copied = copyViaMenu().trim()
        assertTrue(
            "expected a word from the prose, got \"$copied\"",
            "Alpha beta gamma delta epsilon.".contains(copied),
        )
        // A long-press selects a whole word. Without this, a degraded gesture that caught a
        // single stray character would still satisfy the substring check above.
        assertTrue("expected a whole word, got \"$copied\"", isWordLike(copied))
    }


    @Test
    fun longPressDoesNotToggleActionRow_tapDoes() {
        setChat(assistantMessage("m1", "Some selectable reply text here."))

        longPressText("selectable reply")
        awaitSelectionMenu()
        composeRule.onNodeWithTag("message_actions", useUnmergedTree = true).assertDoesNotExist()

        // A tap both reveals the action row and drops the selection, and the two are one
        // gesture by construction: the tap clears nothing itself; the action row appearing
        // relayouts the message, and THAT drops the selection. Suppressing the toggle to
        // "protect" the selection strands it with no way to dismiss it.
        val hiddenBefore = menuProvider.hiddenCount
        composeRule.onNodeWithText("selectable reply", substring = true, useUnmergedTree = true)
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("message_actions", useUnmergedTree = true).assertExists()
        composeRule.waitUntil(timeoutMillis = 5_000) { menuProvider.hiddenCount > hiddenBefore }

        val shownBefore = menuProvider.shownCount
        composeRule.onNodeWithText("selectable reply", substring = true, useUnmergedTree = true)
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("message_actions", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("selection came back after being dismissed", shownBefore, menuProvider.shownCount)
    }

    @Test
    fun selectAllSpansParagraphsWithinOneMessage() {
        // Two paragraphs render as two BasicText nodes sharing one per-message
        // SelectionRegistrar; select-all must sweep both.
        setChat(
            assistantMessage("m1", "Opening paragraph words.\n\nClosing paragraph words."),
        )

        longPressText("Opening paragraph")
        awaitSelectionMenu()
        invokeMenuItem(TextContextMenuKeys.SelectAllKey, "select-all")

        val copied = copyViaMenu()
        assertTrue("missing first paragraph in \"$copied\"", copied.contains("Opening paragraph"))
        assertTrue("missing second paragraph in \"$copied\"", copied.contains("Closing paragraph"))
    }

    @Test
    fun selectAllExcludesChromeInsideMessage() {
        // A fenced block contributes a "kotlin" language badge; select-all must
        // sweep prose + code but never the badge (DisableSelection chrome).
        setChat(
            assistantMessage(
                "m1",
                "Look at this:\n\n```kotlin\nval uniqueTokenXyz = 42\n```\n\nDone.",
            ),
        )

        longPressText("uniqueTokenXyz")
        awaitSelectionMenu()
        invokeMenuItem(TextContextMenuKeys.SelectAllKey, "select-all")

        val copied = copyViaMenu()
        assertTrue("code text missing from \"$copied\"", copied.contains("uniqueTokenXyz"))
        assertTrue("prose missing from \"$copied\"", copied.contains("Look at this"))
        // "kotlin" appears rendered only as the CodeBlock header badge (the fence
        // marker itself is markdown syntax, never rendered) — so its presence in the
        // copy means DisableSelection chrome leaked into the sweep.
        assertTrue("chrome badge leaked into selection: \"$copied\"", !copied.contains("kotlin"))
    }

    @Test
    fun codeBlockTextIsSelectableDespiteHorizontalScroll() {
        setChat(
            assistantMessage("m1", "Intro.\n\n```\nval uniqueTokenXyz = compute(1, 2)\n```"),
        )

        // A long-press is stationary, so the horizontalScroll wrapper never
        // contends for the gesture (handle-dragging inside the scroller is the
        // manual-pass check).
        longPressText("uniqueTokenXyz")
        awaitSelectionMenu()

        val copied = copyViaMenu().trim()
        assertTrue(
            "expected a word from the code line, got \"$copied\"",
            "val uniqueTokenXyz = compute(1, 2)".contains(copied) && isWordLike(copied),
        )
    }

    @Test
    fun quotedExcerptIsSelectable() {
        setChat(
            userMessage("m1", "What about this?").copy(quotes = listOf("Quoted excerpt from earlier")),
        )

        longPressText("Quoted excerpt")
        awaitSelectionMenu()

        val copied = copyViaMenu().trim()
        assertTrue(
            "expected a word from the quote, got \"$copied\"",
            "Quoted excerpt from earlier".contains(copied) && isWordLike(copied),
        )
    }

    @Test
    fun incompleteArtifactSourceIsSelectable() {
        // A truncated artifact renders its source through CodeBlock, so the fixture below is a
        // directive that never closes.
        setChat(
            assistantMessage("m1", "").copy(
                content = listOf(
                    MessageContentPart(
                        type = ContentType.TEXT,
                        text = "Here it is:\n\n:::artifact{identifier=demo type=text/markdown title=Demo}\n" +
                            "```md\npartialArtifactToken lives here\n```",
                    ),
                ),
            ),
        )

        longPressText("partialArtifactToken")
        awaitSelectionMenu()

        val copied = copyViaMenu().trim()
        assertTrue(
            "expected a word from the artifact source, got \"$copied\"",
            "partialArtifactToken lives here".contains(copied) && isWordLike(copied),
        )
    }


    @Test
    fun streamingBubbleIsNotSelectable() {
        setChat(
            userMessage("m1", "A question."),
            isStreaming = true,
            streamingContent = "Streaming reply body still growing",
        )

        longPressText("Streaming reply body")
        // Asserting the counter straight away would race the show: every positive test in this
        // file waits seconds for the same signal. Give it a comparable window to stay at zero.
        val toolbarAppeared = runCatching {
            composeRule.waitUntil(timeoutMillis = 3_000) { menuProvider.shownCount > 0 }
        }.isSuccess
        assertFalse("selection toolbar must not appear on the streaming bubble", toolbarAppeared)
    }

    @Test
    fun linkClickStillOpensInsideSelectionContainer() {
        // The whole paragraph is the link, so clicking the node center hits it.
        setChat(assistantMessage("m1", "[Docs](https://example.com/docs)"))

        awaitText("Docs")
        composeRule.onNodeWithText("Docs", substring = true, useUnmergedTree = true)
            .performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("https://example.com/docs"), uriHandler.opened)
    }

    private companion object {
        const val CONVO = "convo-1"
        const val SENDER = "TestBot"
        const val USER_NAME = "TestUser"
    }
}
