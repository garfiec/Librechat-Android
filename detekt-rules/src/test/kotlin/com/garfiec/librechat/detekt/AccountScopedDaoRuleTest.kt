package com.garfiec.librechat.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Tests for [AccountScopedDaoRule].
 *
 * Every snippet is run through detekt-test's [lint], which compiles to PSI WITHOUT a binding
 * context — the same "no type resolution" condition the rule faces on `detektMetadataCommonMain`,
 * where the commonMain DAOs live. A rule that fires here fires on the metadata variant.
 *
 * The DAO corpus below mirrors the production tenant DAOs so the positive cases stay anchored to
 * real query shapes; keep it in sync when those DAOs change.
 */
class AccountScopedDaoRuleTest {

    private fun lint(code: String) = AccountScopedDaoRule(Config.empty).lint(code)
    private fun List<io.gitlab.arturbosch.detekt.api.Finding>.flagged(fn: String) =
        any { it.message.contains("`$fn`") || it.message.contains("$fn(") }

    // region the four leak shapes the rule must catch

    @Test
    fun firesOnByPkSelect_getById() {
        val findings = lint(CONVERSATION_DAO)
        assertTrue(findings.flagged("getById"), "by-PK SELECT must be flagged")
    }

    @Test
    fun firesOnTargetedUpdate_updateText() {
        val findings = lint(MESSAGE_DAO)
        assertTrue(findings.flagged("updateText"), "targeted UPDATE must be flagged")
    }

    @Test
    fun firesOnNoWhereDelete_deleteAll() {
        val findings = lint(CONVERSATION_TAG_DAO)
        assertTrue(findings.flagged("deleteAll"), "no-WHERE DELETE must be flagged")
    }

    @Test
    fun firesOnTransactionDefault_upsertPreservingTags() {
        val findings = lint(CONVERSATION_DAO)
        assertTrue(
            findings.flagged("upsertPreservingTags"),
            "@Transaction default-method body must be flagged (leak is in Kotlin, not a @Query)",
        )
    }

    // endregion

    // region must NOT fire (no false positives)

    @Test
    fun ignoresPlainUpsert() {
        // @Upsert abstract methods carry no SQL and no body; the NOT NULL accountId column enforces them.
        val findings = lint(CONVERSATION_DAO)
        assertFalse(findings.flagged("upsertAll"), "@Upsert must not be flagged")
        assertFalse(findings.flagged("upsert"), "@Upsert must not be flagged")
    }

    @Test
    fun acceptsAccountIdScopedQuery() {
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface ConversationDao {
                @Query("SELECT * FROM conversations WHERE conversationId = :id AND accountId = :acct")
                suspend fun getById(id: String, acct: String): Any?
            }
            """.trimIndent(),
        )
        assertFalse(findings.flagged("getById"), "accountId-scoped query must pass")
    }

    @Test
    fun crossAccountOptOutSuppresses() {
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            annotation class CrossAccount

            @Dao
            interface ConversationDao {
                @CrossAccount
                @Query("DELETE FROM conversations")
                suspend fun nukeForTests()
            }
            """.trimIndent(),
        )
        assertFalse(findings.flagged("nukeForTests"), "@CrossAccount must opt out")
    }

    @Test
    fun ignoresNonTenantTable() {
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface AgentDao {
                @Query("SELECT * FROM agents WHERE id = :id")
                suspend fun getById(id: String): Any?
            }
            """.trimIndent(),
        )
        assertFalse(findings.flagged("getById"), "non-tenant table must be ignored")
    }

    // endregion

    private companion object {
        // Mirrors core/data/.../db/dao/ConversationDao.kt (types stubbed to Any)
        val CONVERSATION_DAO = """
            import androidx.room.Dao
            import androidx.room.Query
            import androidx.room.Transaction
            import androidx.room.Upsert

            @Dao
            interface ConversationDao {
                @Query("SELECT * FROM conversations WHERE user = :userId AND isArchived = :isArchived ORDER BY updatedAt DESC LIMIT :limit")
                fun getConversations(userId: String, isArchived: Boolean = false, limit: Int = 25): Any

                @Query("SELECT * FROM conversations WHERE isArchived = :isArchived ORDER BY updatedAt DESC")
                fun getAllConversations(isArchived: Boolean = false): Any

                @Query("SELECT * FROM conversations WHERE conversationId = :id")
                suspend fun getById(id: String): Any?

                @Query("SELECT * FROM conversations WHERE conversationId = :id")
                fun observeById(id: String): Any

                @Upsert
                suspend fun upsert(conversation: Any)

                @Upsert
                suspend fun upsertAll(conversations: List<Any>)

                @Transaction
                suspend fun upsertPreservingTags(entities: List<Any>) {
                    for (entity in entities) {
                        upsert(entity)
                    }
                }

                @Query("DELETE FROM conversations WHERE conversationId = :id")
                suspend fun deleteById(id: String)

                @Query("UPDATE conversations SET title = :title WHERE conversationId = :id")
                suspend fun updateTitle(id: String, title: String)

                @Query("DELETE FROM conversations")
                suspend fun deleteAll()
            }
        """.trimIndent()

        // Mirrors core/data/.../db/dao/MessageDao.kt (types stubbed to Any)
        val MESSAGE_DAO = """
            import androidx.room.Dao
            import androidx.room.Query
            import androidx.room.Transaction
            import androidx.room.Upsert

            @Dao
            interface MessageDao {
                @Query("SELECT * FROM messages WHERE messageId = :messageId")
                suspend fun getById(messageId: String): Any?

                @Query("UPDATE messages SET text = :text, content = NULL WHERE messageId = :messageId")
                suspend fun updateText(messageId: String, text: String)

                @Transaction
                suspend fun replaceAllForConversation(conversationId: String, messages: List<Any>) {
                    upsertAll(messages)
                }

                @Upsert
                suspend fun upsertAll(messages: List<Any>)
            }
        """.trimIndent()

        // Mirrors core/data/.../db/dao/ConversationTagDao.kt (types stubbed to Any)
        val CONVERSATION_TAG_DAO = """
            import androidx.room.Dao
            import androidx.room.Query
            import androidx.room.Transaction
            import androidx.room.Upsert

            @Dao
            interface ConversationTagDao {
                @Query("SELECT * FROM conversation_tags ORDER BY position ASC")
                fun getAllTags(): Any

                @Upsert
                suspend fun upsertAll(tags: List<Any>)

                @Query("DELETE FROM conversation_tags")
                suspend fun deleteAll()

                @Transaction
                suspend fun replaceAll(tags: List<Any>) {
                    deleteAll()
                    upsertAll(tags)
                }
            }
        """.trimIndent()
    }
}
