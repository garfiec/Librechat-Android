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

    @Test
    fun firesOnEntityDelete() {
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Delete

            @Dao
            interface ConversationDao {
                @Delete
                suspend fun delete(entity: Any)
            }
            """.trimIndent(),
        )
        assertTrue(
            findings.flagged("delete"),
            "entity @Delete matches by PK only (no accountId) and must be flagged",
        )
    }

    @Test
    fun firesOnEntityUpdate() {
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Update

            @Dao
            interface MessageDao {
                @Update
                suspend fun update(entity: Any)
            }
            """.trimIndent(),
        )
        assertTrue(
            findings.flagged("update"),
            "entity @Update matches by PK only (no accountId) and must be flagged",
        )
    }

    // endregion

    // region must NOT fire (no false positives)

    @Test
    fun ignoresEntityInsert() {
        // @Insert/@Upsert attribute the account through the entity's accountId field, which a SQL-text
        // rule can't see; they are deliberately out of scope (callsite + SessionWriter own attribution).
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Insert

            @Dao
            interface ConversationDao {
                @Insert
                suspend fun insert(entity: Any)
            }
            """.trimIndent(),
        )
        assertFalse(findings.flagged("insert"), "@Insert must not be flagged (field-attributed)")
    }

    @Test
    fun acceptsCrossAccountOnEntityDelete() {
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Delete
            import com.garfiec.librechat.core.common.identity.CrossAccount

            @Dao
            interface ConversationDao {
                @CrossAccount
                @Delete
                suspend fun delete(entity: Any)
            }
            """.trimIndent(),
        )
        assertFalse(findings.flagged("delete"), "@CrossAccount must opt an entity @Delete out")
    }

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

    @Test
    fun tenantTableMatchIsCaseInsensitive_firesWhenUnscoped() {
        // SQLite identifiers are case-insensitive, so `FROM Messages` / `FROM CONVERSATIONS` hit the
        // real tenant table and an unscoped statement must still be flagged regardless of casing.
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface MessageDao {
                @Query("DELETE FROM Messages WHERE messageId = :id")
                suspend fun nukeByCasing(id: String)
            }
            """.trimIndent(),
        )
        assertTrue(findings.flagged("nukeByCasing"), "case-variant tenant table must still be flagged when unscoped")
    }

    // endregion

    // region hardened matcher (R5-D): WHERE-scoped predicate, concatenation, @RawQuery, interpolation

    @Test
    fun parsesConcatenatedSql_firesWhenUnscoped() {
        // A `"a" + "b"` concatenated query must be matched as one SQL string, not just single literals.
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface MessageDao {
                @Query(
                    "SELECT * FROM messages WHERE conversationId = :c " +
                        "AND parentMessageId = :p ORDER BY createdAt ASC",
                )
                suspend fun getSiblings(c: String, p: String): Any
            }
            """.trimIndent(),
        )
        assertTrue(findings.flagged("getSiblings"), "unscoped concatenated SQL must be flagged")
    }

    @Test
    fun parsesConcatenatedSql_passesWhenScoped() {
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface MessageDao {
                @Query(
                    "SELECT * FROM messages WHERE conversationId = :c AND accountId = :a " +
                        "ORDER BY createdAt ASC",
                )
                suspend fun getSiblingsForAccount(c: String, a: String): Any
            }
            """.trimIndent(),
        )
        assertFalse(findings.flagged("getSiblingsForAccount"), "scoped concatenated SQL must pass")
    }

    @Test
    fun setAccountIdIsNotScoping() {
        // `SET accountId = :x` writes the column; it does not restrict which rows are touched. Only a
        // WHERE-clause predicate scopes, so this must be flagged despite containing "accountId =".
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface ConversationDao {
                @Query("UPDATE conversations SET accountId = :a WHERE conversationId = :id")
                suspend fun reassign(a: String, id: String)
            }
            """.trimIndent(),
        )
        assertTrue(findings.flagged("reassign"), "accountId in SET (not WHERE) must not count as scoping")
    }

    @Test
    fun isNullIsNotPositiveScoping() {
        // `accountId IS NULL` is the legacy-claim shape, not single-account scoping -> must be flagged
        // (the real claim DAO opts out with @CrossAccount instead).
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface ConversationDao {
                @Query("DELETE FROM conversations WHERE accountId IS NULL")
                suspend fun deleteUnclaimed()
            }
            """.trimIndent(),
        )
        assertTrue(findings.flagged("deleteUnclaimed"), "accountId IS NULL must not count as scoping")
    }

    @Test
    fun acceptsAccountIdInPredicate() {
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface ConversationDao {
                @Query("SELECT * FROM conversations WHERE accountId IN (:a, :b)")
                suspend fun forAccounts(a: String, b: String): Any
            }
            """.trimIndent(),
        )
        assertFalse(findings.flagged("forAccounts"), "accountId IN (...) must count as scoping")
    }

    @Test
    fun firesOnRawQueryInTenantDao() {
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.RawQuery

            @Dao
            interface ConversationDao {
                @RawQuery
                suspend fun raw(query: Any): Any
            }
            """.trimIndent(),
        )
        assertTrue(findings.flagged("raw"), "@RawQuery in a tenant DAO must be flagged")
    }

    @Test
    fun firesOnInterpolatedQueryInTenantDao() {
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface MessageDao {
                @Query("SELECT * FROM messages WHERE messageId = ${'$'}id")
                suspend fun byId(id: String): Any
            }
            """.trimIndent(),
        )
        assertTrue(findings.flagged("byId"), "non-constant (interpolated) @Query in a tenant DAO must be flagged")
    }

    @Test
    fun subqueryScopedPredicateIsNotOuterScoping() {
        // accountId appears only inside a subquery; the outer messages statement is unscoped.
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface MessageDao {
                @Query(
                    "DELETE FROM messages WHERE conversationId IN " +
                        "(SELECT conversationId FROM conversations WHERE accountId = :a)",
                )
                suspend fun deleteForConv(a: String)
            }
            """.trimIndent(),
        )
        assertTrue(findings.flagged("deleteForConv"), "subquery-only accountId must not count as outer scoping")
    }

    @Test
    fun topLevelOrIsNotScoping() {
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface MessageDao {
                @Query("SELECT * FROM messages WHERE messageId = :id OR accountId = :a")
                suspend fun byIdOrAccount(id: String, a: String): Any
            }
            """.trimIndent(),
        )
        assertTrue(findings.flagged("byIdOrAccount"), "accountId joined by a top-level OR is not restrictive scoping")
    }

    @Test
    fun differentlyNamedColumnEndingInAccountIdIsNotScoping() {
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface ConversationDao {
                @Query("SELECT * FROM conversations WHERE userAccountId = :a")
                suspend fun byWrongColumn(a: String): Any
            }
            """.trimIndent(),
        )
        assertTrue(findings.flagged("byWrongColumn"), "a column merely ending in 'accountId' must not count as scoping")
    }

    @Test
    fun inferredTenantDao_concreteBodyFlagged_evenWhenNotInNameList() {
        // A DAO not named in TENANT_DAOS but carrying a tenant @Query is inferred to be a tenant DAO,
        // so its unscoped concrete @Transaction body is still flagged.
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query
            import androidx.room.Transaction
            import androidx.room.Upsert

            @Dao
            interface MessageSearchDao {
                @Query("SELECT * FROM messages WHERE accountId = :a")
                fun forAccount(a: String): Any

                @Transaction
                suspend fun rebuild(a: String, rows: List<Any>) {
                    upsertAll(rows)
                }

                @Upsert
                suspend fun upsertAll(rows: List<Any>)
            }
            """.trimIndent(),
        )
        assertTrue(findings.flagged("rebuild"), "concrete body in an inferred tenant DAO must be flagged")
        assertFalse(findings.flagged("forAccount"), "the scoped @Query that triggered inference must itself pass")
    }

    @Test
    fun acceptsParenthesizedAccountIdPredicate() {
        // A validly-grouped accountId predicate must pass; the matcher must not strip it as if it were a
        // subquery / value-list (that was a false positive that would push authors to a needless opt-out).
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface MessageDao {
                @Query("DELETE FROM messages WHERE (accountId = :a) AND conversationId = :c")
                suspend fun deleteForConv(a: String, c: String)
            }
            """.trimIndent(),
        )
        assertFalse(findings.flagged("deleteForConv"), "a parenthesized (accountId = :a) predicate must count as scoping")
    }

    @Test
    fun subqueryWhereBeforeOuterWhere_isNotScoping() {
        // The accountId WHERE lives in a subquery in the SET clause, textually before the outer WHERE.
        // The outer UPDATE is scoped only by messageId -> must be flagged (the first-WHERE trap).
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface MessageDao {
                @Query(
                    "UPDATE messages SET text = " +
                        "(SELECT text FROM messages WHERE accountId = :a LIMIT 1) WHERE messageId = :id",
                )
                suspend fun copyText(a: String, id: String)
            }
            """.trimIndent(),
        )
        assertTrue(findings.flagged("copyText"), "a subquery WHERE must not be mistaken for the outer scoping WHERE")
    }

    @Test
    fun multiTableJoinStatement_isFailClosed() {
        // A statement spanning two tenant tables / a JOIN is fail-closed: a regex can't prove which
        // table the accountId predicate scopes, so even a qualified `c.accountId` must not pass.
        val findings = lint(
            """
            import androidx.room.Dao
            import androidx.room.Query

            @Dao
            interface MessageDao {
                @Query(
                    "SELECT m.* FROM messages m JOIN conversations c " +
                        "ON m.conversationId = c.conversationId WHERE c.accountId = :a",
                )
                suspend fun joinedForAccount(a: String): Any
            }
            """.trimIndent(),
        )
        assertTrue(findings.flagged("joinedForAccount"), "a multi-table/JOIN statement must be fail-closed")
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
