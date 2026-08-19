package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.selection_add_to_chat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

object AddToChatMenuKey

/**
 * Adds an "Add to chat" item to the selection toolbar of every text below this modifier (v0.8.7
 * quotes, upstream #13868): tapping it stages the selected excerpt as a pending quote chip.
 *
 * **Must stay a modifier, not a provider wrapper.** Foundation collects a menu's components by
 * walking the toolbar handler's ANCESTORS (`collectTextContextMenuData` → `traverseAncestors`),
 * and every `SelectionContainer` installs the platform toolbar provider *inside itself*
 * (`CommonContextMenuArea` → `ProvideDefaultPlatformTextContextMenuProviders`) — so a
 * `LocalTextContextMenuToolbarProvider` published above the thread is null when read there and
 * shadowed if written, and the item silently never reaches the toolbar.
 *
 * The item needs the SELECTED TEXT, which foundation exposes nowhere public (the hoisting overload
 * carries anchors, not text). The only conduit is the built-in Copy item's own onClick, so the
 * appended item drives that and lifts the text off the clipboard, restoring the previous clip
 * afterwards. The filter is how Copy is reached — a builder cannot read what has already been
 * collected — and it drops the item from any menu that has no Copy, so a text field's paste-only
 * menu cannot stage a stale clipboard.
 *
 * When [enabled] is false (pre-0.8.7 server, unknown version, assistants endpoint) nothing is
 * added.
 */
@Composable
internal fun Modifier.addToChatSelectionItem(
    enabled: Boolean,
    onAddToChat: (String) -> Unit,
): Modifier {
    if (!enabled) return this
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(Res.string.selection_add_to_chat)
    val currentOnAdd by rememberUpdatedState(onAddToChat)
    val capture = remember(clipboard, scope) { SelectionQuoteCapture(clipboard, scope) }
    return this
        .appendTextContextMenuComponents {
            capture.onMenuRebuilt()
            item(key = AddToChatMenuKey, label = label) {
                capture.stageSelection(this) { currentOnAdd(it) }
            }
        }
        .filterTextContextMenuComponents { component ->
            when {
                component !is TextContextMenuItem -> true
                component.key == TextContextMenuKeys.CopyKey -> {
                    capture.copyItem = component
                    true
                }
                component.key == AddToChatMenuKey -> capture.copyItem != null
                else -> true
            }
        }
}

/** Lifts the current selection off the clipboard by driving the menu's own Copy item. */
private class SelectionQuoteCapture(
    private val clipboard: Clipboard,
    private val scope: CoroutineScope,
) {
    var copyItem: TextContextMenuItem? = null

    /** Called once per menu build, before the filters see anything. */
    fun onMenuRebuilt() {
        copyItem = null
    }

    fun stageSelection(session: TextContextMenuSession, onAddToChat: (String) -> Unit) {
        val copyItem = copyItem ?: return
        scope.launch {
            val previous = runCatching { clipboard.getClipEntry() }.getOrNull()
            val startedAt = System.currentTimeMillis()
            with(copyItem) { session.onClick() }
            // The copy lands on the clipboard asynchronously; bounded poll for it. The write's own
            // timestamp is what identifies it — comparing against the PREVIOUS TEXT cannot tell
            // "the copy landed and happens to equal the old clip" from "the copy never landed",
            // and resolving that ambiguity by staging whatever the clipboard holds at timeout
            // quotes the user's unrelated previous clip (a password, an old snippet) into the
            // chat. ClipDescription.getTimestamp is API 26, which is minSdk.
            var captured: String? = null
            for (attempt in 0 until CAPTURE_POLLS) {
                delay(CAPTURE_POLL_MS)
                val entry = runCatching { clipboard.getClipEntry() }.getOrNull()
                val text = entry?.firstText()
                val writtenAt = entry?.clipData?.description?.timestamp ?: 0L
                if (!text.isNullOrEmpty() && writtenAt >= startedAt) {
                    captured = text
                    break
                }
            }
            // Timed out: the copy never observably landed, so there is nothing of the user's
            // selection to stage. Staging the stale clip instead would quote text they never
            // selected — say nothing rather than the wrong thing.
            if (captured == null) return@launch
            onAddToChat(captured)
            runCatching { clipboard.setClipEntry(previous) }
        }
    }

    private fun androidx.compose.ui.platform.ClipEntry.firstText(): String? =
        clipData.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    private companion object {
        const val CAPTURE_POLLS = 20
        const val CAPTURE_POLL_MS = 25L
    }
}
