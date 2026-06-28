package com.garfiec.librechat.core.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.garfiec.librechat.core.data.db.migration.MIGRATION_4_5
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Host-JVM test of the row-tenancy schema migration (account-isolation),
 * running on the Path B lane established by [RoomHostJvmSmokeTest].
 *
 * Drives [MIGRATION_4_5] directly against an in-memory connection from [AndroidSQLiteDriver] — the
 * framework-backed driver, shadowed by Robolectric on the JVM, the same engine production Android
 * runs. This gives the migration the exact `androidx.sqlite.SQLiteConnection` its `migrate()` takes,
 * with no instrumentation or schema-asset plumbing.
 *
 * It asserts the migration's contract: every tenant table gains a **nullable** `accountId` whose value
 * is NULL for rows that predate the migration (legacy single-account data, claimed later at runtime),
 * pre-existing data is untouched, the new column is writable so the claim can stamp it, and the dead
 * `files` table (never read or written) is dropped in the same hop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration4To5Test {

    private val driver = AndroidSQLiteDriver()

    @Test
    fun addsNullableAccountIdToAllFourTenantTables_preservingLegacyRows() {
        val connection = driver.open(":memory:")
        try {
            createV4TenantTables(connection)
            seedLegacyRows(connection)
            // The dead `files` table is present in real v4 DBs; seed it so the drop has a target.
            connection.execSQL("CREATE TABLE files (fileId TEXT NOT NULL PRIMARY KEY, user TEXT NOT NULL)")
            connection.execSQL("INSERT INTO files (fileId, user) VALUES ('f1', 'u1')")

            MIGRATION_4_5.migrate(connection)

            // accountId exists and is NULL on every tenant table's legacy rows.
            assertAccountIdIsNull(connection, "conversations", "conversationId", "c1")
            assertAccountIdIsNull(connection, "messages", "messageId", "m1")
            assertAccountIdIsNull(connection, "drafts", "conversation_id", "c1")
            assertAccountIdIsNull(connection, "conversation_tags", "tag", "work")

            // Pre-existing data is preserved across the migration.
            assertEquals(
                "Legacy chat",
                queryText(connection, "SELECT title FROM conversations WHERE conversationId = 'c1'"),
            )

            // The new column is writable — this is what the one-time claim will do.
            connection.execSQL("UPDATE conversations SET accountId = 'acct-A' WHERE conversationId = 'c1'")
            assertEquals(
                "acct-A",
                queryText(connection, "SELECT accountId FROM conversations WHERE conversationId = 'c1'"),
            )

            // The dead `files` table is dropped in the same hop; an unrelated tenant table survives.
            assertFalse(tableExists(connection, "files"), "expected files table to be dropped")
            assertTrue(tableExists(connection, "conversations"), "unrelated table must survive")
        } finally {
            connection.close()
        }
    }

    @Test
    fun isSafeWhenFilesTableAlreadyAbsent() {
        val connection = driver.open(":memory:")
        try {
            // v4 tables without a `files` table — DROP TABLE IF EXISTS must not throw.
            createV4TenantTables(connection)

            MIGRATION_4_5.migrate(connection)

            assertFalse(tableExists(connection, "files"))
        } finally {
            connection.close()
        }
    }

    private fun createV4TenantTables(connection: SQLiteConnection) {
        // Minimal v4-shaped tenant tables (pre-accountId). Only the migration's ADD COLUMN is under
        // test, so representative columns suffice to prove the column add + data preservation.
        connection.execSQL(
            "CREATE TABLE conversations (conversationId TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, user TEXT NOT NULL)",
        )
        connection.execSQL(
            "CREATE TABLE messages (messageId TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL)",
        )
        connection.execSQL(
            "CREATE TABLE drafts (conversation_id TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL)",
        )
        connection.execSQL(
            "CREATE TABLE conversation_tags (id INTEGER PRIMARY KEY AUTOINCREMENT, tag TEXT NOT NULL, user TEXT NOT NULL)",
        )
    }

    private fun seedLegacyRows(connection: SQLiteConnection) {
        connection.execSQL("INSERT INTO conversations (conversationId, title, user) VALUES ('c1', 'Legacy chat', 'u1')")
        connection.execSQL("INSERT INTO messages (messageId, conversationId) VALUES ('m1', 'c1')")
        connection.execSQL("INSERT INTO drafts (conversation_id, text) VALUES ('c1', 'unsent')")
        connection.execSQL("INSERT INTO conversation_tags (tag, user) VALUES ('work', 'u1')")
    }

    private fun assertAccountIdIsNull(
        connection: SQLiteConnection,
        table: String,
        keyColumn: String,
        keyValue: String,
    ) {
        val statement = connection.prepare("SELECT accountId FROM $table WHERE $keyColumn = '$keyValue'")
        try {
            assertTrue(statement.step(), "expected a row in $table for $keyColumn='$keyValue'")
            assertTrue(statement.isNull(0), "expected accountId NULL on legacy $table row")
        } finally {
            statement.close()
        }
    }

    private fun queryText(connection: SQLiteConnection, sql: String): String {
        val statement = connection.prepare(sql)
        try {
            assertTrue(statement.step(), "expected a row for: $sql")
            return statement.getText(0)
        } finally {
            statement.close()
        }
    }

    private fun tableExists(connection: SQLiteConnection, name: String): Boolean {
        val statement = connection.prepare(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$name'",
        )
        try {
            assertTrue(statement.step(), "expected a count row")
            return statement.getLong(0) > 0
        } finally {
            statement.close()
        }
    }
}
