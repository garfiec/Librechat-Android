package com.garfiec.librechat.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.data.db.LibreChatDatabase
import com.garfiec.librechat.core.data.db.entity.ConversationEntity
import com.garfiec.librechat.core.data.db.entity.ConversationTagEntity
import com.garfiec.librechat.core.data.db.entity.DraftEntity
import com.garfiec.librechat.core.data.db.entity.MessageEntity
import com.garfiec.librechat.core.model.NEW_CHAT_DRAFT_KEY
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Host-JVM (Robolectric) test of the one-time legacy claim: legacy NULL-accountId rows are attributed
 * to the resolved owner by their `user` column (and transitively for messages/drafts), while every
 * other user's commingled leftover and un-attributable conv-less row is deleted — the core of the
 * leak fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountClaimReconcilerTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
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

    private fun dataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { File(tmpFolder.root, "$name.preferences_pb") }

    private fun reconciler(name: String) =
        AccountClaimReconciler(db.accountClaimDao(), dataStore(name), dispatcher)

    // Account-agnostic raw reads for assertions: these tests must inspect rows regardless of which
    // account owns them (to verify what accountId the claim stamped, or that foreign rows were swept),
    // which the account-scoped DAO reads deliberately can't do. Query the DB directly instead of
    // exposing an unscoped read on the production DAO.
    private fun rowExists(table: String, pkCol: String, id: String): Boolean =
        db.query("SELECT 1 FROM $table WHERE $pkCol = ?", arrayOf(id)).use { it.moveToFirst() }

    private fun accountIdOf(table: String, pkCol: String, id: String): String? =
        db.query("SELECT accountId FROM $table WHERE $pkCol = ?", arrayOf(id)).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }

    private data class TagRow(val tag: String, val accountId: String?)

    private fun allTags(): List<TagRow> =
        db.query("SELECT tag, accountId FROM conversation_tags ORDER BY position ASC", null).use { c ->
            buildList { while (c.moveToNext()) add(TagRow(c.getString(0), if (c.isNull(1)) null else c.getString(1))) }
        }

    @Test
    fun claimsOwnerRowsByUser_stampsTransitively_deletesForeignAndConvLess() = runTest(dispatcher) {
        seedLegacyRows()

        reconciler("claim").claimIfNeeded(AccountId("srv:userA"), userKey = "userA")

        // Owner (userA) rows are stamped...
        assertThat(accountIdOf("conversations", "conversationId", "convA")).isEqualTo("srv:userA")
        assertThat(accountIdOf("messages", "messageId", "mA")).isEqualTo("srv:userA")
        assertThat(accountIdOf("drafts", "conversation_id", "convA")).isEqualTo("srv:userA")
        val tags = allTags()
        assertThat(tags.map { it.tag }).containsExactly("work")
        assertThat(tags.single().accountId).isEqualTo("srv:userA")

        // ...foreign (userB) rows are deleted, not visible, not re-attributed.
        assertThat(rowExists("conversations", "conversationId", "convB")).isFalse()
        assertThat(rowExists("messages", "messageId", "mB")).isFalse()
        assertThat(rowExists("drafts", "conversation_id", "convB")).isFalse()

        // ...un-attributable conv-less message is deleted (fail-safe).
        assertThat(rowExists("messages", "messageId", "mOrphan")).isFalse()

        // ...the conv-less new-chat draft survives, claimed for the upgrading account (unsent text kept)...
        assertThat(accountIdOf("drafts", "conversation_id", NEW_CHAT_DRAFT_KEY)).isEqualTo("srv:userA")
        // ...but a stale conv-less draft (owning conversation already gone) is still swept.
        assertThat(rowExists("drafts", "conversation_id", "deleted-conv")).isFalse()
    }

    @Test
    fun claimIsIdempotent() = runTest(dispatcher) {
        seedLegacyRows()
        val dao = db.accountClaimDao()

        dao.claimLegacyRows(accountId = "srv:userA", userKey = "userA", newChatKey = NEW_CHAT_DRAFT_KEY)
        dao.claimLegacyRows(accountId = "srv:userA", userKey = "userA", newChatKey = NEW_CHAT_DRAFT_KEY)

        assertThat(accountIdOf("conversations", "conversationId", "convA")).isEqualTo("srv:userA")
        assertThat(rowExists("conversations", "conversationId", "convB")).isFalse()
        assertThat(accountIdOf("messages", "messageId", "mA")).isEqualTo("srv:userA")
    }

    @Test
    fun markerSkipsRepeatClaim_soLaterLegacyRowsAreLeftAlone() = runTest(dispatcher) {
        seedLegacyRows()
        val sharedStore = dataStore("gated")
        val gated = AccountClaimReconciler(db.accountClaimDao(), sharedStore, dispatcher)

        gated.claimIfNeeded(AccountId("srv:userA"), userKey = "userA")

        // A new NULL-accountId row appears after the claim ran...
        db.conversationDao().upsert(conversation("convLate", user = "userA", accountId = null))
        // ...a second call is short-circuited by the marker, so it neither stamps nor deletes it.
        gated.claimIfNeeded(AccountId("srv:userA"), userKey = "userA")

        assertThat(rowExists("conversations", "conversationId", "convLate")).isTrue()
        assertThat(accountIdOf("conversations", "conversationId", "convLate")).isNull()
    }

    private suspend fun seedLegacyRows() {
        db.conversationDao().upsert(conversation("convA", user = "userA", accountId = null))
        db.conversationDao().upsert(conversation("convB", user = "userB", accountId = null))
        db.messageDao().upsert(message("mA", "convA"))
        db.messageDao().upsert(message("mB", "convB"))
        db.messageDao().upsert(message("mOrphan", "ghost-conv"))
        db.draftDao().upsertDraft(DraftEntity(conversationId = "convA", text = "draftA"))
        db.draftDao().upsertDraft(DraftEntity(conversationId = "convB", text = "draftB"))
        // Conv-less drafts: the new-chat sentinel (must survive) and a stale draft whose
        // conversation was already deleted (must be swept — no owning conversation to attribute it).
        db.draftDao().upsertDraft(DraftEntity(conversationId = NEW_CHAT_DRAFT_KEY, text = "unsent new chat"))
        db.draftDao().upsertDraft(DraftEntity(conversationId = "deleted-conv", text = "orphaned"))
        db.conversationTagDao().upsertAll(
            listOf(
                tag("work", user = "userA"),
                tag("home", user = "userB"),
            ),
        )
    }

    private fun conversation(id: String, user: String, accountId: String?) = ConversationEntity(
        conversationId = id, title = "t-$id", user = user, endpoint = null, endpointType = null,
        model = null, agentId = null, isArchived = false, tags = "[]", iconURL = null, greeting = null,
        modelParams = null, createdAt = 0L, updatedAt = 0L, accountId = accountId,
    )

    private fun message(id: String, conversationId: String) = MessageEntity(
        messageId = id, conversationId = conversationId, parentMessageId = null, sender = null,
        text = null, content = null, isCreatedByUser = false, model = null, endpoint = null,
        iconURL = null, finishReason = null, tokenCount = null, feedback = null, files = null,
        attachments = null, metadata = null, createdAt = 0L, updatedAt = 0L, accountId = null,
    )

    private fun tag(tag: String, user: String) = ConversationTagEntity(
        tag = tag, user = user, description = null, count = 0, position = 0, createdAt = 0L,
        updatedAt = 0L, accountId = null,
    )
}
