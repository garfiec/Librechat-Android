package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.permissions.UserRolePermissions
import com.garfiec.librechat.core.model.request.UpdateRoleSkillsRequest
import kotlinx.coroutines.flow.StateFlow

interface RoleRepository {
    /**
     * Latest permissions for the authenticated user's role, or null if not yet loaded.
     * Emits null briefly at startup before the DataStore cache prime completes.
     */
    val userPermissions: StateFlow<UserRolePermissions?>

    /**
     * Fetches `/api/roles/{currentUser.role}`, updates the StateFlow + DataStore on success.
     * On network failure with a cached value present, returns Result.Success(cached) so
     * gates can keep working offline. On network failure with no cache, returns Result.Error.
     */
    suspend fun fetchUserRole(): Result<UserRolePermissions>

    /** Wipes in-memory StateFlow and DataStore cache. Called on logout. */
    suspend fun clear()

    /** Fetches an ARBITRARY role's permissions (admin role-skills config), not
     *  the current user's. Does not touch the userPermissions StateFlow/cache. */
    suspend fun getRole(roleName: String): Result<UserRolePermissions>

    /** Admin: sets a role's SKILLS permission booleans via PUT /roles/:role/skills;
     *  returns the updated role. Server enforces admin (manageRoles). */
    suspend fun updateRoleSkills(roleName: String, request: UpdateRoleSkillsRequest): Result<UserRolePermissions>
}
