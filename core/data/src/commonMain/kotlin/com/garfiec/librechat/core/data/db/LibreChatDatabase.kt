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
import com.garfiec.librechat.core.data.db.dao.ConversationDao
import com.garfiec.librechat.core.data.db.dao.ConversationTagDao
import com.garfiec.librechat.core.data.db.dao.DraftDao
import com.garfiec.librechat.core.data.db.dao.MessageDao
import com.garfiec.librechat.core.data.db.dao.PresetDao
import com.garfiec.librechat.core.data.db.entity.AgentEntity
import com.garfiec.librechat.core.data.db.entity.ConversationEntity
import com.garfiec.librechat.core.data.db.entity.ConversationTagEntity
import com.garfiec.librechat.core.data.db.entity.DraftEntity
import com.garfiec.librechat.core.data.db.entity.MessageEntity
import com.garfiec.librechat.core.data.db.entity.PresetEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AgentEntity::class,
        PresetEntity::class,
        ConversationTagEntity::class,
        DraftEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        // 4 -> 5 (manual MIGRATION_4_5) is account-tenancy: accountId + drop files table.
        // 5 -> 6 adds the nullable v0.8.7 conversation columns `pinned` (pinned conversations)
        // and `chatProjectId` (Chat Projects assignment). Bundled into one hop because neither
        // shipped as a released DB version — there is no in-the-wild v6 to migrate through.
        AutoMigration(from = 5, to = 6),
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
}

// Room KSP auto-generates the actual implementations for each platform
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object LibreChatDatabaseConstructor : RoomDatabaseConstructor<LibreChatDatabase>
