package com.garfiec.librechat.core.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.garfiec.librechat.core.data.db.migration.MIGRATION_3_4
import com.garfiec.librechat.core.data.db.migration.MIGRATION_4_5
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates Room migrations preserve entity data (auto v1→v2→v3) and that
 * the manual v3→v4 normalization rewrites legacy enum-name endpoint values
 * to their wire-format counterparts.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {

    private val testDbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LibreChatDatabase::class.java,
    )

    @Test
    fun migrateV1ToV3_allEntitiesPreserved() {
        // --- Create v1 database with all 6 entity types ---
        val db = helper.createDatabase(testDbName, 1)

        // 1. Conversation with JSON tags and modelParams
        db.insert(
            "conversations",
            SQLiteDatabase.CONFLICT_REPLACE,
            ContentValues().apply {
                put("conversationId", "conv-001")
                put("title", "Migration Test Chat")
                put("user", "user-001")
                put("endpoint", "openAI")
                put("endpointType", "custom")
                put("model", "gpt-4o")
                put("agentId", "agent-001")
                put("isArchived", 0)
                put("tags", """["important","work"]""")
                put("iconURL", "https://example.com/icon.png")
                put("greeting", "Hello!")
                put("modelParams", """{"temperature":0.7,"maxTokens":4096}""")
                put("createdAt", 1700000000000L)
                put("updatedAt", 1700001000000L)
                put("lastSyncedAt", 1700000500000L)
            },
        )

        // 2. Message with JSON content, files, attachments, feedback, metadata
        db.insert(
            "messages",
            SQLiteDatabase.CONFLICT_REPLACE,
            ContentValues().apply {
                put("messageId", "msg-001")
                put("conversationId", "conv-001")
                put("parentMessageId", "msg-000")
                put("sender", "Assistant")
                put("text", "Hello from migration test!")
                put("content", """[{"type":"text","text":"Hello"},{"type":"image_file","image_file":{"file_id":"f1"}}]""")
                put("isCreatedByUser", 0)
                put("model", "gpt-4o")
                put("endpoint", "openAI")
                put("iconURL", null as String?)
                put("unfinished", 0)
                put("error", 0)
                put("finishReason", "stop")
                put("tokenCount", 42)
                put("feedback", """{"rating":"thumbsUp","comment":"Great response"}""")
                put("files", """[{"file_id":"f1","filename":"test.png","type":"image/png"}]""")
                put("attachments", """[{"type":"file","url":"https://example.com/doc.pdf"}]""")
                put("metadata", """{"plugins":[],"finish_reason":"stop"}""")
                put("createdAt", 1700000100000L)
                put("updatedAt", 1700000200000L)
            },
        )

        // 3. File entity
        db.insert(
            "files",
            SQLiteDatabase.CONFLICT_REPLACE,
            ContentValues().apply {
                put("fileId", "file-001")
                put("user", "user-001")
                put("conversationId", "conv-001")
                put("messageId", "msg-001")
                put("filename", "screenshot.png")
                put("filepath", "/uploads/user-001/screenshot.png")
                put("type", "image/png")
                put("bytes", 102400L)
                put("source", "local")
                put("width", 1920)
                put("height", 1080)
                put("createdAt", 1700000100000L)
                put("updatedAt", 1700000100000L)
            },
        )

        // 4. Agent with JSON tools and conversationStarters
        db.insert(
            "agents",
            SQLiteDatabase.CONFLICT_REPLACE,
            ContentValues().apply {
                put("id", "agent-001")
                put("name", "Code Helper")
                put("description", "Helps with code")
                put("avatar", """{"filepath":"/images/agent.png","source":"local"}""")
                put("provider", "openAI")
                put("model", "gpt-4o")
                put("category", "coding")
                put("authorName", "test-user")
                put("isPromoted", 1)
                put("conversationStarters", """["Write a function","Debug this code","Explain this pattern"]""")
                put("tools", """["code_interpreter","file_search","dalle"]""")
                put("updatedAt", 1700000000000L)
            },
        )

        // 5. Preset with JSON params
        // Note: "order" is a SQL reserved word, so use execSQL with backtick-quoted column
        db.execSQL(
            """INSERT INTO presets (presetId, title, endpoint, model, isDefault, `order`, params, createdAt, updatedAt)
               VALUES ('preset-001', 'Creative Writing', 'openAI', 'gpt-4o', 1, 0,
                       '{"temperature":0.9,"topP":0.95,"maxTokens":8192}', 1700000000000, 1700000000000)""",
        )

        // 6. Conversation tag
        db.insert(
            "conversation_tags",
            SQLiteDatabase.CONFLICT_REPLACE,
            ContentValues().apply {
                put("tag", "important")
                put("user", "user-001")
                put("description", "Important conversations")
                put("count", 5)
                put("position", 0)
                put("createdAt", 1700000000000L)
                put("updatedAt", 1700000000000L)
            },
        )

        db.close()

        // --- Run migrations v1 → v2 → v3 ---
        val migratedDb = helper.runMigrationsAndValidate(testDbName, 3, true)

        // --- Verify all data survived ---

        // Conversations
        migratedDb.query("SELECT * FROM conversations WHERE conversationId = 'conv-001'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("title"))).isEqualTo("Migration Test Chat")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("endpoint"))).isEqualTo("openAI")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("tags"))).isEqualTo("""["important","work"]""")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("modelParams"))).isEqualTo("""{"temperature":0.7,"maxTokens":4096}""")
            assertThat(cursor.getLong(cursor.getColumnIndexOrThrow("createdAt"))).isEqualTo(1700000000000L)
        }

        // Messages (with all JSON fields)
        migratedDb.query("SELECT * FROM messages WHERE messageId = 'msg-001'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("text"))).isEqualTo("Hello from migration test!")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("content"))).contains("image_file")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("feedback"))).contains("thumbsUp")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("files"))).contains("test.png")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("attachments"))).contains("doc.pdf")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("metadata"))).contains("finish_reason")
            assertThat(cursor.getInt(cursor.getColumnIndexOrThrow("tokenCount"))).isEqualTo(42)
        }

        // Files
        migratedDb.query("SELECT * FROM files WHERE fileId = 'file-001'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("filename"))).isEqualTo("screenshot.png")
            assertThat(cursor.getInt(cursor.getColumnIndexOrThrow("width"))).isEqualTo(1920)
            assertThat(cursor.getLong(cursor.getColumnIndexOrThrow("bytes"))).isEqualTo(102400L)
        }

        // Agents (with JSON fields)
        migratedDb.query("SELECT * FROM agents WHERE id = 'agent-001'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("name"))).isEqualTo("Code Helper")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("avatar"))).contains("filepath")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("tools"))).contains("code_interpreter")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("conversationStarters"))).contains("Debug this code")
        }

        // Presets (with JSON params)
        migratedDb.query("SELECT * FROM presets WHERE presetId = 'preset-001'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("title"))).isEqualTo("Creative Writing")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("params"))).contains("temperature")
        }

        // Conversation tags
        migratedDb.query("SELECT * FROM conversation_tags WHERE tag = 'important'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(cursor.getColumnIndexOrThrow("count"))).isEqualTo(5)
        }

        // v1→v2 added drafts table — verify it exists and is writable
        migratedDb.execSQL(
            "INSERT INTO drafts (conversation_id, text, updated_at) VALUES ('conv-001', 'Draft text', 1700002000000)",
        )
        migratedDb.query("SELECT * FROM drafts WHERE conversation_id = 'conv-001'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("text"))).isEqualTo("Draft text")
        }

        migratedDb.close()
    }

    @Test
    fun migrateV1ToCurrent_viaRoomApi_entitiesReadable() {
        // Create and populate a v1 database
        val db = helper.createDatabase(testDbName, 1)
        db.insert(
            "conversations",
            SQLiteDatabase.CONFLICT_REPLACE,
            ContentValues().apply {
                put("conversationId", "conv-api-test")
                put("title", "Room API Test")
                put("user", "user-001")
                put("isArchived", 0)
                put("tags", "[]")
                put("createdAt", 1700000000000L)
                put("updatedAt", 1700000000000L)
                put("lastSyncedAt", 0L)
            },
        )
        db.close()

        // Open with Room (auto-runs 1→2→3, the manual 3→4 and 4→5, then the auto 5→6). All
        // migrations through the current @Database version must be registered or the open throws.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roomDb = Room.databaseBuilder(
            context,
            LibreChatDatabase::class.java,
            testDbName,
        )
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
            .build()

        // Verify we can read the migrated data (raw query: the migrated row carries a null accountId,
        // so the account-scoped DAO reads can't see it).
        val title = roomDb.query(
            "SELECT title FROM conversations WHERE conversationId = ?",
            arrayOf("conv-api-test"),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        assertThat(title).isEqualTo("Room API Test")

        roomDb.close()
    }

    /**
     * Pre-#67 builds wrote Conversation.endpoint and endpointType as the
     * Kotlin enum `.name`. The v3→v4 migration rewrites those rows to the
     * wire-format SerialName so ConversationMapper can drop its read-side shim.
     */
    @Test
    fun migrateV3ToV4_normalizesLegacyEndpointEnumNames() {
        val db = helper.createDatabase(testDbName, 3)

        insertLegacyConversation(db, id = "legacy-openai", endpoint = "OPENAI", endpointType = "OPENAI")
        insertLegacyConversation(db, id = "legacy-azure", endpoint = "AZURE_OPENAI", endpointType = "AZURE_OPENAI")
        insertLegacyConversation(db, id = "legacy-anthro", endpoint = "ANTHROPIC", endpointType = null)
        insertLegacyConversation(db, id = "legacy-google", endpoint = "GOOGLE", endpointType = "GOOGLE")
        insertLegacyConversation(db, id = "legacy-bedrock", endpoint = "BEDROCK", endpointType = "BEDROCK")
        insertLegacyConversation(db, id = "legacy-assist", endpoint = "ASSISTANTS", endpointType = "ASSISTANTS")
        insertLegacyConversation(db, id = "legacy-azassist", endpoint = "AZURE_ASSISTANTS", endpointType = "AZURE_ASSISTANTS")
        insertLegacyConversation(db, id = "legacy-custom", endpoint = "CUSTOM", endpointType = "CUSTOM")

        // Idempotency: already-wire-format rows are untouched
        insertLegacyConversation(db, id = "post-fix", endpoint = "openAI", endpointType = "openAI")

        // Custom endpoint name passes through (the issue #60 case)
        insertLegacyConversation(db, id = "custom-name", endpoint = "OpenRouter", endpointType = "custom")

        // Null endpoints unaffected
        insertLegacyConversation(db, id = "null-ep", endpoint = null, endpointType = null)

        db.close()

        val migrated = helper.runMigrationsAndValidate(testDbName, 4, true, MIGRATION_3_4)

        assertEndpointPair(migrated, "legacy-openai", endpoint = "openAI", endpointType = "openAI")
        assertEndpointPair(migrated, "legacy-azure", endpoint = "azureOpenAI", endpointType = "azureOpenAI")
        assertEndpointPair(migrated, "legacy-anthro", endpoint = "anthropic", endpointType = null)
        assertEndpointPair(migrated, "legacy-google", endpoint = "google", endpointType = "google")
        assertEndpointPair(migrated, "legacy-bedrock", endpoint = "bedrock", endpointType = "bedrock")
        assertEndpointPair(migrated, "legacy-assist", endpoint = "assistants", endpointType = "assistants")
        assertEndpointPair(migrated, "legacy-azassist", endpoint = "azureAssistants", endpointType = "azureAssistants")
        assertEndpointPair(migrated, "legacy-custom", endpoint = "custom", endpointType = "custom")
        assertEndpointPair(migrated, "post-fix", endpoint = "openAI", endpointType = "openAI")
        assertEndpointPair(migrated, "custom-name", endpoint = "OpenRouter", endpointType = "custom")
        assertEndpointPair(migrated, "null-ep", endpoint = null, endpointType = null)

        migrated.close()
    }

    /**
     * Pre-#67 ChatPayloadBuilder silently coerced any unknown endpoint name
     * (OpenRouter, Deepseek, xAI, …) to EModelEndpoint.AGENTS, so a legacy
     * row with `endpoint = "AGENTS"` may have originated from any custom
     * endpoint and the original name is unrecoverable.
     *
     * The v3→v4 migration rewrites "AGENTS" → "agents" — same lossy outcome
     * the read-side shim already produced. This test pins that contract so a
     * future "smart" migration can't silently change the cached/fresh mismatch
     * behavior without an explicit decision.
     */
    @Test
    fun migrateV3ToV4_legacyAgentsRowMapsToLowercaseAgents_lossyByDesign() {
        val db = helper.createDatabase(testDbName, 3)
        insertLegacyConversation(db, id = "lossy-agents", endpoint = "AGENTS", endpointType = "AGENTS")
        db.close()

        val migrated = helper.runMigrationsAndValidate(testDbName, 4, true, MIGRATION_3_4)

        assertEndpointPair(migrated, "lossy-agents", endpoint = "agents", endpointType = "agents")

        migrated.close()
    }

    /**
     * `presets` table is migrated defensively. PresetDao currently has no
     * production callers (PresetRepositoryImpl is API-only), so the table is
     * empty in the field. Test exists so the symmetry is enforced if a future
     * change starts persisting presets.
     */
    @Test
    fun migrateV3ToV4_normalizesLegacyPresetEndpoint() {
        val db = helper.createDatabase(testDbName, 3)
        db.execSQL(
            "INSERT INTO presets (presetId, title, endpoint, model, isDefault, `order`, params, createdAt, updatedAt) " +
                "VALUES ('preset-legacy', 'Legacy', 'OPENAI', 'gpt-4o', 0, 0, '{}', 1700000000000, 1700000000000)",
        )
        db.execSQL(
            "INSERT INTO presets (presetId, title, endpoint, model, isDefault, `order`, params, createdAt, updatedAt) " +
                "VALUES ('preset-custom', 'Custom', 'OpenRouter', 'llama', 0, 1, '{}', 1700000000000, 1700000000000)",
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(testDbName, 4, true, MIGRATION_3_4)

        migrated.query("SELECT endpoint FROM presets WHERE presetId = 'preset-legacy'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("openAI")
        }
        migrated.query("SELECT endpoint FROM presets WHERE presetId = 'preset-custom'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("OpenRouter")
        }

        migrated.close()
    }

    private fun insertLegacyConversation(
        db: SupportSQLiteDatabase,
        id: String,
        endpoint: String?,
        endpointType: String?,
    ) {
        db.insert(
            "conversations",
            SQLiteDatabase.CONFLICT_REPLACE,
            ContentValues().apply {
                put("conversationId", id)
                put("title", "Title $id")
                put("user", "user-001")
                put("endpoint", endpoint)
                put("endpointType", endpointType)
                put("isArchived", 0)
                put("tags", "[]")
                put("createdAt", 1700000000000L)
                put("updatedAt", 1700000000000L)
                put("lastSyncedAt", 0L)
            },
        )
    }

    private fun assertEndpointPair(
        db: SupportSQLiteDatabase,
        id: String,
        endpoint: String?,
        endpointType: String?,
    ) {
        db.query("SELECT endpoint, endpointType FROM conversations WHERE conversationId = '$id'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            val actualEndpoint = if (cursor.isNull(0)) null else cursor.getString(0)
            val actualEndpointType = if (cursor.isNull(1)) null else cursor.getString(1)
            assertThat(actualEndpoint).isEqualTo(endpoint)
            assertThat(actualEndpointType).isEqualTo(endpointType)
        }
    }
}
