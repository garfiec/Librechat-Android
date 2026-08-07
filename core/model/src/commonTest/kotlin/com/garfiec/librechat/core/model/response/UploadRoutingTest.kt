package com.garfiec.librechat.core.model.response

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The governing invariant: routing to [UploadRoute.TEXT] is only ever correct when the provider
 * cannot take the file natively **and** the server can demonstrably extract it. Every other cell
 * must stay [UploadRoute.PROVIDER], which is exactly what mobile did before this router existed.
 */
class UploadRoutingTest {

    private val png = "image/png"
    private val svg = "image/svg+xml"
    private val pdf = "application/pdf"
    private val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private val xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private val ods = "application/vnd.oasis.opendocument.spreadsheet"
    private val odt = "application/vnd.oasis.opendocument.text"
    private val csv = "text/csv"
    private val txt = "text/plain"
    private val md = "text/markdown"
    private val pptx = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    private val epub = "application/epub+zip"
    private val zip = "application/zip"
    private val tar = "application/x-tar"
    private val doc = "application/msword"
    private val parquet = "application/x-parquet"
    private val mp4 = "video/mp4"
    private val mp3 = "audio/mpeg"
    private val unknownBinary = "application/octet-stream"

    private fun route(
        mime: String?,
        endpoint: String?,
        endpointType: String? = null,
        agentProvider: String? = null,
    ) = resolveUploadRoute(mime, endpoint, endpointType, agentProvider)

    // ---------------------------------------------------------------- images

    @Test
    fun imagesAlwaysGoToTheProvider() {
        // Every provider takes images natively; text extraction of an image needs OCR we can't see.
        for (endpoint in listOf("anthropic", "openAI", "google", "bedrock", "ollama", "openrouter")) {
            assertEquals(UploadRoute.PROVIDER, route(png, endpoint), "png on $endpoint")
        }
    }

    @Test
    fun svgStaysOnTheProviderEvenThoughItIsMarkup() {
        // `image/svg+xml` fails upstream's defaultTextMimeTypes `[\w.-]` class on the `+`, so the
        // text path throws "not supported for text parsing" rather than degrading.
        assertEquals(UploadRoute.PROVIDER, route(svg, "ollama"))
        assertEquals(UploadRoute.PROVIDER, route(svg, "anthropic"))
    }

    // ------------------------------------------------------- document providers

    @Test
    fun pdfGoesToTheProviderOnEveryDocumentSupportedProvider() {
        val documentProviders = listOf(
            "anthropic", "openAI", "bedrock", "custom", "google",
            "vertexai", "mistralai", "mistral", "deepseek", "moonshot", "openrouter", "xai",
        )
        for (provider in documentProviders) {
            assertEquals(UploadRoute.PROVIDER, route(pdf, provider), "pdf on $provider")
        }
    }

    @Test
    fun pdfIsExtractedToTextOnlyWhenTheProviderCannotTakeIt() {
        assertEquals(UploadRoute.TEXT, route(pdf, "ollama"))
    }

    @Test
    fun officeDocumentsAreExtractedOnEveryProviderExceptBedrock() {
        // Only Bedrock declares native docx/xlsx blocks; anthropic et al. accept PDF and images only.
        for (mime in listOf(docx, xlsx, ods, odt)) {
            assertEquals(UploadRoute.TEXT, route(mime, "anthropic"), "$mime on anthropic")
            assertEquals(UploadRoute.TEXT, route(mime, "ollama"), "$mime on ollama")
        }
    }

    @Test
    fun bedrockTakesItsDocumentFormatsNatively() {
        for (mime in listOf(pdf, docx, xlsx, csv, txt, md, doc, "text/html")) {
            assertEquals(UploadRoute.PROVIDER, route(mime, "bedrock"), "$mime on bedrock")
        }
        // ...but not the ones absent from its format table.
        assertEquals(UploadRoute.TEXT, route(ods, "bedrock"))
    }

    @Test
    fun bedrockIsAlsoRecognisedViaEndpointType() {
        assertEquals(UploadRoute.PROVIDER, route(docx, "My Bedrock", endpointType = "bedrock"))
    }

    @Test
    fun googleAndOpenRouterTakeVideoAndAudioNatively() {
        for (provider in listOf("google", "openrouter")) {
            assertEquals(UploadRoute.PROVIDER, route(mp4, provider), "mp4 on $provider")
            assertEquals(UploadRoute.PROVIDER, route(mp3, provider), "mp3 on $provider")
        }
    }

