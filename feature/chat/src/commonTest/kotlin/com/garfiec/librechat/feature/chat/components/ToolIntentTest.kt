package com.garfiec.librechat.feature.chat.components

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `intent` rides inside the tool call's own arguments, which arrive as an object mid-stream and as
 * a JSON *string* once persisted — so both shapes have to read back the same, or the label would
 * appear during the run and vanish on reload.
 */
class ToolIntentTest {

    @Test
    fun readsTheIntentFromStreamedObjectArgs() {
        val args = buildJsonObject {
            put("intent", "Searching for OAuth handling in the callback router")
            put("query", "oauth callback")
        }
        assertEquals("Searching for OAuth handling in the callback router", parseToolIntent(args))
    }

    @Test
    fun readsTheIntentFromPersistedStringArgs() {
        val args = JsonPrimitive("""{"intent":"Reading the router","path":"/router.ts"}""")
        assertEquals("Reading the router", parseToolIntent(args))
    }

    @Test
    fun argsWithoutAnIntentFallThrough() {
        // The capability is off or the tool did not opt in — every caller falls back to the name.
        assertNull(parseToolIntent(buildJsonObject { put("query", "oauth") }))
        assertNull(parseToolIntent(JsonPrimitive("""{"query":"oauth"}""")))
    }

    @Test
    fun blankAndNonStringIntentsAreIgnored() {
        assertNull(parseToolIntent(buildJsonObject { put("intent", "   ") }))
        assertNull(parseToolIntent(buildJsonObject { put("intent", 3) }))
    }

    @Test
    fun anIntentThatIsNotTheFirstKeyIsSomeoneElsesParameter() {
        // Nothing about the opt-in reaches the client: the capability and the per-tool
        // `describe_intent` flag are server-side, and the arg is stripped before a tool that did
        // not declare it runs. A user's own MCP tool taking an `intent` parameter would otherwise
        // get its argument value printed as the card's title, on any server.
        val args = buildJsonObject {
            put("query", "oauth")
            put("intent", "book a flight")
        }
        assertNull(parseToolIntent(args))
        assertNull(parseToolIntent(JsonPrimitive("""{"query":"oauth","intent":"book a flight"}""")))
    }

    @Test
    fun displayedArgsDropTheLabelAlreadyShownAsTheTitle() {
        assertEquals(
            """{"path":"/router.ts"}""",
            argsWithoutIntent("""{"intent":"Reading the router","path":"/router.ts"}"""),
        )
    }

    @Test
    fun displayedArgsAreLeftAloneWhenTheIntentIsNotTheTitle() {
        // Not first — it was never lifted, so removing it would hide a real argument.
        val args = """{"query":"oauth","intent":"book a flight"}"""
        assertEquals(args, argsWithoutIntent(args))
        assertEquals("""{"query":"oauth"}""", argsWithoutIntent("""{"query":"oauth"}"""))
        assertNull(argsWithoutIntent(null))
    }

    @Test
    fun argsThatWereNothingButAnIntentCollapseToNoArgs() {
        assertNull(argsWithoutIntent("""{"intent":"Thinking about it"}"""))
    }

    @Test
    fun malformedArgsDegradeInsteadOfThrowing() {
        assertNull(parseToolIntent(JsonPrimitive("{not json")))
        assertNull(parseToolIntent(null as String?))
        assertNull(parseToolIntent(JsonPrimitive(true)))
    }
}
