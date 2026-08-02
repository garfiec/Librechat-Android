package com.garfiec.librechat.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.garfiec.librechat.core.common.identity.deriveServerId
import com.garfiec.librechat.core.data.db.LibreChatDatabase
import com.garfiec.librechat.core.data.db.dao.ServerDao
import com.garfiec.librechat.core.data.db.entity.ServerEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Persistence contract for per-server gateway headers (issue #287). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerRepositoryImplTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    private val server = "https://chat.example.com"
    private val other = "https://other.example.com"
    private val headers = mapOf("CF-Access-Client-Id" to "id", "CF-Access-Client-Secret" to "secret")

    private lateinit var db: LibreChatDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibreChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repository(dao: ServerDao = db.serverDao()) =
        ServerRepositoryImpl(
            serverDao = dao,
            json = json,
            appScope = CoroutineScope(testDispatcher),
            ioDispatcher = testDispatcher,
        )

    @Test
    fun `headers written are visible to the very next read with no restart`() = runTest(testDispatcher) {
        // The editors save and then immediately probe the server, so a stale read here surfaces as a
        // first Connect that fails and a second that succeeds.
        val subject = repository()
        subject.awaitWarm()

        subject.setHeaders(server, headers)

        assertThat(subject.headersFor(server)).isEqualTo(headers)
    }

    @Test
    fun `headers are scoped per server`() = runTest(testDispatcher) {
        val subject = repository()
        subject.setHeaders(server, headers)

        assertThat(subject.headersFor(other)).isEmpty()
    }

    @Test
    fun `url variants of the same deployment resolve to the same headers`() = runTest(testDispatcher) {
        val subject = repository()
        subject.setHeaders("https://chat.example.com/", headers)

        assertThat(subject.headersFor("https://Chat.Example.com:443")).isEqualTo(headers)
    }

    @Test
    fun `a blank or unparseable base URL yields empty rather than throwing`() = runTest(testDispatcher) {
        val subject = repository()
        subject.setHeaders(server, headers)

        assertThat(subject.headersFor("")).isEmpty()
        assertThat(subject.headersFor("   ")).isEmpty()
        assertThat(subject.headersFor("ftp://nope")).isEmpty()
        assertThat(subject.headersFor("https://")).isEmpty()
    }

    @Test
    fun `an unusable server URL reports failure rather than a silent no-op`() = runTest(testDispatcher) {
        assertThat(repository().setHeaders("", headers))
            .isEqualTo(HeaderWriteResult.Refused(HeaderWriteFailure.NoServer))
    }

    @Test
    fun `an empty map removes the entry`() = runTest(testDispatcher) {
        val subject = repository()
        subject.setHeaders(server, headers)
        subject.setHeaders(server, emptyMap())

        assertThat(subject.headersFor(server)).isEmpty()
        assertThat(db.serverDao().getAll()).isEmpty()
    }

    @Test
    fun `a write whose every pair is unsendable is refused, not treated as a clear`() =
        runTest(testDispatcher) {
            val subject = repository()
            subject.setHeaders(server, headers)

            // Asking to *set* headers that all turn out to be unsendable must not answer by deleting
            // the ones already there. Neither editor can produce this — both validate first — but
            // sanitize() exists precisely for callers that don't.
            val result = subject.setHeaders(server, mapOf("Authorization" to "Basic nope"))

            assertThat(result).isEqualTo(HeaderWriteResult.Refused(HeaderWriteFailure.NothingUsable))
            assertThat(subject.headersFor(server)).isEqualTo(headers)
            assertThat(db.serverDao().getAll()).hasSize(1)
        }

    @Test
    fun `reserved and malformed pairs never survive a round trip`() = runTest(testDispatcher) {
        val subject = repository()
        subject.setHeaders(
            server,
            mapOf(
                "CF-Access-Client-Id" to "keep",
                "Authorization" to "Basic nope",
                "User-Agent" to "curl/8",
            ),
        )

        assertThat(subject.headersFor(server)).containsExactly("CF-Access-Client-Id", "keep")
    }

    @Test
    fun `headers survive a new repository instance over the same database`() = runTest(testDispatcher) {
        // The table is device-scoped and absent from AccountDataPurger — logging out must not cost
        // the user the credential that lets them log back in.
        repository().setHeaders(server, headers)

        val reopened = repository()
        reopened.awaitWarm()

        assertThat(reopened.headersFor(server)).isEqualTo(headers)
    }

    @Test
    fun `an unreadable store reports null rather than an empty header set`() = runTest(testDispatcher) {
        val broken = repository(failingDao())

        broken.awaitWarm()

        assertThat(broken.headersFor(server)).isEmpty()
        assertThat(broken.headersForServer(server)).isNull()
    }

    @Test
    fun `a write the store rejects is reported as a failed save`() = runTest(testDispatcher) {
        assertThat(repository(failingDao()).setHeaders(server, headers))
            .isEqualTo(HeaderWriteResult.Refused(HeaderWriteFailure.StorageUnavailable))
    }

    @Test
    fun `an empty write over a table that could not be read is refused`() = runTest(testDispatcher) {
        repository().setHeaders(server, headers)
        val dao = ToggleableServerDao(db.serverDao()).apply { failReads = true }
        val subject = repository(dao)
        subject.awaitWarm()

        // The editor showing an empty list is the *absence of a read*, not a user who cleared their
        // headers — and the write would delete the row it was never able to display. Refused here
        // rather than in each editor, because this is the only layer that can tell the two apart.
        assertThat(subject.setHeaders(server, emptyMap()))
            .isEqualTo(HeaderWriteResult.Refused(HeaderWriteFailure.UnverifiedDelete))
        assertThat(db.serverDao().getAll()).hasSize(1)
    }

    @Test
    fun `an empty write over a row that could not be decoded is refused`() = runTest(testDispatcher) {
        db.serverDao().upsert(ServerEntity(deriveServerId(server).value, "{ not json"))
        repository().setHeaders(other, headers)
        val subject = repository()
        subject.awaitWarm()

        assertThat(subject.setHeaders(server, emptyMap()))
            .isEqualTo(HeaderWriteResult.Refused(HeaderWriteFailure.UnverifiedDelete))
        // The refusal is per server: a readable row still clears normally.
        assertThat(subject.setHeaders(other, emptyMap())).isEqualTo(HeaderWriteResult.Saved)
    }

    @Test
    fun `a clear is allowed again once the read recovers`() = runTest(testDispatcher) {
        repository().setHeaders(server, headers)
        val dao = ToggleableServerDao(db.serverDao()).apply { failReads = true }
        val subject = repository(dao)
        subject.awaitWarm()

        dao.failReads = false

        // Otherwise one transient read failure would leave the user permanently unable to delete a
        // rotated credential — the refusal above would have nothing that could ever lift it.
        assertThat(subject.setHeaders(server, emptyMap())).isEqualTo(HeaderWriteResult.Saved)
        assertThat(db.serverDao().getAll()).isEmpty()
    }

    @Test
    fun `a row whose pairs all sanitize away reports null rather than an empty header set`() =
        runTest(testDispatcher) {
            // Every stored name is reserved, so nothing survives sanitisation — which is what happens
            // to a value written before a release added that name to RESERVED_NAMES. The row is still
            // there and still holds what the user typed, so "none configured" would invite the save
            // that deletes it.
            db.serverDao().upsert(
                ServerEntity(deriveServerId(server).value, """{"Authorization":"Basic nope"}"""),
            )

            val subject = repository()
            subject.awaitWarm()

            assertThat(subject.headersForServer(server)).isNull()
            assertThat(subject.headersFor(server)).isEmpty()
        }

    @Test
    fun `the request path never re-reads the table`() = runTest(testDispatcher) {
        val dao = ToggleableServerDao(db.serverDao()).apply { failReads = true }
        val subject = repository(dao)
        subject.awaitWarm()
        val afterSeed = dao.reads

        repeat(REQUESTS) { subject.awaitWarm() }

        // awaitWarm runs in every request's pre-flight, so any re-read here is either a query per
        // request or a queue of requests behind one failing query. Recovery is the editor path's job
        // — see the class KDoc for why trying to serve both from one path could not be settled.
        assertThat(dao.reads).isEqualTo(afterSeed)
    }

    @Test
    fun `a write that lands reads back even while the table cannot be read`() = runTest(testDispatcher) {
        val dao = ToggleableServerDao(db.serverDao()).apply { failReads = true }
        val subject = repository(dao)
        subject.awaitWarm()

        assertThat(subject.setHeaders(server, headers)).isEqualTo(HeaderWriteResult.Saved)

        // Telling the user the credential saved and then, on the next open, that it could not be
        // read sends them to re-enter a secret every request is already carrying. This class is the
        // table's only writer, so a write it performed is not in doubt.
        assertThat(subject.headersForServer(server)).isEqualTo(headers)
        // Servers it did NOT write are still unknown while the read is failing.
        assertThat(subject.headersForServer(other)).isNull()
    }

    @Test
    fun `a transient read failure recovers on the next read`() = runTest(testDispatcher) {
        repository().setHeaders(server, headers)
        val dao = ToggleableServerDao(db.serverDao()).apply { failReads = true }
        val subject = repository(dao)
        subject.awaitWarm()
        assertThat(subject.headersForServer(server)).isNull()

        dao.failReads = false

        // Without a retry a single transient failure would leave every request headerless for the
        // rest of the process, recoverable only by force-stopping the app.
        assertThat(subject.headersForServer(server)).isEqualTo(headers)
        assertThat(subject.headersFor(server)).isEqualTo(headers)
    }

    @Test
    fun `a write after a failed read re-seeds every server, not just the one written`() =
        runTest(testDispatcher) {
            repository().setHeaders(other, headers)
            val dao = ToggleableServerDao(db.serverDao()).apply { failReads = true }
            val subject = repository(dao)
            subject.awaitWarm()

            dao.failReads = false
            assertThat(subject.setHeaders(server, headers)).isEqualTo(HeaderWriteResult.Saved)

            // A write proves one row is writable, not that the table was ever read: reporting the
            // unseeded server as "none configured" invites a save that deletes it for real.
            assertThat(subject.headersForServer(other)).isEqualTo(headers)
            assertThat(subject.headersFor(other)).isEqualTo(headers)
        }

    @Test
    fun `a row that cannot be decoded reports null rather than an empty header set`() =
        runTest(testDispatcher) {
            repository().setHeaders(other, headers)
            db.serverDao().upsert(ServerEntity(deriveServerId(server).value, "{ not json"))

            val subject = repository()
            subject.awaitWarm()

            assertThat(subject.headersForServer(server)).isNull()
            // One unreadable row must not take the rest of the table with it.
            assertThat(subject.headersForServer(other)).isEqualTo(headers)
        }

    @Test
    fun `saving over a row that could not be decoded repairs it`() = runTest(testDispatcher) {
        db.serverDao().upsert(ServerEntity(deriveServerId(server).value, "{ not json"))
        val subject = repository()
        subject.awaitWarm()

        assertThat(subject.setHeaders(server, headers)).isEqualTo(HeaderWriteResult.Saved)

        assertThat(subject.headersForServer(server)).isEqualTo(headers)
    }

    private fun failingDao() = ToggleableServerDao(db.serverDao()).apply {
        failReads = true
        failWrites = true
    }

    /**
     * A DAO that delegates to the real one with reads and writes independently switchable to a
     * failure. Failing every call cannot reach the states that matter here — a read that failed and
     * later recovered, or a write that lands while the table has never been read.
     */
    private class ToggleableServerDao(private val delegate: ServerDao) : ServerDao {
        var failReads = false
        var failWrites = false

        /** Reads attempted, so a test can assert how often the table is hit rather than only what it returns. */
        var reads = 0
            private set

        override suspend fun getAll(): List<ServerEntity> {
            reads++
            return if (failReads) error("database unavailable") else delegate.getAll()
        }

        override suspend fun upsert(server: ServerEntity) =
            if (failWrites) error("database unavailable") else delegate.upsert(server)

        override suspend fun deleteById(serverId: String) =
            if (failWrites) error("database unavailable") else delegate.deleteById(serverId)
    }

    private companion object {
        /** Enough pre-flights that a per-request retry would be unmistakable in the read count. */
        const val REQUESTS = 20
    }
}
