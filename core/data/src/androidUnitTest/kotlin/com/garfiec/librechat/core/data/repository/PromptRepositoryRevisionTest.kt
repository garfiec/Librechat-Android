package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.model.Prompt
import com.garfiec.librechat.core.model.PromptGroup
import com.garfiec.librechat.core.model.request.AddPromptToGroupRequest
import com.garfiec.librechat.core.model.request.CreatePromptData
import com.garfiec.librechat.core.model.request.CreatePromptGroupData
import com.garfiec.librechat.core.model.request.CreatePromptRequest
import com.garfiec.librechat.core.model.request.UpdatePromptGroupRequest
import com.garfiec.librechat.core.model.request.UpdatePromptTagRequest
import com.garfiec.librechat.core.network.api.PromptsApi
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The contract under test is that `revision` follows the *server's* answer: an accepted mutation
 * announces itself, a rejected one stays silent. A bump on a failed call would make the prompts
 * library and the `/` picker refetch and repaint unchanged data as though the user's edit landed.
 */
class PromptRepositoryRevisionTest {

    private val promptsApi = mockk<PromptsApi>(relaxed = true)
    private val repository = PromptRepositoryImpl(promptsApi)

    private val group = PromptGroup(id = "g-1", name = "Group", author = "a", authorName = "A")
    private val prompt = Prompt(id = "p-1", groupId = "g-1", author = "a", prompt = "body", type = "text")

    private val createRequest = CreatePromptRequest(
        prompt = CreatePromptData(prompt = "body", type = "text"),
        group = CreatePromptGroupData(name = "Group"),
    )

    @Test
    fun everyAcceptedMutationBumpsTheRevision() = runTest {
        coEvery { promptsApi.createPrompt(any()) } returns group
        coEvery { promptsApi.updatePromptGroup(any(), any()) } returns group
        coEvery { promptsApi.deletePromptGroup(any()) } returns Unit
        coEvery { promptsApi.addPromptToGroup(any(), any()) } returns prompt
        coEvery { promptsApi.updatePromptProductionTag(any(), any()) } returns prompt

        val start = repository.revision.value
        repository.create(createRequest)
        repository.update("g-1", UpdatePromptGroupRequest(name = "Group"))
        repository.addPromptToGroup("g-1", AddPromptToGroupRequest(prompt = "new body", type = "text"))
        repository.updatePromptProductionTag("p-1", UpdatePromptTagRequest(productionPromptId = "p-1"))
        repository.delete("g-1")

        // One per mutation: a missed bump is a surface left serving pre-save values with nothing
        // reporting it, which is the whole bug class this counter exists to close.
        assertThat(repository.revision.value).isEqualTo(start + 5)
    }

    @Test
    fun readsDoNotBumpTheRevision() = runTest {
        coEvery { promptsApi.getAllPromptGroups() } returns listOf(group)
        coEvery { promptsApi.getPromptGroup(any()) } returns group
        coEvery { promptsApi.getPromptsByGroupId(any()) } returns listOf(prompt)

        val start = repository.revision.value
        repository.getAllGroups()
        repository.getGroup("g-1")
        repository.getPromptsByGroupId("g-1")

        // Each consumer reloads on a bump, so a read that bumps is an infinite refetch loop.
        assertThat(repository.revision.value).isEqualTo(start)
    }

    @Test
    fun aRejectedMutationStaysSilent() = runTest {
        coEvery { promptsApi.createPrompt(any()) } throws RuntimeException("500")
        coEvery { promptsApi.deletePromptGroup(any()) } throws RuntimeException("403")
        coEvery { promptsApi.addPromptToGroup(any(), any()) } throws RuntimeException("offline")

        val start = repository.revision.value
        repository.create(createRequest)
        repository.delete("g-1")
        repository.addPromptToGroup("g-1", AddPromptToGroupRequest(prompt = "new body", type = "text"))

        // The bump sits after the API call inside `safeApiCall`, so a throw skips it. Announcing a
        // failed delete would make the library refetch and repaint the prompt the user was told
        // was gone.
        assertThat(repository.revision.value).isEqualTo(start)
    }
}
