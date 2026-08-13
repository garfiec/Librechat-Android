package com.garfiec.librechat.core.data.repository

import com.garfiec.librechat.core.common.result.ApiException
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.common.result.onApiDispatcher
import com.garfiec.librechat.core.common.result.safeApiCall
import com.garfiec.librechat.core.model.Skill
import com.garfiec.librechat.core.model.SkillFile
import com.garfiec.librechat.core.model.request.CreateSkillRequest
import com.garfiec.librechat.core.model.request.UpdateSkillRequest
import com.garfiec.librechat.core.model.response.DeleteSkillFileResponse
import com.garfiec.librechat.core.model.response.DeleteSkillResponse
import com.garfiec.librechat.core.model.response.SkillConflictResponse
import com.garfiec.librechat.core.model.response.SkillListResponse
import com.garfiec.librechat.core.model.response.SkillValidationErrorResponse
import com.garfiec.librechat.core.network.api.SkillsApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Create, update, upload and import map their own failures — the server's `issues` array carries
 * validation detail (reserved name prefix, path traversal, a name conflict) that a generic message
 * would throw away — so they hand-roll the try/catch instead of using `safeApiCall`, and take its
 * dispatcher hop explicitly via `onApiDispatcher` (#326). The rest of the class uses `safeApiCall`.
 */
class SkillsRepositoryImpl(
    private val skillsApi: SkillsApi,
    private val json: Json,
) : SkillsRepository {

    override suspend fun listSkills(
        limit: Int,
        search: String?,
        category: String?,
        cursor: String?,
    ): Result<SkillListResponse> =
        safeApiCall { skillsApi.listSkills(limit, search, category, cursor) }

    override suspend fun getSkill(id: String): Result<Skill> =
        safeApiCall { skillsApi.getSkill(id) }

    override suspend fun createSkill(request: CreateSkillRequest): Result<Skill> {
        return try {
            Result.Success(onApiDispatcher { skillsApi.createSkill(request) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // Surface server-side validation `issues` (e.g. reserved name prefix/
            // word) that the client's kebab/length checks don't catch.
            Result.Error(e, validationMessage(e) ?: e.message)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to create skill.")
        }
    }

    override suspend fun updateSkill(id: String, request: UpdateSkillRequest): SkillUpdateResult {
        return try {
            SkillUpdateResult.Success(onApiDispatcher { skillsApi.updateSkill(id, request) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            when {
                e.statusCode == HTTP_CONFLICT -> parseConflict(e)
                else -> SkillUpdateResult.Error(validationMessage(e) ?: e.message)
            }
        } catch (e: Exception) {
            SkillUpdateResult.Error(e.message ?: "Failed to update skill.")
        }
    }

    override suspend fun deleteSkill(id: String): Result<DeleteSkillResponse> =
        safeApiCall { skillsApi.deleteSkill(id) }

    override suspend fun getSkillStates(): Result<Map<String, Boolean>> =
        safeApiCall { skillsApi.getSkillStates() }

    override suspend fun updateSkillStates(states: Map<String, Boolean>): Result<Map<String, Boolean>> =
        safeApiCall { skillsApi.updateSkillStates(states) }

    override suspend fun listSkillFiles(skillId: String): Result<List<SkillFile>> =
        safeApiCall { skillsApi.listSkillFiles(skillId).files }

    override suspend fun uploadSkillFile(
        skillId: String,
        relativePath: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
    ): Result<SkillFile> {
        return try {
            Result.Success(
                onApiDispatcher { skillsApi.uploadSkillFile(skillId, relativePath, bytes, filename, mimeType) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // Surface the server's path/validation messages (reserved name,
            // traversal, etc.) instead of a generic failure.
            Result.Error(e, validationMessage(e) ?: e.message)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to upload file.")
        }
    }

    override suspend fun deleteSkillFile(skillId: String, relativePath: String): Result<DeleteSkillFileResponse> =
        safeApiCall { skillsApi.deleteSkillFile(skillId, relativePath) }

    override suspend fun importSkill(bytes: ByteArray, filename: String, mimeType: String): Result<Skill> {
        return try {
            Result.Success(onApiDispatcher { skillsApi.importSkill(bytes, filename, mimeType) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            Result.Error(e, validationMessage(e) ?: e.message)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to import skill.")
        }
    }

    /**
     * Decodes the authoritative [Skill] from the 409 `skill_version_conflict`
     * body so the editor can rebase onto the server's current version without a
     * second round-trip. Falls back to a generic conflict if the body is absent
     * or unparseable (then the caller can't bump the version and the user retries).
     */
    private fun parseConflict(e: ApiException): SkillUpdateResult {
        val current = e.body?.let {
            runCatching { json.decodeFromString(SkillConflictResponse.serializer(), it).current }.getOrNull()
        }
        return if (current != null) {
            SkillUpdateResult.Conflict(current)
        } else {
            SkillUpdateResult.Error("This skill was changed on the server. Reload and try again.")
        }
    }

    /** Joins a 400 `{ error, issues }` body's issue messages into one line, or null. */
    private fun validationMessage(e: ApiException): String? {
        val body = e.body ?: return null
        val parsed = runCatching {
            json.decodeFromString(SkillValidationErrorResponse.serializer(), body)
        }.getOrNull() ?: return null
        val issueText = parsed.issues.mapNotNull { it.message.ifBlank { null } }
        return issueText.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private companion object {
        const val HTTP_CONFLICT = 409
    }
}
