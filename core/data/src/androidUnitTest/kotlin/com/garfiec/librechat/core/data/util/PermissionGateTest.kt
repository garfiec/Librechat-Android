package com.garfiec.librechat.core.data.util

import com.garfiec.librechat.core.data.repository.RoleRepository
import com.garfiec.librechat.core.model.permissions.UserRolePermissions
import com.garfiec.librechat.core.model.request.UpdateRoleSkillsRequest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionGateTest {

    private fun fakeRepository(flow: StateFlow<UserRolePermissions?>): RoleRepository =
        object : RoleRepository {
            override val userPermissions: StateFlow<UserRolePermissions?> = flow
            override suspend fun fetchUserRole() = error("not needed in gate tests")
            override suspend fun clear() { /* no-op */ }
            override suspend fun getRole(roleName: String) = error("not needed in gate tests")
            override suspend fun updateRoleSkills(roleName: String, request: UpdateRoleSkillsRequest) =
                error("not needed in gate tests")
        }

    private val adminRole = UserRolePermissions(
        name = "ADMIN",
        permissions = mapOf(
            "PROMPTS" to mapOf("USE" to false, "CREATE" to true),
            "AGENTS" to mapOf("USE" to false),
        ),
    )

    @Test
    fun `awaitRole returns null when role never loads within 5s`() = runTest {
        val flow = MutableStateFlow<UserRolePermissions?>(null)
        val gate = PermissionGate(fakeRepository(flow))

        val deferred = async { gate.awaitRole() }
        advanceTimeBy(PermissionGate.ROLE_LOAD_TIMEOUT_MS + 100)

        assertThat(deferred.await()).isNull()
    }

    @Test
    fun `awaitRole returns loaded role immediately after emission`() = runTest {
        val flow = MutableStateFlow<UserRolePermissions?>(adminRole)
        val gate = PermissionGate(fakeRepository(flow))

        val role = gate.awaitRole()
        assertThat(role).isEqualTo(adminRole)
    }
}
