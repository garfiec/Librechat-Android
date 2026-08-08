package com.garfiec.librechat.core.data.prefetch

import com.garfiec.librechat.core.common.conversation.OpenConversationRegistry
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.network.isPrefetch
import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.SettingsDataStore
import com.garfiec.librechat.core.data.db.dao.ConversationDao
import com.garfiec.librechat.core.data.db.dao.MessageDao
import com.garfiec.librechat.core.data.db.dao.PrefetchCandidate
import com.garfiec.librechat.core.data.db.dao.PrefetchWatermarkDao
import com.garfiec.librechat.core.data.db.entity.PrefetchWatermarkEntity
import com.garfiec.librechat.core.data.repository.AgentRepository
import com.garfiec.librechat.core.data.repository.ConfigRepository
import com.garfiec.librechat.core.data.repository.ConversationRepository
import com.garfiec.librechat.core.data.repository.MessageRepository
import com.garfiec.librechat.core.model.Message
import com.garfiec.librechat.core.network.client.ServerUrlProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.coroutineContext

/**
 * The prefetcher's behaviour under load and failure, on virtual time.
 *
 * These are the properties that decide whether the feature is a good citizen rather than whether it
 * works: how hard it leans on the server, what it does when told to slow down, and when it gives up.
 * All of them are invisible in a functional test that only asks whether the cache got warm.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefetchEngineTest {

    private val account = AccountId("srv:user-a")

    private val conversationDao = mockk<ConversationDao>(relaxed = true)
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val watermarkDao = mockk<PrefetchWatermarkDao>(relaxed = true)
    private val messageRepository = mockk<MessageRepository>()
    private val conversationRepository = mockk<ConversationRepository>()
    private val configRepository = mockk<ConfigRepository>(relaxed = true)
    private val agentRepository = mockk<AgentRepository>(relaxed = true)
    private val openConversationRegistry = OpenConversationRegistry()
    private val settingsDataStore = mockk<SettingsDataStore>(relaxed = true)
    private val attachmentWarmer = mockk<AttachmentWarmer>(relaxed = true)
    private val serverUrlProvider = object : ServerUrlProvider {
        override fun getBaseUrl(): String = "https://chat.example.com"
    }

    @Before
    fun setup() {
        coEvery { conversationRepository.loadNextPage(any(), any(), any(), any(), any(), any()) } returns
            Result.Success(null)
        coEvery { configRepository.fetchEndpoints() } returns Result.Success(emptyMap())
        coEvery { configRepository.fetchModels() } returns Result.Success(emptyMap())
        coEvery { agentRepository.getAgents(any()) } returns Result.Success(emptyList())
        coEvery { watermarkDao.allForAccount(any()) } returns emptyList()
        coEvery { conversationDao.pinnedForPrefetch(any()) } returns emptyList()
        coEvery { conversationDao.conversationIdsOlderThan(any(), any()) } returns emptyList()
        every { settingsDataStore.prefetchAttachmentsEnabled } returns flowOf(false)
        every { attachmentWarmer.isSupported } returns false
    }

    private fun TestScope.engine() = PrefetchEngine(
        conversationDao = conversationDao,
        messageDao = messageDao,
        watermarkDao = watermarkDao,
        messageRepository = messageRepository,
        conversationRepository = conversationRepository,
        configRepository = configRepository,
        agentRepository = agentRepository,
        policy = PrefetchPolicy(),
        openConversationRegistry = openConversationRegistry,
        attachmentWarmer = attachmentWarmer,
        settingsDataStore = settingsDataStore,
        serverUrlProvider = serverUrlProvider,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        // The scheduler's own time source, so elapsedNow() follows virtual time.
        timeSource = testScheduler.timeSource,
        nowMillis = { currentTime },
    )

    private fun stubRecent(vararg ids: String) {
        coEvery { conversationDao.recentForPrefetch(any(), any()) } returns
            ids.mapIndexed { index, id -> PrefetchCandidate(id, updatedAt = 100L + index, pinned = false) }
    }

    private fun rateLimited(retryAfterSeconds: Long?) = Result.Error(
        exception = ApiException(
            statusCode = 429,
            message = "slow down",
            retryAfterSeconds = retryAfterSeconds,
        ),
    )

    @Test
    fun `pacing waits five times the observed latency between requests`() = runTest {
        stubRecent("a", "b")
        val callTimes = mutableListOf<Long>()
        coEvery { messageRepository.refreshMessages(any(), any()) } coAnswers {
            callTimes += currentTime
            delay(REQUEST_MILLIS)
            Result.Success(emptyList<Message>())
        }

        engine().run(account)

        assertThat(callTimes).hasSize(2)
        assertThat(callTimes[1] - callTimes[0])
            .isEqualTo(REQUEST_MILLIS + REQUEST_MILLIS * PrefetchEngine.PACE_FACTOR)
    }

    /**
     * The engine calls the ordinary repositories, so the exemption has to be applied by the engine
     * itself. Without it the first request counts as user activity, closes the gate that permits the
     * pass, and the prefetcher deadlocks against itself — silently.
     */
    @Test
    fun `every prefetch request runs marked as prefetch work`() = runTest {
        stubRecent("a")
        // A real fake, not a mock: MockK invokes `coAnswers` through its own continuation, which does
        // not carry the caller's coroutine context — so a mock here would report "unmarked" no matter
        // what the engine does, and this test would fail on correct code.
        val recordingRepository = MarkerRecordingMessageRepository()
        val engine = PrefetchEngine(
            conversationDao = conversationDao,
            messageDao = messageDao,
            watermarkDao = watermarkDao,
            messageRepository = recordingRepository,
            conversationRepository = conversationRepository,
            configRepository = configRepository,
            agentRepository = agentRepository,
            policy = PrefetchPolicy(),
            openConversationRegistry = openConversationRegistry,
            attachmentWarmer = attachmentWarmer,
            settingsDataStore = settingsDataStore,
            serverUrlProvider = serverUrlProvider,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            timeSource = testScheduler.timeSource,
            nowMillis = { currentTime },
        )

        engine.run(account)

        assertThat(recordingRepository.sawMarker).isTrue()
    }

    private class MarkerRecordingMessageRepository : MessageRepository {
        var sawMarker: Boolean = false
            private set

        override suspend fun refreshMessages(
            conversationId: String,
            originAccount: AccountId?,
        ): Result<List<Message>> {
            sawMarker = coroutineContext.isPrefetch()
            return Result.Success(emptyList())
        }

        override fun observeMessages(conversationId: String) = throw UnsupportedOperationException()
        override suspend fun getMessages(conversationId: String) = throw UnsupportedOperationException()
        override suspend fun cacheMessages(messages: List<Message>, originAccount: AccountId?) = Unit
        override suspend fun updateFeedback(
            conversationId: String,
            messageId: String,
            feedback: com.garfiec.librechat.core.model.MinimalFeedback?,
        ) = throw UnsupportedOperationException()

        override suspend fun updateMessageText(conversationId: String, messageId: String, text: String) =
            throw UnsupportedOperationException()

        override suspend fun branchMessage(conversationId: String, messageId: String, agentId: String?) =
            throw UnsupportedOperationException()
    }

    /** A 429 means "slower", not "broken": the server is answering, it is just asking us to wait. */
    @Test
    fun `a rate limit waits the server's retry-after and does not stop the pass`() = runTest {
        stubRecent("a", "b")
        val callTimes = mutableListOf<Long>()
        var first = true
        coEvery { messageRepository.refreshMessages(any(), any()) } coAnswers {
            callTimes += currentTime
            if (first) {
                first = false
                rateLimited(retryAfterSeconds = 7)
            } else {
                Result.Success(emptyList<Message>())
            }
        }

        engine().run(account)

        assertThat(callTimes).hasSize(2)
        assertThat(callTimes[1] - callTimes[0]).isEqualTo(7_000L)
    }

    /**
     * Three 429s in a row must not be mistaken for a broken server — otherwise a busy server that is
     * politely throttling us turns the feature off until the app restarts.
     */
    @Test
    fun `repeated rate limits never trip the breaker`() = runTest {
        stubRecent("a", "b", "c", "d")
        var calls = 0
        coEvery { messageRepository.refreshMessages(any(), any()) } coAnswers {
            calls++
            rateLimited(retryAfterSeconds = 1)
        }

        engine().run(account)

        assertThat(calls).isEqualTo(4)
    }

    @Test
    fun `the pass stops after three consecutive failures`() = runTest {
        stubRecent("a", "b", "c", "d", "e")
        var calls = 0
        coEvery { messageRepository.refreshMessages(any(), any()) } coAnswers {
            calls++
            Result.Error(exception = ApiException(statusCode = 500, message = "boom"))
        }

        engine().run(account)

        assertThat(calls).isEqualTo(PrefetchEngine.MAX_CONSECUTIVE_FAILURES)
    }

    /** "Consecutive" has to mean consecutive, or a flaky server eventually trips the breaker anyway. */
    @Test
    fun `a success resets the failure count`() = runTest {
        stubRecent("a", "b", "c", "d", "e")
        var calls = 0
        coEvery { messageRepository.refreshMessages(any(), any()) } coAnswers {
            calls++
            // fail, fail, succeed, fail, fail — never three in a row.
            if (calls == 3) Result.Success(emptyList<Message>()) else Result.Error(exception = ApiException(500, "boom"))
        }

        engine().run(account)

        assertThat(calls).isEqualTo(5)
    }

    /**
     * The breaker is keyed by account rather than a flag, so a switch or a re-login starts clean
     * while repeated gate flips within one session do not keep retrying a dead server.
     */
    @Test
    fun `a tripped breaker holds for its account and not for another`() = runTest {
        stubRecent("a", "b", "c", "d")
        var calls = 0
        coEvery { messageRepository.refreshMessages(any(), any()) } coAnswers {
            calls++
            Result.Error(exception = ApiException(statusCode = 500, message = "boom"))
        }
        val engine = engine()

        engine.run(account)
        val afterFirstPass = calls
        engine.run(account)
        val afterSecondPass = calls
        engine.run(AccountId("srv:user-b"))

        assertThat(afterFirstPass).isEqualTo(PrefetchEngine.MAX_CONSECUTIVE_FAILURES)
        assertThat(afterSecondPass).isEqualTo(afterFirstPass)
        assertThat(calls).isGreaterThan(afterSecondPass)
    }

    @Test
    fun `a warmed conversation records the version it was warmed against`() = runTest {
        coEvery { conversationDao.recentForPrefetch(any(), any()) } returns
            listOf(PrefetchCandidate("a", updatedAt = 4_242L, pinned = false))
        coEvery { messageRepository.refreshMessages(any(), any()) } returns Result.Success(emptyList())

        val watermark = slot<PrefetchWatermarkEntity>()
        coEvery { watermarkDao.upsert(capture(watermark)) } returns Unit

        engine().run(account)

        // The conversation's updatedAt, not the time of the warm: only the former can answer
        // "has the server changed since?".
        assertThat(watermark.captured.warmedConversationUpdatedAt).isEqualTo(4_242L)
        assertThat(watermark.captured.accountId).isEqualTo(account.value)
    }

    @Test
    fun `pruning drops stale message rows together with their watermarks`() = runTest {
        stubRecent("recent")
        coEvery { messageRepository.refreshMessages(any(), any()) } returns Result.Success(emptyList())
        coEvery { conversationDao.conversationIdsOlderThan(any(), any()) } returns listOf("ancient")

        engine().run(account)

        coVerify(exactly = 1) { messageDao.deleteForConversations(account.value, listOf("ancient")) }
        // Without this the rows just deleted still look warm and are never fetched again.
        coVerify(exactly = 1) { watermarkDao.deleteFor(account.value, listOf("ancient")) }
    }

    @Test
    fun `pruning spares the open conversation and everything still eligible`() = runTest {
        stubRecent("eligible")
        openConversationRegistry.set("open")
        coEvery { messageRepository.refreshMessages(any(), any()) } returns Result.Success(emptyList())
        coEvery { conversationDao.conversationIdsOlderThan(any(), any()) } returns
            listOf("eligible", "open", "genuinely-stale")

        engine().run(account)

        coVerify(exactly = 1) { messageDao.deleteForConversations(account.value, listOf("genuinely-stale")) }
    }

    // --- Run state, which is what the settings readout reports ---

    @Test
    fun `run state reports progress through the warm set and settles back to idle`() = runTest {
        stubRecent("a", "b")
        val observed = mutableListOf<PrefetchRunState>()
        val engine = engine()
        coEvery { messageRepository.refreshMessages(any(), any()) } coAnswers {
            observed += engine.runState.value.state
            Result.Success(emptyList())
        }

        engine.run(account)

        assertThat(observed).containsExactly(
            PrefetchRunState.WarmingMessages(completed = 0, total = 2),
            PrefetchRunState.WarmingMessages(completed = 1, total = 2),
        ).inOrder()
        assertThat(engine.runState.value.state).isEqualTo(PrefetchRunState.Idle)
    }

    /**
     * A pass with nothing stale must be reported as idle, not as working. The two are the states the
     * readout exists to tell apart, and the list refresh runs either way.
     */
    @Test
    fun `a pass with nothing stale ends idle`() = runTest {
        coEvery { conversationDao.recentForPrefetch(any(), any()) } returns emptyList()
        val engine = engine()

        engine.run(account)

        assertThat(engine.runState.value.state).isEqualTo(PrefetchRunState.Idle)
    }

    @Test
    fun `the breaker tripping is published and outlives the pass`() = runTest {
        stubRecent("a", "b", "c", "d")
        coEvery { messageRepository.refreshMessages(any(), any()) } returns
            Result.Error(exception = ApiException(statusCode = 500, message = "boom"))
        val engine = engine()

        engine.run(account)
        assertThat(engine.runState.value.state).isEqualTo(PrefetchRunState.Stopped)

        // Still stopped on the next attempt: the breaker is what makes this state worth surfacing,
        // since nothing clears it on its own.
        engine.run(account)
        assertThat(engine.runState.value.state).isEqualTo(PrefetchRunState.Stopped)
    }

    @Test
    fun `a manual reset lets a later pass run again`() = runTest {
        stubRecent("a", "b", "c")
        coEvery { messageRepository.refreshMessages(any(), any()) } returns
            Result.Error(exception = ApiException(statusCode = 500, message = "boom"))
        val engine = engine()
        engine.run(account)

        engine.resetForManualRun(account)
        assertThat(engine.runState.value.state).isEqualTo(PrefetchRunState.Idle)

        coEvery { messageRepository.refreshMessages(any(), any()) } returns Result.Success(emptyList())
        engine.run(account)

        coVerify(atLeast = 1) { watermarkDao.upsert(any()) }
    }

    /** Resetting another account's state must not revive this one. */
    @Test
    fun `a manual reset is scoped to one account`() = runTest {
        stubRecent("a", "b", "c")
        coEvery { messageRepository.refreshMessages(any(), any()) } returns
            Result.Error(exception = ApiException(statusCode = 500, message = "boom"))
        val engine = engine()
        engine.run(account)

        engine.resetForManualRun(AccountId("srv:someone-else"))

        assertThat(engine.runState.value.state).isEqualTo(PrefetchRunState.Stopped)
    }

    /**
     * The run state is read by a settings screen that outlives any one session, so it has to say
     * whose state it is. Without the tag, an account whose server failed hands its verdict to
     * whichever account is active next.
     */
    @Test
    fun `run state is tagged with the account it describes`() = runTest {
        stubRecent("a", "b", "c", "d")
        coEvery { messageRepository.refreshMessages(any(), any()) } returns
            Result.Error(exception = ApiException(statusCode = 500, message = "boom"))
        val engine = engine()

        engine.run(account)

        assertThat(engine.runState.value.accountId).isEqualTo(account.value)
        assertThat(engine.runState.value.stateFor(account.value)).isEqualTo(PrefetchRunState.Stopped)
        // Anyone else sees Idle, not a failure report about an account that never failed.
        assertThat(engine.runState.value.stateFor("srv:someone-else")).isEqualTo(PrefetchRunState.Idle)
    }

    /**
     * Marking the stage warmed before its requests land turns "once per process" into "attempted
     * once per process": the gate closing mid-stage is the ordinary way a pass ends, and it would
     * retire reference data for the life of the process.
     */
    @Test
    fun `reference data is only marked warmed once its requests land`() = runTest {
        stubRecent()
        coEvery { configRepository.fetchEndpoints() } returns
            Result.Error(exception = ApiException(statusCode = 500, message = "boom"))
        val engine = engine()

        engine.run(account)
        coEvery { configRepository.fetchEndpoints() } returns Result.Success(emptyMap())
        engine.run(account)

        // Twice: the failed attempt did not retire the stage.
        coVerify(exactly = 2) { configRepository.fetchEndpoints() }
    }

    @Test
    fun `reference data is warmed once per account when it succeeds`() = runTest {
        stubRecent()
        val engine = engine()

        engine.run(account)
        engine.run(account)

        coVerify(exactly = 1) { configRepository.fetchEndpoints() }
    }

    /** "Warm now" advertises a retry, so it has to clear the once-per-process mark too. */
    @Test
    fun `a manual reset re-arms the reference data warm`() = runTest {
        stubRecent()
        val engine = engine()
        engine.run(account)

        engine.resetForManualRun(account)
        engine.run(account)

        coVerify(exactly = 2) { configRepository.fetchEndpoints() }
    }

    /** The list refresh publishes its own rate limit; unpublished, the pass would read as "Working". */
    @Test
    fun `a rate limit during the list refresh is published`() = runTest {
        val observed = mutableListOf<PrefetchRunState>()
        coEvery { conversationRepository.loadNextPage(any(), any(), any(), any(), any(), any()) } coAnswers {
            rateLimited(retryAfterSeconds = 5)
        }
        val engine = engine()
        val job = launch {
            engine.runState.collect { observed += it.state }
        }

        engine.run(account)
        job.cancel()

        assertThat(observed).contains(PrefetchRunState.RateLimited(backoffMillis = 5_000))
    }

    private companion object {
        const val REQUEST_MILLIS = 200L
    }
}
