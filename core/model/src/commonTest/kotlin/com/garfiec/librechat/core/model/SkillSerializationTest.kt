package com.garfiec.librechat.core.model

import com.garfiec.librechat.core.model.request.CreateSkillRequest
import com.garfiec.librechat.core.model.request.UpdateSkillRequest
import com.garfiec.librechat.core.model.response.SkillConflictResponse
import com.garfiec.librechat.core.model.response.SkillValidationErrorResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Serialization round-trips for the Skills wire types. Mirrors the project's
 * Json config (ignoreUnknownKeys/explicitNulls=false) so the assertions reflect
 * what the network module actually does, and covers the fail-safe nullable-
 * widening fields (server may omit them).
 */
class SkillSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun fullSkillRoundTrip() {
        val original = Skill(
            id = "sk-1",
            name = "my-skill",
            displayTitle = "My Skill",
            description = "Does a thing.",
            body = "# Heading\n\nbody text",
            frontmatter = buildJsonObject { put("when-to-use", "always") },
            category = "general",
            version = 3,
            fileCount = 2,
            alwaysApply = true,
            isPublic = false,
        )
        val decoded = json.decodeFromString(Skill.serializer(), json.encodeToString(Skill.serializer(), original))
        assertEquals(original, decoded)
    }

    @Test
    fun skillDecodesServerShapeWithUnderscoreIdAndUnknownKeys() {
        val wire = """
            {
              "_id": "sk-2",
              "name": "kebab-name",
              "description": "desc",
              "body": "content",
              "version": 1,
              "fileCount": 0,
              "source": "inline",
              "someFutureField": 123
            }
        """.trimIndent()
        val decoded = json.decodeFromString(Skill.serializer(), wire)
        assertEquals("sk-2", decoded.id)
        assertEquals("kebab-name", decoded.name)
        assertEquals(1, decoded.version)
        // Unknown server-added keys must not break decode.
    }

    @Test
    fun skillToleratesMissingOptionalFields() {
        // Minimal payload: only the required-ish keys; everything else widens to defaults/null.
        val wire = """{ "_id": "sk-3", "name": "n", "description": "d" }"""
        val decoded = json.decodeFromString(Skill.serializer(), wire)
        assertEquals("sk-3", decoded.id)
        assertEquals("", decoded.body)
        assertEquals(1, decoded.version)
        assertEquals(0, decoded.fileCount)
        assertNull(decoded.frontmatter)
        assertNull(decoded.alwaysApply)
        assertNull(decoded.warnings)
    }

    @Test
    fun skillConflictResponseDecodesNestedCurrent() {
        val wire = """
            {
              "error": "skill_version_conflict",
              "current": { "_id": "sk-4", "name": "n", "description": "d", "version": 7 }
            }
        """.trimIndent()
        val decoded = json.decodeFromString(SkillConflictResponse.serializer(), wire)
        assertEquals("skill_version_conflict", decoded.error)
        assertEquals("sk-4", decoded.current.id)
        assertEquals(7, decoded.current.version)
    }

    @Test
    fun validationErrorResponseDecodesIssues() {
        val wire = """
            {
              "error": "Validation failed",
              "issues": [
                { "field": "name", "code": "reserved_prefix", "message": "Name cannot start with 'lc-'", "severity": "warning" }
              ]
            }
        """.trimIndent()
        val decoded = json.decodeFromString(SkillValidationErrorResponse.serializer(), wire)
        assertEquals(1, decoded.issues.size)
        assertEquals("Name cannot start with 'lc-'", decoded.issues.first().message)
    }

    @Test
    fun createSkillRequestOmitsNullsAndKeepsKeys() {
        val req = CreateSkillRequest(name = "n", description = "d", body = "b")
        val encoded = json.encodeToString(CreateSkillRequest.serializer(), req)
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("n", obj["name"]?.jsonPrimitive?.content)
        assertEquals("d", obj["description"]?.jsonPrimitive?.content)
        assertEquals("b", obj["body"]?.jsonPrimitive?.content)
        // explicitNulls=false → unset optional fields are omitted, not null.
        assertTrue("displayTitle" !in obj)
        assertTrue("category" !in obj)
    }

    @Test
    fun updateSkillRequestAlwaysCarriesExpectedVersion() {
        val req = UpdateSkillRequest(expectedVersion = 5, name = "n2")
        val encoded = json.encodeToString(UpdateSkillRequest.serializer(), req)
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals(5, obj["expectedVersion"]?.jsonPrimitive?.content?.toInt())
        assertEquals("n2", obj["name"]?.jsonPrimitive?.content)
        // Round-trips back.
        val decoded = json.decodeFromString(UpdateSkillRequest.serializer(), encoded)
        assertEquals(req, decoded)
    }
}
