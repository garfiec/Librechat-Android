package com.garfiec.librechat.core.data.prefetch

import com.garfiec.librechat.core.common.conversation.OpenConversationRegistry
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.Session
import com.garfiec.librechat.core.common.identity.SessionManager
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.db.dao.ConversationDao
import com.garfiec.librechat.core.data.db.dao.MessageDao
import com.garfiec.librechat.core.data.db.dao.PrefetchWatermarkDao
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * When a manual run is honoured.
 *
 * The subtlety here is subscription timing. The manual-run flow has no replay, so a request that
 * arrives while nothing is collecting is gone for good — which makes *where* the collector starts
 * the entire behaviour, and makes it invisible to any test that only taps an idle prefetcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefetchControllerTest {

    private val account = AccountId("srv:user-a")
    private val gateOpen = MutableStateFlow(true)

    private val conversationRepository = mockk<ConversationRepository>()
    private val configRepository = mockk<ConfigRepository>(relaxed = true)
    private val agentRepository = mockk<AgentRepository>(relaxed = true)

    /** Held by the first list refresh, so a pass can be parked mid-flight. */
    private val firstPassBlocker = CompletableDeferred<Unit>()

    private fun TestScope.engine(): PrefetchEngine {
        val watermarkDao = mockk<PrefetchWatermarkDao>(relaxed = true)
        val conversationDao = mockk<ConversationDao>(relaxed = true)
        val settingsDataStore = mockk<SettingsDataStore>(relaxed = true)
        val attachmentWarmer = mockk<AttachmentWarmer>(relaxed = true)
        coEvery { watermarkDao.allForAccount(any()) } returns emptyList()
        coEvery { conversationDao.recentForPrefetch(any(), any()) } returns emptyList()
        coEvery { conversationDao.pinnedForPrefetch(any()) } returns emptyList()
        coEvery { conversationDao.conversationIdsOlderThan(any(), any()) } returns emptyList()
        coEvery { configRepository.fetchEndpoints() } returns Result.Success(emptyMap())
        coEvery { configRepository.fetchModels() } returns Result.Success(emptyMap())
        coEvery { agentRepository.getAgents(any()) } returns Result.Success(emptyList())
        every { settingsDataStore.prefetchAttachmentsEnabled } returns flowOf(false)
        every { attachmentWarmer.isSupported } returns false

        var firstCall = true
        coEvery { conversationRepository.loadNextPage(any(), any(), any(), any(), any(), any()) } coAnswers {
            if (firstCall) {
                firstCall = false
                firstPassBlocker.await()
            }
            Result.Success(null)
        }

        return spyk(
            PrefetchEngine(
                conversationDao = conversationDao,
                messageDao = mockk<MessageDao>(relaxed = true),
                watermarkDao = watermarkDao,
                messageRepository = mockk<MessageRepository>(relaxed = true),
                conversationRepository = conversationRepository,
                configRepository = configRepository,
                agentRepository = agentRepository,
                policy = PrefetchPolicy(),
                openConversationRegistry = OpenConversationRegistry(),
                attachmentWarmer = attachmentWarmer,
                settingsDataStore = settingsDataStore,
                serverUrlProvider = object : ServerUrlProvider {
                    override fun getBaseUrl(): String = "https://chat.example.com"
                },
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                timeSource = testScheduler.timeSource,
                nowMillis = { 0L },
            ),
        )
    }

    /**
     * The scope the controller's collectors run in. They never complete by design, so the test
     * cancels this explicitly rather than leaving runTest waiting on them.
     */
    private lateinit var hostScope: CoroutineScope

    private fun TestScope.controller(engine: PrefetchEngine): PrefetchController {
        hostScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val session = mockk<Session>()
        every { session.accountId } returns account
        every { session.scope } returns hostScope
        val sessionManager = mockk<SessionManager>()
        every { sessionManager.current } returns MutableStateFlow(session)
        val gate = mockk<PrefetchGate>()
        every { gate.isOpen() } returns gateOpen

        return PrefetchController(sessionManager, gate, engine, appScope = hostScope)
    }

    @After
    fun tearDown() {
        if (::hostScope.isInitialized) hostScope.cancel()
    }

    /**
     * Taps land while a pass is running, which is exactly when someone reaches for the button. The
     * collector has to be subscribed before the first pass starts: subscribing after it returned
     * drops every one of those taps for want of a collector.
     */
    @Test
    fun `a request made during a pass is honoured rather than dropped`() = runTest {
        val engine = engine()
        val controller = controller(engine)
        advanceUntilIdle()

        // The rising-edge pass is parked inside its list refresh and has not returned.
        coVerify(exactly = 1) {
            conversationRepository.loadNextPage(any(), any(), any(), any(), any(), any())
        }

        controller.requestRun()
        advanceUntilIdle()
        firstPassBlocker.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { engine.resetForManualRun(account) }
        coVerify(exactly = 2) {
            conversationRepository.loadNextPage(any(), any(), any(), any(), any(), any())
        }
    }

    /**
     * A request made with the gate shut belongs to the moment it was made. Retaining it would fire a
     * pass at whatever unrelated time the gate next opened, long after the user stopped looking.
     */
    @Test
    fun `a request made while the gate is shut is discarded`() = runTest {
        gateOpen.value = false
        firstPassBlocker.complete(Unit)
        val engine = engine()
        val controller = controller(engine)
        advanceUntilIdle()

        controller.requestRun()
        advanceUntilIdle()

        coVerify(exactly = 0) {
            conversationRepository.loadNextPage(any(), any(), any(), any(), any(), any())
        }

        gateOpen.value = true
        advanceUntilIdle()

        // Exactly the rising-edge pass, with no queued manual run behind it.
        coVerify(exactly = 1) {
            conversationRepository.loadNextPage(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { engine.resetForManualRun(any()) }
    }

    @Test
    fun `repeated taps during one pass coalesce into a single queued run`() = runTest {
        val engine = engine()
        val controller = controller(engine)
        advanceUntilIdle()

        repeat(5) { controller.requestRun() }
        advanceUntilIdle()
        firstPassBlocker.complete(Unit)
        advanceUntilIdle()

        // One queued run, not five: the single-slot buffer drops the older request.
        coVerify(exactly = 2) {
            conversationRepository.loadNextPage(any(), any(), any(), any(), any(), any())
        }
    }
}
