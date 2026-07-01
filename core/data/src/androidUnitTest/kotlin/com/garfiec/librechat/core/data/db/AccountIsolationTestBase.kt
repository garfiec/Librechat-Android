package com.garfiec.librechat.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.garfiec.librechat.core.data.db.entity.ConversationEntity
import com.garfiec.librechat.core.data.db.entity.ConversationTagEntity
import com.garfiec.librechat.core.data.db.entity.MessageEntity
import org.junit.After
import org.junit.Before

/**
 * Shared Robolectric harness + entity builders for the two-account isolation suites
 * ([AccountReadIsolationTest], [AccountWriteIsolationTest]). Centralizing the in-memory DB setup and
 * the fixture builders keeps those two suites exercising identical DB config and entity shapes.
 *
 * `AccountClaimReconcilerTest` deliberately keeps its own builders rather than extending this base: it
 * seeds *legacy* rows (nullable `accountId`, a per-row `user` column, null `parentMessageId`), a shape
 * these always-attributed builders don't model.
 */
abstract class AccountIsolationTestBase {

    protected val accountA = "srv:userA"
    protected val accountB = "srv:userB"

    protected lateinit var db: LibreChatDatabase

    @Before
    fun setUpDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibreChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDownDb() {
        db.close()
    }

    protected fun conversation(
        id: String,
        accountId: String,
        title: String = "t-$id",
        isArchived: Boolean = false,
        tags: String = "[]",
    ) = ConversationEntity(
        conversationId = id, title = title, user = "u", endpoint = null, endpointType = null,
        model = null, agentId = null, isArchived = isArchived, tags = tags, iconURL = null, greeting = null,
        modelParams = null, createdAt = 0L, updatedAt = 0L, accountId = accountId,
    )

    protected fun message(
        id: String,
        conversationId: String,
        accountId: String,
        parentMessageId: String = "p",
        text: String? = null,
        feedback: String? = null,
    ) = MessageEntity(
        messageId = id, conversationId = conversationId, parentMessageId = parentMessageId, sender = null,
        text = text, content = null, isCreatedByUser = false, model = null, endpoint = null,
        iconURL = null, finishReason = null, tokenCount = null, feedback = feedback, files = null,
        attachments = null, metadata = null, createdAt = 0L, updatedAt = 0L, accountId = accountId,
    )

    protected fun tag(tag: String, accountId: String) = ConversationTagEntity(
        tag = tag, user = "u", description = null, count = 0, position = 0, createdAt = 0L,
        updatedAt = 0L, accountId = accountId,
    )
}
