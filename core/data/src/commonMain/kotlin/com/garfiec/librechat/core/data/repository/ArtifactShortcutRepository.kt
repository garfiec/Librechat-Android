package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.model.ArtifactShortcut
import kotlinx.coroutines.flow.Flow

interface ArtifactShortcutRepository {
    suspend fun save(shortcut: ArtifactShortcut)
    suspend fun get(id: String): ArtifactShortcut?
    fun observeAll(): Flow<List<ArtifactShortcut>>
    suspend fun delete(id: String)
}
