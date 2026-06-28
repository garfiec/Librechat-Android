package com.garfiec.librechat.core.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Pre-#67 builds stored Conversation.endpoint and endpointType as the Kotlin
 * enum `.name` (e.g. "OPENAI"). #67's writer emits the wire-format SerialName
 * ("openAI"); the migration rewrites legacy rows in place so the read-side
 * shim in ConversationMapper can be deleted.
 *
 * Hardcoded pairs (not iterated over EModelEndpoint.entries) keep this a
 * frozen historical artifact — future enum edits do not change which legacy
 * strings get rewritten.
 *
 * Note on lossy AGENTS: pre-#67 ChatPayloadBuilder silently coerced any
 * unknown endpoint name (OpenRouter, Deepseek, xAI, …) to EModelEndpoint.AGENTS
 * before persistence. So a legacy row with `endpoint = "AGENTS"` may have
 * originated from any custom endpoint and the original name is unrecoverable.
 * Rewriting "AGENTS" -> "agents" preserves the same lossy outcome the
 * read-side shim already produced. This is by design.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        listOf(
            "AZURE_OPENAI" to "azureOpenAI",
            "OPENAI" to "openAI",
            "GOOGLE" to "google",
            "ANTHROPIC" to "anthropic",
            "ASSISTANTS" to "assistants",
            "AZURE_ASSISTANTS" to "azureAssistants",
            "AGENTS" to "agents",
            "CUSTOM" to "custom",
            "BEDROCK" to "bedrock",
        ).forEach { (legacy, wire) ->
            connection.execSQL("UPDATE conversations SET endpoint = '$wire' WHERE endpoint = '$legacy'")
            connection.execSQL("UPDATE conversations SET endpointType = '$wire' WHERE endpointType = '$legacy'")
            connection.execSQL("UPDATE presets SET endpoint = '$wire' WHERE endpoint = '$legacy'")
        }
    }
}

/**
 * Row-tenancy schema change (account-isolation).
 *
 * Two DDL steps that ship together as the single account-tenancy schema bump:
 *
 * 1. Adds a nullable `accountId` owner column to the four tenant tables — `conversations`, `messages`,
 *    `drafts`, `conversation_tags`. The column is **nullable with no default**: rows that exist at
 *    migration time were written by the single legacy (pre-multi-account) user and are left NULL here;
 *    a separate one-time runtime *claim* (gated on the active-account registry, not this DDL) stamps
 *    those legacy rows for the resolved account once identity is known, and the reads are NULL-safe /
 *    fail-open until then. Keeping the claim out of the migration is deliberate: the migration runs
 *    before any account identity is available, so it cannot know which account owns the legacy rows.
 * 2. Drops the dead `files` table. It was a Room entity from v1 but was never read or written — the
 *    file DAO was bound in DI yet injected nowhere, and FileRepository reads files straight from the
 *    API. Carrying it forward meant a stray, account-unaware cache table that would have needed its
 *    own row-tenancy scoping; removing the dead table is simpler than scoping data nothing produces.
 *    `IF EXISTS` keeps it safe on any odd v4 DB.
 *
 * Add-column is pure additive DDL and the drop is guarded, so the whole step is idempotent-safe for
 * Room's single-shot migration contract. Both DDL steps ship as one hop because this feature has not
 * shipped — there is no released v5 DB in the wild to migrate through.
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        listOf("conversations", "messages", "drafts", "conversation_tags").forEach { table ->
            connection.execSQL("ALTER TABLE $table ADD COLUMN accountId TEXT")
        }
        connection.execSQL("DROP TABLE IF EXISTS files")
    }
}
