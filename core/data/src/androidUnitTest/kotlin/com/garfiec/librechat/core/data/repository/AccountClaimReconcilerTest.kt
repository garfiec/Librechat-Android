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
import kotlinx.coroutines.flow.first
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

    @Test
    fun claimsOwnerRowsByUser_stampsTransitively_deletesForeignAndConvLess() = runTest(dispatcher) {
        seedLegacyRows()

        reconciler("claim").claimIfNeeded(AccountId("srv:userA"), userKey = "userA")

        // Owner (userA) rows are stamped...
        assertThat(db.conversationDao().getById("convA")?.accountId).isEqualTo("srv:userA")
        assertThat(db.messageDao().getById("mA")?.accountId).isEqualTo("srv:userA")
        assertThat(db.draftDao().getDraft("convA")?.accountId).isEqualTo("srv:userA")
        val tags = db.conversationTagDao().getAllTags().first()
        assertThat(tags.map { it.tag }).containsExactly("work")
        assertThat(tags.single().accountId).isEqualTo("srv:userA")

        // ...foreign (userB) rows are deleted, not visible, not re-attributed.
        assertThat(db.conversationDao().getById("convB")).isNull()
        assertThat(db.messageDao().getById("mB")).isNull()
        assertThat(db.draftDao().getDraft("convB")).isNull()

        // ...un-attributable conv-less message is deleted (fail-safe).
        assertThat(db.messageDao().getById("mOrphan")).isNull()

        // ...the conv-less new-chat draft survives, claimed for the upgrading account (unsent text kept)...
        assertThat(db.draftDao().getDraft(NEW_CHAT_DRAFT_KEY)?.accountId).isEqualTo("srv:userA")
        // ...but a stale conv-less draft (owning conversation already gone) is still swept.
        assertThat(db.draftDao().getDraft("deleted-conv")).isNull()
    }

    @Test
    fun claimIsIdempotent() = runTest(dispatcher) {
        seedLegacyRows()
        val dao = db.accountClaimDao()

        dao.claimLegacyRows(accountId = "srv:userA", userKey = "userA", newChatKey = NEW_CHAT_DRAFT_KEY)
        dao.claimLegacyRows(accountId = "srv:userA", userKey = "userA", newChatKey = NEW_CHAT_DRAFT_KEY)

        assertThat(db.conversationDao().getById("convA")?.accountId).isEqualTo("srv:userA")
        assertThat(db.conversationDao().getById("convB")).isNull()
        assertThat(db.messageDao().getById("mA")?.accountId).isEqualTo("srv:userA")
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

        assertThat(db.conversationDao().getById("convLate")?.accountId).isNull()
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
