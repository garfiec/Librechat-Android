package com.garfiec.librechat.feature.skills.viewmodel

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.repository.PermissionsRepository
import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.model.Principal
import com.garfiec.librechat.core.model.ResourcePermissions
import com.garfiec.librechat.core.model.ResourceType
import com.garfiec.librechat.core.model.permissions.UserRolePermissions
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkillAclViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val permissionsRepository = mockk<PermissionsRepository>(relaxed = true)
    private val roleRepository = mockk<RoleRepository>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { roleRepository.userPermissions } returns MutableStateFlow(null)
    }

    @After
    fun teardown() = Dispatchers.resetMain()

    private fun vm() = SkillAclViewModel(permissionsRepository, roleRepository)

    private fun rolePerms(vararg granted: String) = UserRolePermissions(
        name = "USER",
        permissions = mapOf("SKILLS" to granted.associateWith { true }),
    )

    // --- Fail-closed share gates ---

    @Test
    fun `canShare and canSharePublic are false for null role (fail-closed)`() = runTest(testDispatcher) {
        val viewModel = vm()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.canShare).isFalse()
        assertThat(viewModel.uiState.value.canSharePublic).isFalse()
    }

    @Test
    fun `canShare true with SKILLS SHARE but canSharePublic still gated`() =
        runTest(testDispatcher) {
            every { roleRepository.userPermissions } returns MutableStateFlow(rolePerms("SHARE"))
            val viewModel = vm()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.canShare).isTrue()
            // SHARE granted but not SHARE_PUBLIC → public toggle stays gated.
            assertThat(viewModel.uiState.value.canSharePublic).isFalse()
        }

    @Test
    fun `canSharePublic true with SHARE_PUBLIC granted`() = runTest(testDispatcher) {
        every { roleRepository.userPermissions } returns MutableStateFlow(rolePerms("SHARE", "SHARE_PUBLIC"))
        val viewModel = vm()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.canSharePublic).isTrue()
    }

    // --- grant / revoke / setPublic apply flow ---

    @Test
    fun `grant sends the principal with its access role and updates state`() = runTest(testDispatcher) {
        coEvery { permissionsRepository.getResourceRoles(ResourceType.SKILL) } returns Result.Success(emptyList())
        coEvery { permissionsRepository.getResourcePermissions(ResourceType.SKILL, "sk-1") } returns
            Result.Success(ResourcePermissions())
        val updatedPerms = ResourcePermissions(
            principals = listOf(Principal(type = "user", id = "u1", accessRoleId = "role_editor")),
        )
        coEvery { permissionsRepository.updateResourcePermissions(ResourceType.SKILL, "sk-1", any()) } returns
            Result.Success(updatedPerms)

        val viewModel = vm()
        viewModel.load("sk-1")
        advanceUntilIdle()
        viewModel.grant(Principal(type = "user", id = "u1"), "role_editor")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.principals.single().accessRoleId).isEqualTo("role_editor")
        assertThat(viewModel.uiState.value.showGrantDialog).isFalse()
        coVerify { permissionsRepository.updateResourcePermissions(ResourceType.SKILL, "sk-1", any()) }
    }

    @Test
    fun `setPublic propagates the public flag through applyUpdate`() = runTest(testDispatcher) {
        coEvery { permissionsRepository.getResourceRoles(ResourceType.SKILL) } returns Result.Success(emptyList())
        coEvery { permissionsRepository.getResourcePermissions(ResourceType.SKILL, "sk-1") } returns
            Result.Success(ResourcePermissions())
        coEvery { permissionsRepository.updateResourcePermissions(ResourceType.SKILL, "sk-1", any()) } returns
            Result.Success(ResourcePermissions(public = true, publicAccessRoleId = "role_viewer"))

        val viewModel = vm()
        viewModel.load("sk-1")
        advanceUntilIdle()
        viewModel.setPublic(enabled = true, accessRoleId = "role_viewer")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isPublic).isTrue()
        assertThat(viewModel.uiState.value.publicAccessRoleId).isEqualTo("role_viewer")
    }

    @Test
    fun `applyUpdate surfaces error on failure`() = runTest(testDispatcher) {
        coEvery { permissionsRepository.getResourceRoles(ResourceType.SKILL) } returns Result.Success(emptyList())
        coEvery { permissionsRepository.getResourcePermissions(ResourceType.SKILL, "sk-1") } returns
            Result.Success(ResourcePermissions())
        coEvery { permissionsRepository.updateResourcePermissions(ResourceType.SKILL, "sk-1", any()) } returns
            Result.Error(message = "denied")

        val viewModel = vm()
        viewModel.load("sk-1")
        advanceUntilIdle()
        viewModel.revoke(Principal(type = "user", id = "u1"))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo("denied")
    }

    @Test
    fun `mutation before load is a no-op (no skillId)`() = runTest(testDispatcher) {
        val viewModel = vm()
        advanceUntilIdle()
        viewModel.grant(Principal(type = "user", id = "u1"), "role_editor")
        advanceUntilIdle()
        coVerify(exactly = 0) {
            permissionsRepository.updateResourcePermissions(any(), any(), any())
        }
    }
}
