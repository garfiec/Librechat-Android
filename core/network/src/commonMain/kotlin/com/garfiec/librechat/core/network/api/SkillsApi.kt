package com.garfiec.librechat.core.network.api

import com.garfiec.librechat.core.model.Skill
import com.garfiec.librechat.core.model.SkillFile
import com.garfiec.librechat.core.model.request.CreateSkillRequest
import com.garfiec.librechat.core.model.request.UpdateSkillRequest
import com.garfiec.librechat.core.model.request.UpdateSkillStatesRequest
import com.garfiec.librechat.core.model.response.DeleteSkillFileResponse
import com.garfiec.librechat.core.model.response.DeleteSkillResponse
import com.garfiec.librechat.core.model.response.SkillFileContentResponse
import com.garfiec.librechat.core.model.response.SkillFileListResponse
import com.garfiec.librechat.core.model.response.SkillListResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.path

/**
 * Read-only access to the Skills catalog (`GET /api/skills`). Used by the
 * agent-editor skills selector to populate the picker and resolve saved skill
 * `_id`s to display names. The router is gated server-side behind
 * `PermissionTypes.SKILLS` / `Permissions.USE` (403 when denied).
 */
class SkillsApi(
    private val client: HttpClient,
) {

    /**
     * Lists available skill summaries. All filters are optional; web calls bare
     * `/api/skills?limit=100`.
     *
     * @param limit max results (upstream default 100)
     * @param search free-text filter on skill name
     * @param category category filter
     * @param cursor pagination cursor (`after` from a prior response)
     */
    suspend fun listSkills(
        limit: Int = 100,
        search: String? = null,
        category: String? = null,
        cursor: String? = null,
    ): SkillListResponse =
        client.get {
            url { path("api/skills") }
            parameter("limit", limit)
            if (search != null) parameter("search", search)
            if (category != null) parameter("category", category)
            if (cursor != null) parameter("cursor", cursor)
        }.body()

    /** Full skill incl. body + frontmatter (`GET /api/skills/:id`). ACL VIEW. */
    suspend fun getSkill(id: String): Skill =
        client.get {
            url { path("api/skills/$id") }
        }.body()

    /** Creates a skill (`POST /api/skills`). SKILLS.USE+CREATE. No arg-wrap. */
    suspend fun createSkill(request: CreateSkillRequest): Skill =
        client.post {
            url { path("api/skills") }
            setBody(request)
        }.body()

    /**
     * Updates a skill (`PATCH /api/skills/:id`). [UpdateSkillRequest.expectedVersion]
     * is mandatory; a stale value yields a 409 (the client converts non-2xx to
     * ApiException, so the repository detects statusCode==409 and refetches the
     * authoritative skill rather than clobbering). SKILLS.USE+CREATE, ACL EDIT.
     */
    suspend fun updateSkill(id: String, request: UpdateSkillRequest): Skill =
        client.patch {
            url { path("api/skills/$id") }
            setBody(request)
        }.body()

    /** Deletes a skill (`DELETE /api/skills/:id`). SKILLS.USE+CREATE, ACL DELETE. */
    suspend fun deleteSkill(id: String): DeleteSkillResponse =
        client.delete {
            url { path("api/skills/$id") }
        }.body()

    /**
     * Per-user skill active/inactive overrides
     * (`GET /api/user/settings/skills/active`) → `Record<skillId, boolean>`.
     * Behind `requireJwtAuth` only (no SKILLS perm gate at the route level).
     */
    suspend fun getSkillStates(): Map<String, Boolean> =
        client.get {
            url { path("api/user/settings/skills/active") }
        }.body()

    /**
     * Persists active/inactive overrides (`POST /api/user/settings/skills/active`,
     * body `{ skillStates }`) → the pruned `Record<skillId, boolean>`.
     */
    suspend fun updateSkillStates(states: Map<String, Boolean>): Map<String, Boolean> =
        client.post {
            url { path("api/user/settings/skills/active") }
            setBody(UpdateSkillStatesRequest(states))
        }.body()

    // --- Skill files (flat; NOT the file-tree, which is not implemented upstream) ---

    /** `GET /api/skills/:id/files` → `{ files }`. ACL VIEW. */
    suspend fun listSkillFiles(skillId: String): SkillFileListResponse =
        client.get {
            url { path("api/skills/$skillId/files") }
        }.body()

    /**
     * `POST /api/skills/:id/files` (multipart `file` + form `relativePath`) →
     * the created [SkillFile]. ACL EDIT. The server rejects `SKILL.md`, absolute
     * paths, and traversal with 400.
     */
    suspend fun uploadSkillFile(
        skillId: String,
        relativePath: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
    ): SkillFile =
        client.post {
            url { path("api/skills/$skillId/files") }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("file", bytes, Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"${filename.replace("\"", "\\\"")}\"")
                            append(HttpHeaders.ContentType, mimeType)
                        })
                        append("relativePath", relativePath)
                    },
                ),
            )
        }.body()

    /**
     * `GET /api/skills/:id/files/:relativePath` (JSON mode) → content metadata;
     * [SkillFileContentResponse.content] is omitted for binary files. The
     * relativePath may contain `/`, encoded as a single path part. ACL VIEW.
     */
    suspend fun getSkillFileContent(skillId: String, relativePath: String): SkillFileContentResponse =
        client.get {
            url { skillFilePath(skillId, relativePath) }
        }.body()

    /** `DELETE /api/skills/:id/files/:relativePath`. ACL EDIT. */
    suspend fun deleteSkillFile(skillId: String, relativePath: String): DeleteSkillFileResponse =
        client.delete {
            url { skillFilePath(skillId, relativePath) }
        }.body()

    /**
     * `POST /api/skills/import` (multipart `file`: `.md`/`.zip`/`.skill`) →
     * the created [Skill]. SKILLS.USE+CREATE.
     */
    suspend fun importSkill(
        bytes: ByteArray,
        filename: String,
        mimeType: String,
    ): Skill =
        client.post {
            url { path("api/skills/import") }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("file", bytes, Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"${filename.replace("\"", "\\\"")}\"")
                            append(HttpHeaders.ContentType, mimeType)
                        })
                    },
                ),
            )
        }.body()

    private companion object {
        /**
         * Builds `/api/skills/:id/files/:relativePath` where [relativePath] is a
         * SINGLE path param: its `/` is encoded to `%2F` (encodeSlash=true), the
         * structural separators are not. This matches the web client's
         * encodeURIComponent. Appending the structural segments first
         * (encodeSlash=false) keeps them as real separators.
         */
        fun URLBuilder.skillFilePath(skillId: String, relativePath: String) {
            appendPathSegments("api", "skills", skillId, "files")
            appendPathSegments(listOf(relativePath), encodeSlash = true)
        }
    }
}
