package com.garfiec.librechat.core.data.repository

import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.common.identity.AccountState
import com.garfiec.librechat.core.common.identity.ActiveAccountProvider
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

class RoleRepositoryImpl(
    private val rolesApi: RolesApi,
    private val userRepository: UserRepository,
    private val cacheDataStore: RoleCacheDataStore,
    private val activeAccountProvider: ActiveAccountProvider,
    applicationScope: CoroutineScope,
) : RoleRepository {

    private val _userPermissions = MutableStateFlow<UserRolePermissions?>(null)
    override val userPermissions: StateFlow<UserRolePermissions?> = _userPermissions.asStateFlow()

    init {
        // Prime the cached role from disk once the account resolves — the cache is account-scoped, so a
        // one-shot read during the cold-start Warming window (before the account is known) would read
        // null and never re-prime. Re-primes per account; PermissionGate's permissive default covers the
        // gap until this lands or the live fetch repopulates.
        applicationScope.launch {
            activeAccountProvider.state
                .mapNotNull { (it as? AccountState.Resolved)?.id }
                .distinctUntilChanged()
                .collect { cacheDataStore.load()?.let { role -> _userPermissions.value = role } }
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
