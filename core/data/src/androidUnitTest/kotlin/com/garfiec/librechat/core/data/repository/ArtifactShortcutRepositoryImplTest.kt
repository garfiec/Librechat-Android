package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.data.db.dao.ArtifactShortcutDao
import com.garfiec.librechat.core.data.db.entity.ArtifactShortcutEntity
import com.garfiec.librechat.core.model.ArtifactShortcut
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Verifies the entity ↔ core-model mapping on both the write (save) and read (get/observeAll) sides. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactShortcutRepositoryImplTest {

    private val model = ArtifactShortcut(
        id = "11111111-2222-3333-4444-555555555555",
        label = "My Chart",
        emoji = "📊",
        identifier = "chart-1",
        type = "application/vnd.mermaid",
        title = "Sales Chart",
        language = null,
        content = "graph TD; A-->B",
        version = 2,
        createdAt = 1_700_000_000_000L,
    )

    @Test
    fun save_mapsModelToEntity() = runTest {
        val dao = mockk<ArtifactShortcutDao>(relaxed = true)
        val captured = slot<ArtifactShortcutEntity>()
        coEvery { dao.upsert(capture(captured)) } returns Unit
        val repo = ArtifactShortcutRepositoryImpl(dao, StandardTestDispatcher(testScheduler))

        repo.save(model)
        testScheduler.advanceUntilIdle()

        val entity = captured.captured
        assertThat(entity.id).isEqualTo(model.id)
        assertThat(entity.shortcutLabel).isEqualTo("My Chart")
        assertThat(entity.emoji).isEqualTo("📊")
        assertThat(entity.identifier).isEqualTo("chart-1")
        assertThat(entity.type).isEqualTo("application/vnd.mermaid")
        assertThat(entity.language).isNull()
        assertThat(entity.version).isEqualTo(2)
        assertThat(entity.createdAt).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun get_mapsEntityToModel() = runTest {
        val dao = mockk<ArtifactShortcutDao>()
        coEvery { dao.getById(model.id) } returns model.toEntity()
        val repo = ArtifactShortcutRepositoryImpl(dao, StandardTestDispatcher(testScheduler))

        val result = repo.get(model.id)
        testScheduler.advanceUntilIdle()

        assertThat(result).isEqualTo(model)
    }

    @Test
    fun observeAll_mapsEachEntity() = runTest {
        val dao = mockk<ArtifactShortcutDao>()
        every { dao.observeAll() } returns flowOf(listOf(model.toEntity()))
        val repo = ArtifactShortcutRepositoryImpl(dao, StandardTestDispatcher(testScheduler))

        val result = repo.observeAll().first()

        assertThat(result).containsExactly(model)
    }
}

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
