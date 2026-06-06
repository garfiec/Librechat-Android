package com.garfiec.librechat.core.data.repository

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.data.datastore.RoleCacheDataStore
import com.garfiec.librechat.core.model.permissions.UserRolePermissions
import com.garfiec.librechat.core.model.request.UpdateRoleSkillsRequest
import com.garfiec.librechat.core.network.api.RolesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RoleRepositoryImpl(
    private val rolesApi: RolesApi,
    private val userRepository: UserRepository,
    private val cacheDataStore: RoleCacheDataStore,
    applicationScope: CoroutineScope,
) : RoleRepository {

    private val _userPermissions = MutableStateFlow<UserRolePermissions?>(null)
    override val userPermissions: StateFlow<UserRolePermissions?> = _userPermissions.asStateFlow()

    init {
        // Async DataStore prime — never runBlocking. The StateFlow briefly emits null
        // then populates from disk; PermissionGate's permissive default covers the flash.
        applicationScope.launch {
            cacheDataStore.load()?.let { _userPermissions.value = it }
        }
    }

    override suspend fun fetchUserRole(): Result<UserRolePermissions> {
        val userResult = userRepository.getUser()
        val user = when (userResult) {
            is Result.Success -> userResult.data
            is Result.Error -> return Result.Error(userResult.exception, "No current user")
            is Result.Loading -> return Result.Error(message = "User load still in progress")
        }

        return try {
            val role = rolesApi.getRole(user.role)
            _userPermissions.value = role
            cacheDataStore.save(role)
            Result.Success(role)
        } catch (e: Exception) {
            val cached = _userPermissions.value
            if (cached != null) {
                Logger.w(e) { "fetchUserRole failed, keeping cached role '${cached.name}'" }
                Result.Success(cached)
            } else {
                Logger.w(e) { "fetchUserRole failed with no cached value" }
                Result.Error(e, e.message ?: "Failed to load role permissions")
            }
        }
    }

    override suspend fun clear() {
        _userPermissions.value = null
        cacheDataStore.clear()
    }

    override suspend fun getRole(roleName: String): Result<UserRolePermissions> =
        safeApiCall { rolesApi.getRole(roleName) }

    override suspend fun updateRoleSkills(
        roleName: String,
        request: UpdateRoleSkillsRequest,
    ): Result<UserRolePermissions> =
        safeApiCall { rolesApi.updateRoleSkills(roleName, request) }
}
