package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.data.db.dao.ArtifactShortcutDao
import com.garfiec.librechat.core.data.db.entity.ArtifactShortcutEntity
import com.garfiec.librechat.core.model.ArtifactShortcut
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ArtifactShortcutRepositoryImpl(
    private val dao: ArtifactShortcutDao,
    private val ioDispatcher: CoroutineDispatcher,
) : ArtifactShortcutRepository {

    override suspend fun save(shortcut: ArtifactShortcut) = withContext(ioDispatcher) {
        dao.upsert(shortcut.toEntity())
    }

    override suspend fun get(id: String): ArtifactShortcut? = withContext(ioDispatcher) {
        dao.getById(id)?.toModel()
    }

    override fun observeAll(): Flow<List<ArtifactShortcut>> =
        dao.observeAll().map { entities -> entities.map { it.toModel() } }.flowOn(ioDispatcher)

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        dao.deleteById(id)
    }
}

private fun ArtifactShortcutEntity.toModel() = ArtifactShortcut(
    id = id,
    label = shortcutLabel,
    emoji = emoji,
    identifier = identifier,
    type = type,
    title = title,
    language = language,
    content = content,
    version = version,
    createdAt = createdAt,
)

private fun ArtifactShortcut.toEntity() = ArtifactShortcutEntity(
    id = id,
    shortcutLabel = label,
    emoji = emoji,
    identifier = identifier,
    type = type,
    title = title,
    language = language,
    content = content,
    version = version,
    createdAt = createdAt,
)
