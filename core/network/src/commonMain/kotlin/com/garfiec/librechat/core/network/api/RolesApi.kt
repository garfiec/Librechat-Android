package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.permissions.UserRolePermissions
import com.garfiec.librechat.core.model.request.UpdateRoleSkillsRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.path

class RolesApi(
    private val client: HttpClient,
) {
    suspend fun getRole(roleName: String): UserRolePermissions =
        client.get {
            url { path("api/roles/$roleName") }
        }.body()

    /**
     * Sets a role's SKILLS permission booleans
     * (`PUT /api/roles/:roleName/skills`). Admin-only server-side (manageRoles);
     * returns the updated role. No `arg`-wrap.
     */
    suspend fun updateRoleSkills(roleName: String, request: UpdateRoleSkillsRequest): UserRolePermissions =
        client.put {
            url { path("api/roles/$roleName/skills") }
            setBody(request)
        }.body()
}