    @Test
    fun vertexAiIsNotTreatedAsVideoCapableMirroringUpstream() {
        // Upstream's supportsImageDocVideoAudio is google||openrouter only. Video never routes to
        // text here anyway, so mirroring the omission costs nothing and keeps sync diffs clean.
        assertFalse(isProviderCapable(mp4, "vertexai"))
        assertEquals(UploadRoute.PROVIDER, route(mp4, "vertexai"))
    }

    // ------------------------------------------------- non-extractable types

    @Test
    fun binaryContainersNeverRouteToText() {
        // These are absent from documentParserMimeTypes, so "text" means a raw UTF-8 decode of
        // container bytes: silent mojibake, token-counted and re-injected every turn. The provider
        // rejecting them visibly is strictly better.
        for (mime in listOf(zip, tar, pptx, epub, parquet, doc, unknownBinary)) {
            assertEquals(UploadRoute.PROVIDER, route(mime, "ollama"), "$mime on ollama")
            assertEquals(UploadRoute.PROVIDER, route(mime, "anthropic"), "$mime on anthropic")
        }
    }

    @Test
    fun videoAndAudioNeverRouteToTextEvenWhereTheProviderRejectsThem() {
        // Audio's text path is speech-to-text, which throws without `speech.stt` configured, and
        // video's is nothing at all. Neither is knowable client-side.
        assertEquals(UploadRoute.PROVIDER, route(mp4, "anthropic"))
        assertEquals(UploadRoute.PROVIDER, route(mp3, "anthropic"))
    }

    // ----------------------------------------------------------- textual types

    @Test
    fun plainTextIsExtractedWhereTheProviderCannotTakeIt() {
        assertEquals(UploadRoute.TEXT, route(txt, "anthropic"))
        assertEquals(UploadRoute.TEXT, route(csv, "openAI"))
        assertEquals(UploadRoute.TEXT, route(md, "anthropic"))
        assertEquals(UploadRoute.TEXT, route("application/json", "anthropic"))
        assertEquals(UploadRoute.TEXT, route("text/x-python", "anthropic"))
    }

    @Test
    fun azureOpenAiIsNotTreatedAsDocumentCapable() {
        // Mirrors upstream's commented-out set entry: Azure only takes documents with
        // `useResponsesApi`, a per-agent toggle. We could read that flag but choose not to model
        // the corner, so an Azure document is extracted — which works — rather than sent down a
        // path whose validity depends on a setting this router never consults.
        assertFalse(isProviderCapable(pdf, "azureOpenAI"))
        assertEquals(UploadRoute.TEXT, route(pdf, "azureOpenAI"))
        assertEquals(UploadRoute.PROVIDER, route(png, "azureOpenAI"))
    }

    // ------------------------------------------------------ extractability probe

    @Test
    fun textExtractabilityIsQueryableForTheManualPicker() {
        // The manual sheet enables its control from this, not from the chosen route: a file the
        // server cannot extract has one usable mode however capable the provider is.
        for (mime in listOf(txt, csv, md, pdf, docx, xlsx, ods, odt, "application/json")) {
            assertTrue(isTextExtractable(mime), "$mime should be extractable")
        }
        for (mime in listOf(png, svg, zip, tar, pptx, epub, parquet, doc, mp4, mp3, unknownBinary)) {
            assertFalse(isTextExtractable(mime), "$mime should not be extractable")
        }
        assertFalse(isTextExtractable(null))
    }

    // -------------------------------------------------------- custom endpoints

    @Test
    fun customEndpointsResolveThroughEndpointTypeNotTheirDisplayLabel() {
        // `endpoint` here is whatever the admin named the entry; only `endpointType` says "custom".
        assertEquals(UploadRoute.PROVIDER, route(pdf, "Groq", endpointType = "custom"))
        assertEquals(UploadRoute.PROVIDER, route(pdf, "LiteLLM", endpointType = "custom"))
        // Without the type it looks like an unknown provider and the PDF would be extracted.
        assertEquals(UploadRoute.TEXT, route(pdf, "Groq"))
    }

    // --------------------------------------------------------- agents endpoint

