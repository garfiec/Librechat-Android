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

/** Menu key for the appended item; also what tests can look it up by. */
object AddToChatMenuKey

/**
 * Adds an "Add to chat" item to the selection toolbar of every text below this modifier (v0.8.7
 * quotes, upstream #13868): tapping it stages the selected excerpt as a pending quote chip.
 *
 * **A modifier, not a provider wrapper.** Foundation builds a menu's data by walking the toolbar
 * handler's ANCESTORS (`collectTextContextMenuData` → `traverseAncestors`), and every
 * `SelectionContainer` installs the platform toolbar provider *inside itself*
 * (`CommonContextMenuArea` → `ProvideDefaultPlatformTextContextMenuProviders`). So a
 * `LocalTextContextMenuToolbarProvider` published above the thread is both null when read there
 * and shadowed if written — which is why the earlier wrapper stood down silently and the item
 * never reached the stock floating toolbar. Contributing components from an ancestor is the
 * supported seam, and it leaves the platform's own toolbar (and its text-classification items)
 * exactly as they are.
 *
 * The item needs the SELECTED TEXT, and foundation exposes no public read of a
 * `SelectionContainer`'s selection (the hoisting overload carries anchors, not text). The one
 * public conduit is the built-in Copy item's own onClick, so the appended item drives that and
 * lifts the text off the clipboard: snapshot the previous clip, invoke Copy (which also dismisses
 * the toolbar), poll briefly for the write to land — the selection machinery performs it
 * asynchronously — then stage the excerpt and restore the previous clip so the user's clipboard
 * is left untouched.
 *
 * Reaching Copy is what the filter is for: the builder cannot read the components already
 * collected, but a filter is handed each of them. It runs after every builder has contributed, so
 * the reset lives in the builder and the item is dropped from any menu that turns out to have no
 * Copy — a text field's paste-only menu, where there is no selection to stage and a stale capture
 * would otherwise quote whatever the clipboard happened to hold.
 *
 * When [enabled] is false (pre-0.8.7 server, unknown version, assistants endpoint) nothing is
 * added and the platform toolbar is left exactly as it was.
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
    /** The Copy item of the menu being built, or null if this menu has none. */
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
            // Leave the user's clipboard the way we found it.
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
