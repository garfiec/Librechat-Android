package com.garfiec.librechat.feature.chat.viewmodel.delegate

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.data.endpoint.EndpointDispatch
import com.garfiec.librechat.feature.chat.viewmodel.ChatStateHandle
import com.garfiec.librechat.feature.chat.viewmodel.QueueHandle
import com.garfiec.librechat.feature.chat.viewmodel.ChatUiState
import com.garfiec.librechat.feature.chat.viewmodel.ComposerSnapshot
import com.garfiec.librechat.feature.chat.viewmodel.QueuedEditSession
import com.garfiec.librechat.feature.chat.viewmodel.QueuedMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.Test

/**
 * Behavior tests for [MessageQueueDelegate]: list mutation (enqueue/cancel/reorder/edit),
 * FIFO draining with the pause gate, and snapshot honesty (a spec is unaffected by later
 * state changes because the whole config was captured at queue time).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageQueueDelegateTest {

    private val stateFlow = MutableStateFlow(ChatUiState())
    private val scope = TestScope()
    private val stateHandle = ChatStateHandle(stateFlow, scope)

    private val sent = mutableListOf<QueuedMessage>()
    private val awaitSettleFlags = mutableListOf<Boolean>()
    private val droppedCounts = mutableListOf<Int>()
    private val activeAccount = InMemoryActiveAccountProvider(AccountState.Resolved(AccountId("srv:user-1")))
    private val delegate = MessageQueueDelegate(
        handle = QueueHandle(stateHandle),
        activeAccountProvider = activeAccount,
        sendWithSpec = { spec, awaitSettle ->
            sent.add(spec)
            awaitSettleFlags.add(awaitSettle)
        },
        onQueuedDropped = { droppedCounts.add(it) },
    )

    private fun spec(id: String, text: String = id, model: String? = "gpt-4") = QueuedMessage(
        localId = id,
        text = text,
        endpoint = "openAI",
        model = model,
        agentId = null,
        dispatch = EndpointDispatch(endpointType = null, key = null, modelDisplayLabel = null),
    )

    private val queue get() = stateFlow.value.messageQueue
    private val paused get() = stateFlow.value.isQueuePaused

    /** Puts the delegate's state into queued-edit mode (an item pulled out into the composer). */
    private fun enterEditMode(original: QueuedMessage = spec("edited")) {
        stateFlow.value = stateFlow.value.copy(
            composer = stateFlow.value.composer.copy(
                editingQueuedItem = QueuedEditSession(
                    original = original,
                    originalIndex = 0,
                    stashed = ComposerSnapshot(text = "", endpoint = "openAI", model = null),
                ),
            ),
        )
    }

    @Test
    fun `enqueue appends in FIFO order`() {
        delegate.enqueue(spec("a"))
        delegate.enqueue(spec("b"))

        assertThat(queue.map { it.localId }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `cancel removes the matching item`() {
        delegate.enqueue(spec("a"))
        delegate.enqueue(spec("b"))

        delegate.cancel("a")

        assertThat(queue.map { it.localId }).containsExactly("b")
    }

    @Test
    fun `takeForEdit removes and returns the item with its index`() {
        delegate.enqueue(spec("a"))
        delegate.enqueue(spec("b", text = "hello"))

        val taken = delegate.takeForEdit("b")

        assertThat(taken?.index).isEqualTo(1)
        assertThat(taken?.value?.text).isEqualTo("hello")
        assertThat(queue.map { it.localId }).containsExactly("a")
    }

    @Test
    fun `takeForEdit returns null for an unknown id`() {
        assertThat(delegate.takeForEdit("missing")).isNull()
    }

    @Test
    fun `takeForEdit leaves the pause flag untouched`() {
        delegate.enqueue(spec("a"))
        delegate.pause()

        delegate.takeForEdit("a")

        // The item is only borrowed for editing, so the queue stays paused.
        assertThat(paused).isTrue()
    }

    @Test
    fun `reinsert restores an item at the original index`() {
        delegate.enqueue(spec("a"))
        delegate.enqueue(spec("c"))
        val taken = delegate.takeForEdit("a") ?: error("missing")
        delegate.enqueue(spec("b"))

        delegate.reinsert(taken.index, taken.value)

        assertThat(queue.map { it.localId }).containsExactly("a", "c", "b").inOrder()
    }

    @Test
    fun `reinsert clamps an out-of-bounds index to the end`() {
        delegate.enqueue(spec("a"))

        delegate.reinsert(index = 9, item = spec("b"))

        assertThat(queue.map { it.localId }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `clearPauseIfEmpty clears pause only when the queue is empty`() {
        delegate.enqueue(spec("a"))
        delegate.pause()

        // Non-empty paused queue: no-op.
        delegate.clearPauseIfEmpty()
        assertThat(paused).isTrue()

        // takeForEdit empties the queue but leaves the pause set (item is only borrowed).
        delegate.takeForEdit("a")
        assertThat(paused).isTrue()

        delegate.clearPauseIfEmpty()
        assertThat(paused).isFalse()
    }

    @Test
    fun `reorder moves an item to the new index`() {
        delegate.enqueue(spec("a"))
        delegate.enqueue(spec("b"))
        delegate.enqueue(spec("c"))

        delegate.reorder(fromIndex = 0, toIndex = 2)

        assertThat(queue.map { it.localId }).containsExactly("b", "c", "a").inOrder()
    }

    @Test
    fun `reorder ignores out-of-bounds indices`() {
        delegate.enqueue(spec("a"))

        delegate.reorder(fromIndex = 0, toIndex = 5)

        assertThat(queue.map { it.localId }).containsExactly("a")
    }

    @Test
    fun `drainNext fires the head and removes it from the queue`() {
        delegate.enqueue(spec("a"))
        delegate.enqueue(spec("b"))

        delegate.drainNext()

        assertThat(sent.map { it.localId }).containsExactly("a")
        assertThat(queue.map { it.localId }).containsExactly("b")
    }

    @Test
    fun `drainNext is frozen while a queued item is being edited`() {
        delegate.enqueue(spec("a"))
        enterEditMode()

        delegate.drainNext()

        assertThat(sent).isEmpty()
        assertThat(queue.map { it.localId }).containsExactly("a")
    }

    @Test
    fun `pause registers while editing even when the queue is momentarily empty`() {
        // Mirrors a Stop during the edit of the sole queued item: it's pulled out (queue empty)
        // but the edit session is active, so the pause must still register.
        enterEditMode()

        delegate.pause()

        assertThat(paused).isTrue()
    }

    @Test
    fun `drainNext is a no-op when paused`() {
        delegate.enqueue(spec("a"))
        delegate.pause()

        delegate.drainNext()

        assertThat(sent).isEmpty()
        assertThat(queue.map { it.localId }).containsExactly("a")
    }

    @Test
    fun `drainNext is a no-op on an empty queue`() {
        delegate.drainNext()

        assertThat(sent).isEmpty()
    }

    @Test
    fun `pause is a no-op when the queue is empty`() {
        delegate.pause()

        assertThat(paused).isFalse()
    }

    @Test
    fun `resume lifts the pause and drains the head`() {
        delegate.enqueue(spec("a"))
        delegate.pause()
        assertThat(paused).isTrue()

        delegate.resume()

        assertThat(paused).isFalse()
        assertThat(sent.map { it.localId }).containsExactly("a")
        // Resume skips the settle-wait — the reply already landed while paused.
        assertThat(awaitSettleFlags).containsExactly(false)
    }

    @Test
    fun `auto-drain after Final requests the settle-wait`() {
        delegate.enqueue(spec("a"))

        delegate.drainNext()

        assertThat(awaitSettleFlags).containsExactly(true)
    }

    @Test
    fun `cancelling the last paused item clears the pause`() {
        delegate.enqueue(spec("a"))
        delegate.pause()

        delegate.cancel("a")

        assertThat(queue).isEmpty()
        assertThat(paused).isFalse()
    }

    @Test
    fun `drained spec retains its queue-time config after state changes`() {
        delegate.enqueue(spec("a", model = "gpt-4"))
        // Mutate live state after queueing — must not retro-edit the queued snapshot.
        stateFlow.value = stateFlow.value.copy(
            selection = stateFlow.value.selection.copy(selectedModel = "claude", selectedEndpoint = "anthropic"),
        )

        delegate.drainNext()

        assertThat(sent.single().model).isEqualTo("gpt-4")
        assertThat(sent.single().endpoint).isEqualTo("openAI")
    }

    @Test
    fun `drainNext drops items composed under a now-inactive account and signals the count`() {
        // Two items queued under account A, one under the live account. The user switched to the live
        // account (srv:user-1) since queueing the A items.
        delegate.enqueue(spec("a1").copy(accountId = "srv:user-A"))
        delegate.enqueue(spec("a2").copy(accountId = "srv:user-A"))
        delegate.enqueue(spec("live").copy(accountId = "srv:user-1"))

        delegate.drainNext(awaitSettle = false)

        // The two foreign items are purged (never sent); the live item drains normally.
        assertThat(sent.map { it.localId }).containsExactly("live")
        assertThat(queue).isEmpty()
        assertThat(droppedCounts).containsExactly(2)
    }

    @Test
    fun `drainNext sends items with a null accountId (pre-multi-account) without dropping`() {
        delegate.enqueue(spec("a")) // spec() leaves accountId null

        delegate.drainNext(awaitSettle = false)

        assertThat(sent.map { it.localId }).containsExactly("a")
        assertThat(droppedCounts).isEmpty()
    }

    @Test
    fun `drainNext leaves foreign items untouched (no purge, no signal) while paused`() {
        // A foreign item on a paused queue: the pause is the user parking the queue, so drainNext must
        // not mutate it or fire the "discarded" snackbar. The purge happens later, on resume.
        delegate.enqueue(spec("a").copy(accountId = "srv:user-A"))
        delegate.pause()

        delegate.drainNext()

        assertThat(sent).isEmpty()
        assertThat(queue.map { it.localId }).containsExactly("a")
        assertThat(droppedCounts).isEmpty()
    }

    @Test
    fun `drainNext keeps stamped items when the active account is unresolved`() {
        // A null current account (Warming / logged-out) is "identity not yet known", not "account B".
        // Treating every stamped item as foreign then would wipe the whole queue for no real mismatch.
        val warming = InMemoryActiveAccountProvider(AccountState.Warming)
        val warmingSent = mutableListOf<QueuedMessage>()
        val warmingDropped = mutableListOf<Int>()
        val warmingDelegate = MessageQueueDelegate(
            handle = QueueHandle(stateHandle),
            activeAccountProvider = warming,
            sendWithSpec = { spec, _ -> warmingSent.add(spec) },
            onQueuedDropped = { warmingDropped.add(it) },
        )
        warmingDelegate.enqueue(spec("a").copy(accountId = "srv:user-A"))

        warmingDelegate.drainNext(awaitSettle = false)

        assertThat(warmingSent.map { it.localId }).containsExactly("a")
        assertThat(warmingDropped).isEmpty()
    }
}
