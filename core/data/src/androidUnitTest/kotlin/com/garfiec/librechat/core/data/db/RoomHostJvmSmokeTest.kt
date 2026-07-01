package com.garfiec.librechat.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.garfiec.librechat.core.data.db.entity.ConversationEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Host-JVM Room lane probe for the account-tenancy DB tests.
 *
 * A raw [androidx.sqlite.driver.bundled.BundledSQLiteDriver] is a dead end on the host JVM (**Path A**):
 * the `sqlite-bundled` artifact resolved for the `androidTarget` ships only Android `.so` natives, which
 * throw `UnsatisfiedLinkError` in a desktop host-JVM unit test. This test uses **Path B**: run the
 * real [LibreChatDatabase] in-memory on the host JVM under Robolectric, which supplies a working framework
 * SQLite — the same driver production Android uses (`DataPlatformModule.android.kt` builds Room with no
 * `setDriver`, so it takes the default `AndroidSQLiteDriver`).
 *
 * If this passes, the account-tenancy migration (4→5) and the cross-account isolation suite can live in
 * `androidUnitTest`, gated by the existing host-JVM `Test` job as a required check — no emulator, no
 * managed-device lane.
 *
 * `@Config(sdk = [34])` pins Robolectric to an SDK it ships an `android-all` image for, independent of the
 * module's `compileSdk`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomHostJvmSmokeTest {

    @Test
    fun roomOpensInMemoryAndRoundTripsARow() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibreChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.conversationDao().upsert(sampleConversation(id = "c1", title = "host-jvm-ok"))
            val read = db.conversationDao().getByIdForAccount("c1", "acct")
            assertEquals("host-jvm-ok", read?.title)
        } finally {
            db.close()
        }
    }

    private fun sampleConversation(id: String, title: String) = ConversationEntity(
        conversationId = id,
        title = title,
        user = "u1",
        endpoint = null,
        endpointType = null,
        model = null,
        agentId = null,
        isArchived = false,
        tags = "[]",
        iconURL = null,
        greeting = null,
        modelParams = null,
        createdAt = 0L,
        updatedAt = 0L,
        accountId = "acct",
    )
}
