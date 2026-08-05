package com.garfiec.librechat.core.data.db

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import com.garfiec.librechat.core.data.db.converter.Converters
import com.garfiec.librechat.core.data.db.dao.AccountClaimDao
import com.garfiec.librechat.core.data.db.dao.AgentDao
import com.garfiec.librechat.core.data.db.dao.ArtifactShortcutDao
import com.garfiec.librechat.core.data.db.dao.ConversationDao
import com.garfiec.librechat.core.data.db.dao.ConversationTagDao
import com.garfiec.librechat.core.data.db.dao.DraftDao
import com.garfiec.librechat.core.data.db.dao.MessageDao
import com.garfiec.librechat.core.data.db.dao.PrefetchWatermarkDao
import com.garfiec.librechat.core.data.db.dao.PresetDao
import com.garfiec.librechat.core.data.db.dao.ServerDao
import com.garfiec.librechat.core.data.db.entity.AgentEntity
import com.garfiec.librechat.core.data.db.entity.ArtifactShortcutEntity
import com.garfiec.librechat.core.data.db.entity.ConversationEntity
import com.garfiec.librechat.core.data.db.entity.ConversationTagEntity
import com.garfiec.librechat.core.data.db.entity.DraftEntity
import com.garfiec.librechat.core.data.db.entity.MessageEntity
import com.garfiec.librechat.core.data.db.entity.PrefetchWatermarkEntity
import com.garfiec.librechat.core.data.db.entity.PresetEntity
import com.garfiec.librechat.core.data.db.entity.ServerEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AgentEntity::class,
        PresetEntity::class,
        ConversationTagEntity::class,
        DraftEntity::class,
        ArtifactShortcutEntity::class,
        ServerEntity::class,
        PrefetchWatermarkEntity::class,
    ],
    version = 9,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        // 3 -> 4 and 4 -> 5 are manual (MIGRATION_3_4 / MIGRATION_4_5 in Migrations.kt, registered
        // via addMigrations in the platform DI); 4 -> 5 is account-tenancy: accountId + drop files
        // table. 5 -> 6 adds every nullable v0.8.7 column in one hop — `pinned` and `chatProjectId`
        // on conversations (pinning + Chat Projects assignment) and `quotes` on messages
        // (referenced-text excerpts). Bundled into a single hop because none shipped as a released
        // DB version — there is no in-the-wild v6 to migrate through.
        AutoMigration(from = 5, to = 6),
        // 6 -> 7 adds the device-scoped artifact_shortcuts table (home-screen artifact snapshots).
        AutoMigration(from = 6, to = 7),
        // 7 -> 8 adds the device-scoped servers table (per-deployment registry; gateway headers).
        AutoMigration(from = 7, to = 8),
        // 8 -> 9 adds the account-scoped prefetch_watermarks table (background cache warming).
        AutoMigration(from = 8, to = 9),
    ],
)
@TypeConverters(Converters::class)
@ConstructedBy(LibreChatDatabaseConstructor::class)
abstract class LibreChatDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun agentDao(): AgentDao
    abstract fun presetDao(): PresetDao
    abstract fun conversationTagDao(): ConversationTagDao
    abstract fun draftDao(): DraftDao
    abstract fun accountClaimDao(): AccountClaimDao
    abstract fun artifactShortcutDao(): ArtifactShortcutDao
    abstract fun serverDao(): ServerDao
    abstract fun prefetchWatermarkDao(): PrefetchWatermarkDao
}

// Room KSP auto-generates the actual implementations for each platform
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object LibreChatDatabaseConstructor : RoomDatabaseConstructor<LibreChatDatabase>
