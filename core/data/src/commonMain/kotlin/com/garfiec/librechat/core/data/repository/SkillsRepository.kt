package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.model.Skill
import com.garfiec.librechat.core.model.SkillFile
import com.garfiec.librechat.core.model.request.CreateSkillRequest
import com.garfiec.librechat.core.model.request.UpdateSkillRequest
import com.garfiec.librechat.core.model.response.DeleteSkillFileResponse
import com.garfiec.librechat.core.model.response.DeleteSkillResponse
import com.garfiec.librechat.core.model.response.SkillListResponse

/** CRUD access to the Skills catalog. */
interface SkillsRepository {
    suspend fun listSkills(
        limit: Int = 100,
        search: String? = null,
        category: String? = null,
        cursor: String? = null,
    ): Result<SkillListResponse>

    suspend fun getSkill(id: String): Result<Skill>

    suspend fun createSkill(request: CreateSkillRequest): Result<Skill>

    /**
     * PATCHes a skill with optimistic concurrency. On a 409
     * `skill_version_conflict` the result is [SkillUpdateResult.Conflict] carrying
     * the authoritative current skill (refetched) so the caller can rebase the
     * editor onto it rather than clobbering — never silently overwrite.
     */
    suspend fun updateSkill(id: String, request: UpdateSkillRequest): SkillUpdateResult

    suspend fun deleteSkill(id: String): Result<DeleteSkillResponse>

    /** Per-user skill active/inactive overrides (`GET .../skills/active`). */
    suspend fun getSkillStates(): Result<Map<String, Boolean>>

    /** Persists active/inactive overrides; returns the pruned map. */
    suspend fun updateSkillStates(states: Map<String, Boolean>): Result<Map<String, Boolean>>

    // --- Skill files (flat) ---

    suspend fun listSkillFiles(skillId: String): Result<List<SkillFile>>

    suspend fun uploadSkillFile(
        skillId: String,
        relativePath: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
    ): Result<SkillFile>

    suspend fun deleteSkillFile(skillId: String, relativePath: String): Result<DeleteSkillFileResponse>

    /** Imports a skill from a .md/.zip/.skill upload. Surfaces server `issues`
     *  validation messages in [Result.Error.message] like [createSkill]. */
    suspend fun importSkill(bytes: ByteArray, filename: String, mimeType: String): Result<Skill>
}

/** Outcome of a skill PATCH that distinguishes the version-conflict case. */
sealed interface SkillUpdateResult {
    data class Success(val skill: Skill) : SkillUpdateResult

    /** 409: the editor's [UpdateSkillRequest.expectedVersion] was stale. [current]
     *  is the authoritative server state to rebase onto. */
    data class Conflict(val current: Skill) : SkillUpdateResult

    data class Error(val message: String) : SkillUpdateResult
}