    @Test
    fun agentsRouteOnTheAgentProviderNotTheEndpointName() {
        assertEquals(UploadRoute.PROVIDER, route(pdf, "agents", agentProvider = "anthropic"))
        assertEquals(UploadRoute.TEXT, route(pdf, "agents", agentProvider = "ollama"))
        assertEquals(UploadRoute.TEXT, route(docx, "agents", agentProvider = "anthropic"))
    }

    @Test
    fun agentsWithAnUnresolvedProviderChangeNothingFromTodaysBehaviour() {
        // `agents` is the default endpoint and the agent *list* response omits `provider`
        // entirely, so this is the routine case, not the exceptional one. Proving it stays on
        // PROVIDER is the regression guard for the whole feature.
        assertEquals(UploadRoute.PROVIDER, route(pdf, "agents"))
        assertEquals(UploadRoute.PROVIDER, route(docx, "agents"))
        assertEquals(UploadRoute.PROVIDER, route(txt, "agents", agentProvider = ""))
    }

    @Test
    fun assistantsEndpointsAreOpaqueAndChangeNothing() {
        assertEquals(UploadRoute.PROVIDER, route(pdf, "assistants"))
        assertEquals(UploadRoute.PROVIDER, route(docx, "azureAssistants"))
    }

    // ----------------------------------------------------- unresolvable inputs

    @Test
    fun anUnknownProviderStillExtractsBecauseTheNameWasKnownToBeAbsent() {
        // A resolved-but-unlisted provider (a self-hosted Ollama) is the case the feature exists
        // for: today its .docx is silently dropped server-side with no error at all.
        assertEquals(UploadRoute.TEXT, route(docx, "ollama"))
    }

    @Test
    fun aMissingEndpointNeverChangesBehaviour() {
        assertEquals(UploadRoute.PROVIDER, route(pdf, null))
        assertEquals(UploadRoute.PROVIDER, route(docx, ""))
    }

    @Test
    fun aMissingMimeTypeNeverChangesBehaviour() {
        assertEquals(UploadRoute.PROVIDER, route(null, "ollama"))
        assertEquals(UploadRoute.PROVIDER, route("", "ollama"))
        assertEquals(UploadRoute.PROVIDER, route("   ", "ollama"))
    }

    // --------------------------------------------------------- normalisation

    @Test
    fun mimeParametersAreStrippedBeforeMatching() {
        assertEquals(UploadRoute.TEXT, route("text/plain; charset=utf-8", "anthropic"))
        assertEquals(UploadRoute.PROVIDER, route("image/png;charset=binary", "anthropic"))
        assertEquals(UploadRoute.PROVIDER, route("application/pdf; qs=0.001", "anthropic"))
    }

    @Test
    fun upstreamMimeAliasesAreAppliedBeforeLookup() {
        // The server rewrites these on receipt; the router runs first, so without the alias table
        // `text/x-markdown` would miss Bedrock's native format list.
        assertEquals(UploadRoute.PROVIDER, route("text/x-markdown", "bedrock"))
        // application/x-zip-compressed -> application/zip, which is not extractable either way.
        assertEquals(UploadRoute.PROVIDER, route("application/x-zip-compressed", "ollama"))
    }

    @Test
    fun openRouterIsMatchedCaseInsensitively() {
        // The one provider upstream lower-cases before comparing. A blanket lowercase() would
        // instead drop `openAI` out of the document-supported set, so this must stay narrow.
        assertEquals(UploadRoute.PROVIDER, route(mp4, "OpenRouter"))
        assertEquals(UploadRoute.PROVIDER, route(pdf, "OPENROUTER"))
        assertEquals(UploadRoute.PROVIDER, route(pdf, "openAI"))
        // Casing is not forgiven elsewhere — `openai` is not the upstream key.
        assertEquals(UploadRoute.TEXT, route(pdf, "openai"))
    }

    // ------------------------------------------------------- capability probe

    @Test
    fun providerCapabilityIsQueryableForTheManualPicker() {
        assertTrue(isProviderCapable(pdf, "anthropic"))
        assertFalse(isProviderCapable(docx, "anthropic"))
        assertTrue(isProviderCapable(docx, "bedrock"))
        assertTrue(isProviderCapable(png, "ollama"))
        assertFalse(isProviderCapable(txt, "ollama"))
        assertFalse(isProviderCapable(null, "anthropic"))
    }
}
