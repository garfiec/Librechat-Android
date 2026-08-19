package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuData
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.selection_add_to_chat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Menu key for the appended item; also what tests can look it up by. */
object AddToChatMenuKey

/**
 * Adds an "Add to chat" item to the selection toolbar of every [content] descendant (v0.8.7
 * quotes, upstream #13868): tapping it stages the selected excerpt as a pending quote chip.
 *
 * Wraps the platform [TextContextMenuProvider] rather than using
 * `Modifier.appendTextContextMenuComponents` because the appended item needs the SELECTED TEXT,
 * and foundation exposes no public read of a `SelectionContainer`'s selection (the hoisting
 * overload and `Selection` itself are internal as of CMP 1.11.0-beta03). The one public conduit
 * is the built-in Copy item's own onClick, so the appended item drives that and lifts the text
 * off the clipboard: snapshot the previous clip, invoke Copy (which also dismisses the toolbar),
 * poll briefly for the write to land — the selection machinery performs it asynchronously — then
 * stage the excerpt and restore the previous clip so the user's clipboard is left untouched.
 *
 * When [enabled] is false (pre-0.8.7 server, unknown version, assistants endpoint) the platform
 * toolbar is left exactly as it was.
 */
@Composable
internal fun AddToChatSelectionMenu(
    enabled: Boolean,
    onAddToChat: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val platform = LocalTextContextMenuToolbarProvider.current
    if (!enabled || platform == null) {
        content()
        return
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(Res.string.selection_add_to_chat)
    val currentOnAdd = rememberUpdatedState(onAddToChat)
    val wrapper = remember(platform, clipboard, label) {
        AddToChatMenuProvider(platform, clipboard, scope, label) { currentOnAdd.value(it) }
    }
    CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides wrapper, content = content)
}

private class AddToChatMenuProvider(
    private val platform: TextContextMenuProvider,
    private val clipboard: Clipboard,
    private val scope: CoroutineScope,
    private val label: String,
    private val onAddToChat: (String) -> Unit,
) : TextContextMenuProvider {

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        platform.showTextContextMenu(
            object : TextContextMenuDataProvider by dataProvider {
                override fun data(): TextContextMenuData = augment(dataProvider.data())
            },
        )
    }

    private fun augment(data: TextContextMenuData): TextContextMenuData {
        // No Copy item means no selection to lift (an editable field's cut-only menu, or a menu
        // this provider has already augmented elsewhere) — leave the data untouched.
        val copyItem = data.components.filterIsInstance<TextContextMenuItem>()
            .firstOrNull { it.key == TextContextMenuKeys.CopyKey }
            ?: return data
        val addToChat = TextContextMenuItem(key = AddToChatMenuKey, label = label) {
            captureSelection(copyItem, this)
        }
        return TextContextMenuData(data.components + addToChat)
    }

    private fun captureSelection(copyItem: TextContextMenuItem, session: TextContextMenuSession) {
        scope.launch {
            val previous = runCatching { clipboard.getClipEntry() }.getOrNull()
            val previousText = previous?.firstText()
            with(copyItem) { session.onClick() }
            // The copy lands on the clipboard asynchronously; bounded poll for it.
            var captured: String? = null
            for (attempt in 0 until CAPTURE_POLLS) {
                delay(CAPTURE_POLL_MS)
                val text = runCatching { clipboard.getClipEntry() }.getOrNull()?.firstText()
                if (!text.isNullOrEmpty() && (previousText == null || text != previousText)) {
                    captured = text
                    break
                }
            }
            if (captured != null) {
                onAddToChat(captured)
                // Leave the user's clipboard the way we found it.
                runCatching { clipboard.setClipEntry(previous) }
                return@launch
            }
            // Timed out — most likely the selection EQUALS the previous clip (copying the same
            // text writes an identical entry). Stage whatever the clipboard holds now; there is
            // nothing to restore because nothing observably changed.
            val fallback = runCatching { clipboard.getClipEntry() }.getOrNull()?.firstText()
            if (!fallback.isNullOrEmpty()) onAddToChat(fallback)
        }
    }

    private fun androidx.compose.ui.platform.ClipEntry.firstText(): String? =
        clipData.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    private companion object {
        const val CAPTURE_POLLS = 20
        const val CAPTURE_POLL_MS = 25L
    }
}
