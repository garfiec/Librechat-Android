package com.garfiec.librechat.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.garfiec.librechat.core.data.db.entity.ConversationEntity
import com.garfiec.librechat.core.data.db.entity.ConversationTagEntity
import com.garfiec.librechat.core.data.db.entity.DraftEntity
import com.garfiec.librechat.core.data.db.entity.MessageEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Host-JVM (Robolectric) proof that the `accountId`-filtered tenant reads never cross accounts — the
 * read half of the leak fix. Two accounts' rows share
 * the one DB; each filtered read for account A must return only A's rows, and **by-PK reads must return
 * null for a foreign id** (the deep-link / stale-id leak), not the foreign row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountReadIsolationTest {

    private val accountA = "srv:userA"
    private val accountB = "srv:userB"

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

    @Test
    fun conversations_areAccountScoped_listAndByPk() = runTest {
        val convDao = db.conversationDao()
        convDao.upsert(conversation("convA", accountA))
        convDao.upsert(conversation("convB", accountB))

        // List: A's account sees only convA.
        val listA = convDao.observeConversationsForAccount(accountA, isArchived = false).first()
        assertThat(listA.map { it.conversationId }).containsExactly("convA")

        // By-PK: A can read its own row, but reading B's row under A's account returns null.
        assertThat(convDao.getByIdForAccount("convA", accountA)?.conversationId).isEqualTo("convA")
        assertThat(convDao.getByIdForAccount("convB", accountA)).isNull()
        assertThat(convDao.observeByIdForAccount("convB", accountA).first()).isNull()
    }

    @Test
    fun messages_areAccountScoped_listSiblingsAndByPk() = runTest {
        val msgDao = db.messageDao()
        // Same conversationId across accounts to prove the filter is accountId, not just conv.
        msgDao.upsert(message("mA", conversationId = "conv", parentMessageId = "p", accountId = accountA))
        msgDao.upsert(message("mB", conversationId = "conv", parentMessageId = "p", accountId = accountB))

        val listA = msgDao.observeMessagesForAccount("conv", accountA).first()
        assertThat(listA.map { it.messageId }).containsExactly("mA")

        val siblingsA = msgDao.getSiblingsForAccount("conv", parentMessageId = "p", accountId = accountA)
        assertThat(siblingsA.map { it.messageId }).containsExactly("mA")

        assertThat(msgDao.getByIdForAccount("mB", accountA)).isNull()
    }

    @Test
    fun drafts_areAccountScoped_listAndByConversation() = runTest {
        val draftDao = db.draftDao()
        draftDao.upsertDraft(DraftEntity(conversationId = "conv", text = "A's draft", accountId = accountA))
        // A different conversation owned by B (drafts PK is conversation_id, so use a distinct id).
        draftDao.upsertDraft(DraftEntity(conversationId = "convB", text = "B's draft", accountId = accountB))

        assertThat(draftDao.observeDraftsForAccount(accountA).first().map { it.conversationId })
            .containsExactly("conv")
        assertThat(draftDao.getDraftForAccount("conv", accountA)?.text).isEqualTo("A's draft")
        assertThat(draftDao.getDraftForAccount("convB", accountA)).isNull()
    }

    @Test
    fun tags_areAccountScoped() = runTest {
        val tagDao = db.conversationTagDao()
        tagDao.upsertAll(
            listOf(
                tag("work", accountA),
                tag("home", accountB),
            ),
        )

        val tagsA = tagDao.observeTagsForAccount(accountA).first()
        assertThat(tagsA.map { it.tag }).containsExactly("work")
    }

    private fun conversation(id: String, accountId: String) = ConversationEntity(
        conversationId = id, title = "t-$id", user = "u", endpoint = null, endpointType = null,
        model = null, agentId = null, isArchived = false, tags = "[]", iconURL = null, greeting = null,
        modelParams = null, createdAt = 0L, updatedAt = 0L, accountId = accountId,
    )

    private fun message(id: String, conversationId: String, parentMessageId: String, accountId: String) =
        MessageEntity(
            messageId = id, conversationId = conversationId, parentMessageId = parentMessageId, sender = null,
            text = null, content = null, isCreatedByUser = false, model = null, endpoint = null,
            iconURL = null, finishReason = null, tokenCount = null, feedback = null, files = null,
            attachments = null, metadata = null, createdAt = 0L, updatedAt = 0L, accountId = accountId,
        )

    private fun tag(tag: String, accountId: String) = ConversationTagEntity(
        tag = tag, user = "u", description = null, count = 0, position = 0, createdAt = 0L,
        updatedAt = 0L, accountId = accountId,
    )
}
