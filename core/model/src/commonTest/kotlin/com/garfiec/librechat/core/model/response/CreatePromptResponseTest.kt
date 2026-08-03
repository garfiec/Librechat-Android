package com.garfiec.librechat.core.model.response

import com.garfiec.librechat.core.model.PromptGroup
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class CreatePromptResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Verbatim shape of `POST /api/prompts`: `createPromptGroup` returns `{ prompt, group }`. */
    private val createResponse = """
        {
          "prompt": {
            "_id": "p1",
            "groupId": "g1",
            "author": "u1",
            "prompt": "Summarize the following:",
            "type": "text"
          },
          "group": {
            "_id": "g1",
            "name": "Summarize",
            "author": "u1",
            "authorName": "Garfie",
            "productionId": "p1",
            "productionPrompt": { "prompt": "Summarize the following:" }
          }
        }
    """.trimIndent()

    @Test
    fun createResponseUnwrapsToTheNestedGroup() {
        val group = json.decodeFromString<CreatePromptResponse>(createResponse).group
        assertEquals("g1", group.id)
        assertEquals("Summarize", group.name)
        assertEquals("Summarize the following:", group.productionPrompt?.prompt)
    }

    @Test
    fun theSameBodyCannotBeDecodedAsABareGroup() {
        // The wrapper can't decode as a bare PromptGroup — name/author/authorName are missing — so
        // a create that already succeeded server-side surfaces as a failure.
        assertFails { json.decodeFromString<PromptGroup>(createResponse) }
    }
}
