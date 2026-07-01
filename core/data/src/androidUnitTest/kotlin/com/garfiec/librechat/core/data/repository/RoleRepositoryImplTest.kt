package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.identity.AccountId
import com.garfiec.librechat.core.common.identity.InMemoryActiveAccountProvider
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.RoleCacheDataStore
import com.garfiec.librechat.core.model.User
import com.garfiec.librechat.core.model.permissions.Permission
import com.garfiec.librechat.core.model.permissions.PermissionType
import com.garfiec.librechat.core.model.permissions.UserRolePermissions
import com.garfiec.librechat.core.network.api.RolesApi
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RoleRepositoryImplTest {

    private val rolesApi = mockk<RolesApi>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val cacheDataStore = mockk<RoleCacheDataStore>(relaxed = true)

    private val adminRole = UserRolePermissions(
        name = "ADMIN",
        permissions = mapOf(
            "PROMPTS" to mapOf("USE" to false),
            "BOOKMARKS" to mapOf("USE" to true),
        ),
    )

    private val userRole = UserRolePermissions(
        name = "USER",
        permissions = mapOf("PROMPTS" to mapOf("USE" to true)),
    )

    // Resolved so the init prime-collector fires cacheDataStore.load() once (as the old one-shot did).
    private val activeAccountProvider = InMemoryActiveAccountProvider().apply { set(AccountId("srv:test")) }

    private fun TestScope.newRepo(): RoleRepositoryImpl {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        return RoleRepositoryImpl(
            rolesApi = rolesApi,
            userRepository = userRepository,
            cacheDataStore = cacheDataStore,
            activeAccountProvider = activeAccountProvider,
            applicationScope = scope,
        )
    }

    @Test
    fun `fetchUserRole calls rolesApi with user's role name`() = runTest {
        coEvery { cacheDataStore.load() } returns null
        coEvery { cacheDataStore.save(any()) } just Runs
        coEvery { userRepository.getUser() } returns Result.Success(User(email = "a@b.c", role = "ADMIN"))
        coEvery { rolesApi.getRole("ADMIN") } returns adminRole

        val repo = newRepo()
        advanceUntilIdle()
        val result = repo.fetchUserRole()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data).isEqualTo(adminRole)
        coVerify(exactly = 1) { rolesApi.getRole("ADMIN") }
    }

    @Test
    fun `fetchUserRole uses role name not a hardcoded string`() = runTest {
        coEvery { cacheDataStore.load() } returns null
        coEvery { cacheDataStore.save(any()) } just Runs
        coEvery { userRepository.getUser() } returns Result.Success(User(email = "a@b.c", role = "USER"))
        coEvery { rolesApi.getRole("USER") } returns userRole

        val repo = newRepo()
        advanceUntilIdle()
        repo.fetchUserRole()

        coVerify(exactly = 1) { rolesApi.getRole("USER") }
    }

    @Test
    fun `fetchUserRole populates StateFlow and saves to cache on success`() = runTest {
        coEvery { cacheDataStore.load() } returns null
        coEvery { cacheDataStore.save(adminRole) } just Runs
        coEvery { userRepository.getUser() } returns Result.Success(User(email = "a@b.c", role = "ADMIN"))
        coEvery { rolesApi.getRole("ADMIN") } returns adminRole

        val repo = newRepo()
        advanceUntilIdle()
        repo.fetchUserRole()

        assertThat(repo.userPermissions.value).isEqualTo(adminRole)
        coVerify(exactly = 1) { cacheDataStore.save(adminRole) }
    }

    @Test
    fun `fetchUserRole returns cached value on network failure when cache populated`() = runTest {
        // Prime the cache so StateFlow has a value before the failing fetch.
        coEvery { cacheDataStore.load() } returns adminRole
        coEvery { userRepository.getUser() } returns Result.Success(User(email = "a@b.c", role = "ADMIN"))
        coEvery { rolesApi.getRole("ADMIN") } throws IOException("offline")

        val repo = newRepo()
        advanceUntilIdle()   // let the cache-prime launch complete
        val result = repo.fetchUserRole()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data).isEqualTo(adminRole)
        assertThat(repo.userPermissions.value).isEqualTo(adminRole)
    }

    @Test
    fun `fetchUserRole returns Error when network fails and no cached value`() = runTest {
        coEvery { cacheDataStore.load() } returns null
        coEvery { userRepository.getUser() } returns Result.Success(User(email = "a@b.c", role = "ADMIN"))
        coEvery { rolesApi.getRole("ADMIN") } throws IOException("offline")

        val repo = newRepo()
        advanceUntilIdle()
        val result = repo.fetchUserRole()

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(repo.userPermissions.value).isNull()
    }

    @Test
    fun `fetchUserRole returns Error when userRepository fails`() = runTest {
        coEvery { cacheDataStore.load() } returns null
        coEvery { userRepository.getUser() } returns Result.Error(RuntimeException("no user"), "no user")

        val repo = newRepo()
        advanceUntilIdle()
        val result = repo.fetchUserRole()

        assertThat(result).isInstanceOf(Result.Error::class.java)
        coVerify(exactly = 0) { rolesApi.getRole(any()) }
    }

    @Test
    fun `clear wipes in-memory StateFlow and DataStore`() = runTest {
        coEvery { cacheDataStore.load() } returns adminRole
        coEvery { cacheDataStore.clear() } just Runs

        val repo = newRepo()
        advanceUntilIdle()
        assertThat(repo.userPermissions.value).isEqualTo(adminRole)

        repo.clear()

        assertThat(repo.userPermissions.value).isNull()
        coVerify(exactly = 1) { cacheDataStore.clear() }
    }

    @Test
    fun `custom role 404-style error keeps prior cached value intact`() = runTest {
        // Cache holds the pre-change custom role permissions.
        val customRole = UserRolePermissions(
            name = "CUSTOM_ROLE",
            permissions = mapOf(PermissionType.AGENTS.serverKey to mapOf(Permission.USE.serverKey to true)),
        )
        coEvery { cacheDataStore.load() } returns customRole
        coEvery { userRepository.getUser() } returns Result.Success(User(email = "a@b.c", role = "CUSTOM_ROLE"))
        coEvery { rolesApi.getRole("CUSTOM_ROLE") } throws IOException("404 Not Found")

        val repo = newRepo()
        advanceUntilIdle()
        val result = repo.fetchUserRole()

        // Repository falls back to cached value, StateFlow not nulled out.
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(repo.userPermissions.value).isEqualTo(customRole)
    }

    @Test
    fun `cache prime populates StateFlow from DataStore at construction`() = runTest {
        coEvery { cacheDataStore.load() } returns adminRole
        val repo = newRepo()
        advanceUntilIdle()   // StandardTestDispatcher needs this to run the init-block launch

        assertThat(repo.userPermissions.value).isEqualTo(adminRole)
    }
}
