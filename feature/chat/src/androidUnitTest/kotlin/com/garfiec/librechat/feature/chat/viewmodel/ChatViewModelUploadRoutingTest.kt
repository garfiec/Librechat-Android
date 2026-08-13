package com.garfiec.librechat.feature.chat.viewmodel

import androidx.lifecycle.viewModelScope
import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.common.result.Result
import com.garfiec.librechat.core.data.datastore.ChatFontSize
import com.garfiec.librechat.core.data.datastore.ChatHeaderAlignment
import com.garfiec.librechat.core.data.datastore.ChatHeaderContent
import com.garfiec.librechat.core.data.datastore.ContextBarPlacement
import com.garfiec.librechat.core.data.datastore.DuringRunAction
import com.garfiec.librechat.core.data.datastore.StarredModelsDisplay
import com.garfiec.librechat.core.data.datastore.UploadRoutingMode
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.response.UploadRoute
import com.garfiec.librechat.feature.chat.viewmodel.delegate.PickedFile
import com.garfiec.librechat.feature.chat.viewmodel.delegate.PlatformFileHandler
import com.garfiec.librechat.feature.chat.viewmodel.delegate.RoutedFile
import com.garfiec.librechat.feature.chat.viewmodel.delegate.ShareData
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The composition test for upload routing: that the route actually handed to the platform handler
 * is the one the router computed from the *live* selection.
 *
 * `UploadRoutingTest` in `:core:model` pins the table itself, and every case there would still
 * pass against a ViewModel that ignored the router and sent PROVIDER for everything. So these
 * tests capture the argument and assert its values, rather than that a call was made.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelUploadRoutingTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val fixture = ChatViewModelTestFixture()
    private val agentRepository get() = fixture.agentRepository
    private val chatRepository get() = fixture.chatRepository
    private val messageRepository get() = fixture.messageRepository
    private val configRepository get() = fixture.configRepository
    private val conversationRepository get() = fixture.conversationRepository
    private val favoritesRepository get() = fixture.favoritesRepository
    private val keyRepository get() = fixture.keyRepository
    private val roleRepository get() = fixture.roleRepository
    private val serverDataStore get() = fixture.serverDataStore
    private val settingsDataStore get() = fixture.settingsDataStore
    private val platformDelegateFactory get() = fixture.platformDelegateFactory
    private val serverFileSelectionHandoff get() = fixture.serverFileSelectionHandoff
    private val fileHandler = mockk<PlatformFileHandler>(relaxed = true)

    private val endpointConfigs = MutableStateFlow<Map<String, EndpointConfig>>(emptyMap())
    private val availableModels = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val shares = MutableSharedFlow<ShareData>(extraBufferCapacity = 1)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fixture.stubDefaults()

        every { configRepository.detectedBackendVersion } returns MutableStateFlow(null)
        every { configRepository.endpointConfigs } returns endpointConfigs
        // A real model list, so the send-readiness gate lets `sendMessage` through: the staged-batch
        // test below has to observe the send being REFUSED, which is meaningless if the send would
        // have been refused anyway.
        every { configRepository.availableModels } returns availableModels
        every { settingsDataStore.selectedMcpServers } returns flowOf(emptySet())
        every { settingsDataStore.enabledTools } returns flowOf(emptySet())

        every { serverDataStore.currentUrlFlow } returns flowOf("https://example.test")
        every { settingsDataStore.chatFontSize } returns flowOf(ChatFontSize.MEDIUM)
        every { settingsDataStore.starredModelsDisplay } returns flowOf(StarredModelsDisplay.OFF)
        every { settingsDataStore.chatHeaderContent } returns flowOf(ChatHeaderContent.TITLE)
        every { settingsDataStore.chatHeaderAlignment } returns flowOf(ChatHeaderAlignment.LEFT)
        every { settingsDataStore.contextBarPlacement } returns flowOf(ContextBarPlacement.OPTIONS_SHEET)
        every { settingsDataStore.contextGaugeExpanded } returns flowOf(false)
        every { settingsDataStore.duringRunAction } returns flowOf(DuringRunAction.QUEUE)
        every { settingsDataStore.uploadRoutingMode } returns flowOf(UploadRoutingMode.AUTO)
        // StateFlow.collect returns Nothing, so a relaxed mock throws in the delegate's collector.
        every { platformDelegateFactory.createShareConsumer().sharesFor(any()) } returns shares

        every { platformDelegateFactory.createFileHandler(any()) } returns fileHandler
        every { fileHandler.attachedFiles } returns MutableStateFlow(emptyList())

        coEvery { conversationRepository.getConversation(any(), any()) } returns Result.Error(message = "test")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a document provider takes pdf natively and has its docx extracted`() =
        routingTest(endpoint = "anthropic") { vm ->
            val routes = routesFor(vm, "report.pdf" to PDF, "notes.docx" to DOCX)

            assertThat(routes).containsExactly(UploadRoute.PROVIDER, UploadRoute.TEXT).inOrder()
        }

    @Test
    fun `types the server cannot extract stay on the provider whatever it accepts`() =
        routingTest(endpoint = "anthropic") { vm ->
            val routes = routesFor(vm, "photo.png" to "image/png", "bundle.zip" to "application/zip")

            assertThat(routes).containsExactly(UploadRoute.PROVIDER, UploadRoute.PROVIDER)
        }

    @Test
    fun `an agent routes on its own provider, not on the agents endpoint name`() =
        routingTest(endpoint = EndpointConstants.AGENTS, model = AGENT_ID, agentProvider = "ollama") { vm ->
            val routes = routesFor(vm, "report.pdf" to PDF)

            // The whole point of the feature: today this PDF is silently dropped server-side.
            assertThat(routes).containsExactly(UploadRoute.TEXT)
        }

    @Test
    fun `an agent whose provider is unresolved changes nothing from today`() =
        routingTest(endpoint = EndpointConstants.AGENTS, model = AGENT_ID, agentProvider = null) { vm ->
            val routes = routesFor(vm, "report.pdf" to PDF, "notes.docx" to DOCX)

            // The regression guard. `agents` is the default endpoint and its list response omits
            // `provider`, so a build that forgot to resolve it would extract everything here.
            assertThat(routes).containsExactly(UploadRoute.PROVIDER, UploadRoute.PROVIDER)
        }

    @Test
    fun `a custom endpoint is recognised through its type, not its display label`() =
        routingTest(
            endpoint = "Groq",
            configs = mapOf("Groq" to EndpointConfig(type = "custom")),
        ) { vm ->
            val routes = routesFor(vm, "report.pdf" to PDF)

            assertThat(routes).containsExactly(UploadRoute.PROVIDER)
        }

    @Test
    fun `an agent selection without the context capability never extracts`() =
        routingTest(
            endpoint = EndpointConstants.AGENTS,
            model = AGENT_ID,
            agentProvider = "ollama",
            configs = mapOf(
                EndpointConstants.AGENTS to EndpointConfig(capabilities = listOf("execute_code")),
            ),
        ) { vm ->
            val routes = routesFor(vm, "notes.docx" to DOCX)

            assertThat(routes).containsExactly(UploadRoute.PROVIDER)
        }

    @Test
    fun `the agents capability list does not gate other endpoints`() =
        routingTest(
            endpoint = "anthropic",
            configs = mapOf(
                "anthropic" to EndpointConfig(),
                EndpointConstants.AGENTS to EndpointConfig(capabilities = listOf("execute_code")),
            ),
        ) { vm ->
            // `capabilities` is the agents endpoint's policy. `POST /api/files` routes every
            // non-assistants upload through one handler whose `context` branch runs no capability
            // check at all, so honouring the agents list here would refuse an extraction the
            // server performs happily — and one admin's agent policy would disable the feature
            // for every endpoint at once.
            val routes = routesFor(vm, "notes.docx" to DOCX)

            assertThat(routes).containsExactly(UploadRoute.TEXT)
        }

    @Test
    fun `an empty capability list is read as unstated, not as a denial`() =
        routingTest(
            endpoint = "anthropic",
            configs = mapOf(
                "anthropic" to EndpointConfig(),
                EndpointConstants.AGENTS to EndpointConfig(capabilities = emptyList()),
            ),
        ) { vm ->
            // `EndpointConfig.capabilities` defaults to an empty list, so a server that omits the
            // field is indistinguishable from one sending `[]`. Reading `[]` as "no context
            // capability" would ship the feature dead against every such server.
            val routes = routesFor(vm, "notes.docx" to DOCX)

            assertThat(routes).containsExactly(UploadRoute.TEXT)
        }

    // ── Manual mode ─────────────────────────────────────────────────────────

    @Test
    fun `manual mode stages a batch instead of uploading it`() =
        routingTest(endpoint = "anthropic", mode = UploadRoutingMode.MANUAL) { vm ->
            stage(vm, "report.pdf" to PDF)

            val pending = vm.uiState.value.composer.pendingUploadRouting
            assertThat(pending).isNotNull()
            assertThat(pending!!.files.map { it.file.name }).containsExactly("report.pdf")
            // Nothing may reach the handler until the user answers: the decision exists precisely
            // so it happens BEFORE an upload, leaving nothing to clean up on cancel.
            io.mockk.verify(exactly = 0) { fileHandler.onFilesSelected(any()) }
        }

    @Test
    fun `manual mode does not ask when no file has a real choice`() =
        routingTest(endpoint = "anthropic", mode = UploadRoutingMode.MANUAL) { vm ->
            // An image and a zip: one is provider-only, the other is not extractable. A sheet whose
            // every control is disabled is friction, not choice.
            val routes = routesFor(vm, "photo.png" to "image/png", "bundle.zip" to "application/zip")

            assertThat(vm.uiState.value.composer.pendingUploadRouting).isNull()
            assertThat(routes).containsExactly(UploadRoute.PROVIDER, UploadRoute.PROVIDER)
        }

    @Test
    fun `an image rides along in a staged batch but cannot be changed`() =
        routingTest(endpoint = "anthropic", mode = UploadRoutingMode.MANUAL) { vm ->
            stage(vm, "photo.png" to "image/png", "report.pdf" to PDF)

            val staged = vm.uiState.value.composer.pendingUploadRouting!!.files
            assertThat(staged.map { it.choosable }).containsExactly(false, true).inOrder()

            // "Use for all" must not drag the image onto a path that would drop it entirely.
            vm.setAllPendingUploadRoutes(UploadRoute.TEXT)
            val after = vm.uiState.value.composer.pendingUploadRouting!!.files
            assertThat(after.map { it.route })
                .containsExactly(UploadRoute.PROVIDER, UploadRoute.TEXT).inOrder()
        }

    @Test
    fun `confirming uploads with the routes the user chose`() =
        routingTest(endpoint = "anthropic", mode = UploadRoutingMode.MANUAL) { vm ->
            stage(vm, "report.pdf" to PDF)
            vm.setPendingUploadRoute(index = 0, route = UploadRoute.TEXT)
            val captured = slot<List<RoutedFile>>()
            every { fileHandler.onFilesSelected(capture(captured)) } returns Unit
            vm.confirmPendingUploadRouting()

            assertThat(captured.captured.map { it.route }).containsExactly(UploadRoute.TEXT)
            assertThat(vm.uiState.value.composer.pendingUploadRouting).isNull()
        }

    @Test
    fun `cancelling uploads nothing`() =
        routingTest(endpoint = "anthropic", mode = UploadRoutingMode.MANUAL) { vm ->
            stage(vm, "report.pdf" to PDF)

            vm.cancelPendingUploadRouting()

            assertThat(vm.uiState.value.composer.pendingUploadRouting).isNull()
            io.mockk.verify(exactly = 0) { fileHandler.onFilesSelected(any()) }
        }

    @Test
    fun `a send while a batch is staged does not fire without it`() =
        routingTest(endpoint = "anthropic", mode = UploadRoutingMode.MANUAL) { vm ->
            stage(vm, "report.pdf" to PDF)
            vm.onInputChanged("look at this")

            vm.sendMessage()
            runCurrent()

            // Staged files are in neither the tray nor `hasPendingUploads()`, so an ungated send
            // would go out without them and the confirm would attach them to the NEXT message.
            io.mockk.verify(exactly = 0) {
                chatRepository.startChat(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            }
            assertThat(vm.uiState.value.composer.pendingUploadRouting).isNotNull()
            assertThat(vm.uiState.value.inputText).isEqualTo("look at this")
        }

    @Test
    fun `a send fired during intake does not go out without the picked files`() {
        // Intake is asynchronous — it reads the routing preference and may wait on the agent's
        // provider. Holding it open on the preference read stands in for that whole window.
        val preference = MutableSharedFlow<UploadRoutingMode>()
        routingTest(endpoint = "anthropic", preferenceFlow = preference) { vm ->
            val picked = listOf(PickedFile(ref = "report.pdf", name = "report.pdf", mimeType = PDF))
            every { fileHandler.describe(any()) } returns picked
            val captured = slot<List<RoutedFile>>()
            every { fileHandler.onFilesSelected(capture(captured)) } returns Unit

            vm.onFilesSelected(picked.map { it.ref })
            vm.onInputChanged("look at this")
            vm.sendMessage()
            runCurrent()

            // The files are in neither the tray nor `hasPendingUploads()` yet, so an ungated send
            // goes out without them — and `clearComposer` then drops them, so they surface on the
            // NEXT message instead.
            io.mockk.verify(exactly = 0) {
                chatRepository.startChat(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            }
            assertThat(vm.uiState.value.inputText).isEqualTo("look at this")

            preference.emit(UploadRoutingMode.AUTO)
            runCurrent()
            assertThat(captured.captured.map { it.route }).containsExactly(UploadRoute.PROVIDER)
        }
    }

    @Test
    fun `confirming after the selection moved re-resolves a choice that is no longer available`() =
        routingTest(endpoint = "anthropic", mode = UploadRoutingMode.MANUAL) { vm ->
            stage(vm, "report.pdf" to PDF)
            vm.setPendingUploadRoute(index = 0, route = UploadRoute.PROVIDER)

            // The sheet is a window the selection can move under: a models/config refresh corrects
            // an invalidated selection and a conversation load re-seeds it, neither of which the
            // modal scrim blocks. "To the model" is not a real option on a provider that cannot
            // read PDFs, so confirming must not send one there.
            availableModels.value = mapOf("ollama" to listOf("llama3"))
            endpointConfigs.value = mapOf("ollama" to EndpointConfig())
            vm.onModelSelected("ollama", "llama3")
            runCurrent()

            val captured = slot<List<RoutedFile>>()
            every { fileHandler.onFilesSelected(capture(captured)) } returns Unit
            vm.confirmPendingUploadRouting()

            assertThat(captured.captured.map { it.route }).containsExactly(UploadRoute.TEXT)
        }

    @Test
    fun `a second pick joins the staged batch instead of replacing it`() =
        routingTest(endpoint = "anthropic", mode = UploadRoutingMode.MANUAL) { vm ->
            stage(vm, "first.pdf" to PDF)
            stage(vm, "second.pdf" to PDF)

            // Intake is asynchronous and nothing disables the attach affordance while it runs, so a
            // second pick can land before the sheet for the first is even on screen. Replacing the
            // batch discards the earlier files outright: never uploaded, no error, no trace.
            val pending = vm.uiState.value.composer.pendingUploadRouting
            assertThat(pending!!.files.map { it.file.name })
                .containsExactly("first.pdf", "second.pdf").inOrder()
            io.mockk.verify(exactly = 0) { fileHandler.onFilesSelected(any()) }
        }

    // ── Share intake ────────────────────────────────────────────────────────

    @Test
    fun `a shared file waits for the agent's provider before routing`() {
        // Held open, so the share lands while the provider is genuinely still unresolved — the
        // state every share sees on a cold start, and the one a direct route reads as "unknown".
        val provider = CompletableDeferred<Result<String?>>()
        routingTest(
            endpoint = EndpointConstants.AGENTS,
            model = AGENT_ID,
            providerAnswer = { provider.await() },
        ) { vm ->
            val picked = listOf(PickedFile(ref = "notes.docx", name = "notes.docx", mimeType = DOCX))
            every { fileHandler.describe(any()) } returns picked
            val captured = slot<List<RoutedFile>>()
            every { fileHandler.onFilesSelected(capture(captured)) } returns Unit

            shares.emit(ShareData(fileRefs = picked.map { it.ref }))
            runCurrent()

            provider.complete(Result.Success("ollama"))
            runCurrent()

            // A share is auto-routed and never prompted, but it is not exempt from resolving the
            // provider: routing against a null one silently takes the provider path for every
            // shared document, so the same file behaves differently depending on whether it
            // arrived through the share sheet or the "+" menu a second later.
            assertThat(captured.captured.map { it.route }).containsExactly(UploadRoute.TEXT)
        }
    }

    @Test
    fun `a share that lands before the selection seeds waits for it`() {
        routingTest(
            endpoint = EndpointConstants.AGENTS,
            model = AGENT_ID,
            agentProvider = "ollama",
            seedSelection = false,
        ) { vm ->
            val picked = listOf(PickedFile(ref = "notes.docx", name = "notes.docx", mimeType = DOCX))
            every { fileHandler.describe(any()) } returns picked
            val captured = slot<List<RoutedFile>>()
            every { fileHandler.onFilesSelected(capture(captured)) } returns Unit

            shares.emit(ShareData(fileRefs = picked.map { it.ref }))
            runCurrent()

            // `agents` is the DEFAULT endpoint, so at this point the pair (endpoint=agents,
            // model=null) means "nothing has seeded yet" — not "no agent". A share that launched
            // the app is drained here, before seeding runs. Reading the agent id straight would
            // answer null and route immediately, as unknown.
            assertThat(captured.isCaptured).isFalse()

            availableModels.value = mapOf(EndpointConstants.AGENTS to listOf(AGENT_ID))
            vm.onModelSelected(EndpointConstants.AGENTS, AGENT_ID)
            runCurrent()

            assertThat(captured.captured.map { it.route }).containsExactly(UploadRoute.TEXT)
        }
    }

    @Test
    fun `one intake finishing does not open the send gate while another is still resolving`() {
        val provider = CompletableDeferred<Result<String?>>()
        // Never emits: holds the picker's intake at the preference read, which the share path
        // skips entirely — so the two intakes are blocked at different points and can be
        // finished independently.
        val preference = MutableSharedFlow<UploadRoutingMode>()
        routingTest(
            endpoint = EndpointConstants.AGENTS,
            model = AGENT_ID,
            preferenceFlow = preference,
            providerAnswer = { provider.await() },
        ) { vm ->
            val picked = listOf(PickedFile(ref = "notes.docx", name = "notes.docx", mimeType = DOCX))
            every { fileHandler.describe(any()) } returns picked

            shares.emit(ShareData(fileRefs = picked.map { it.ref }))
            runCurrent()
            vm.onFilesSelected(picked.map { it.ref })
            runCurrent()
            assertThat(vm.uiState.value.arePicksUnsettled).isTrue()

            // The share's intake completes; the picker's is still waiting on the preference.
            provider.complete(Result.Success("ollama"))
            runCurrent()

            // A flag would have been cleared by whichever intake finished first, re-opening the
            // send window for files still in no list any gate reads.
            assertThat(vm.uiState.value.arePicksUnsettled).isTrue()

            preference.emit(UploadRoutingMode.AUTO)
            runCurrent()
            assertThat(vm.uiState.value.arePicksUnsettled).isFalse()
        }
    }

    @Test
    fun `manual mode still asks when the provider could not be resolved`() =
        routingTest(
            endpoint = EndpointConstants.AGENTS,
            model = AGENT_ID,
            agentProvider = null,
            mode = UploadRoutingMode.MANUAL,
        ) { vm ->
            stage(vm, "notes.docx" to DOCX)

            // "Unknown provider" is not "provider-only". Auto still routes it to PROVIDER, but a
            // user who asked to be asked every time must be asked — and treating unknown as
            // unchoosable makes the whole batch unchoosable, so the sheet never opens at all.
            val pending = vm.uiState.value.composer.pendingUploadRouting
            assertThat(pending).isNotNull()
            assertThat(pending!!.files.single().choosable).isTrue()
            io.mockk.verify(exactly = 0) { fileHandler.onFilesSelected(any()) }
        }

    @Test
    fun `flipping one row leaves an identical file staged beside it alone`() =
        routingTest(endpoint = "anthropic", mode = UploadRoutingMode.MANUAL) { vm ->
            // Batches append, so the same file picked twice before the sheet paints is two equal
            // `PickedFile`s. Matching by value would flip both on one tap, with no way for the
            // user to tell which row they changed.
            stage(vm, "report.pdf" to PDF, "report.pdf" to PDF)

            vm.setPendingUploadRoute(index = 0, route = UploadRoute.TEXT)

            val routes = vm.uiState.value.composer.pendingUploadRouting!!.files.map { it.route }
            assertThat(routes).containsExactly(UploadRoute.TEXT, UploadRoute.PROVIDER).inOrder()
        }

    @Test
    fun `a retry waits for the agent's provider before re-resolving the route`() {
        val provider = CompletableDeferred<Result<String?>>()
        routingTest(
            endpoint = EndpointConstants.AGENTS,
            model = AGENT_ID,
            providerAnswer = { provider.await() },
        ) { vm ->
            vm.retryUpload(
                com.garfiec.librechat.feature.chat.components.AttachedFile(
                    uri = "notes.docx",
                    name = "notes.docx",
                    type = DOCX,
                ),
            )
            runCurrent()

            // The delegate re-derives the route from the live selection, and a retry is what the
            // user does *after* a failure — the outage that failed the upload will often have
            // failed the provider fetch too.
            io.mockk.verify(exactly = 0) { fileHandler.retryUpload(any()) }

            provider.complete(Result.Success("ollama"))
            runCurrent()
            io.mockk.verify(exactly = 1) { fileHandler.retryUpload(any()) }
        }
    }

    /** Picks files in manual mode and leaves the batch staged. */
    private fun stage(vm: ChatViewModel, vararg files: Pair<String, String>) {
        val picked = files.map { (name, mime) -> PickedFile(ref = name, name = name, mimeType = mime) }
        every { fileHandler.describe(any()) } returns picked
        vm.onFilesSelected(picked.map { it.ref })
    }

    /** Drives the real intake and returns the routes actually handed to the platform handler. */
    private fun routesFor(vm: ChatViewModel, vararg files: Pair<String, String>): List<UploadRoute> {
        val picked = files.map { (name, mime) -> PickedFile(ref = name, name = name, mimeType = mime) }
        every { fileHandler.describe(any()) } returns picked
        val captured = slot<List<RoutedFile>>()
        every { fileHandler.onFilesSelected(capture(captured)) } returns Unit

        vm.onFilesSelected(picked.map { it.ref })

        return captured.captured.map { it.route }
    }

    private fun routingTest(
        endpoint: String,
        model: String = MODEL,
        agentProvider: String? = null,
        configs: Map<String, EndpointConfig> = mapOf(endpoint to EndpointConfig()),
        mode: UploadRoutingMode = UploadRoutingMode.AUTO,
        /** Supply a hot flow to hold intake open mid-decision; otherwise [mode] resolves at once. */
        preferenceFlow: Flow<UploadRoutingMode>? = null,
        /** Supply a suspending answer to hold the provider fetch open; otherwise it lands at once. */
        providerAnswer: (suspend () -> Result<String?>)? = null,
        /** False leaves the selection un-seeded — the cold-start state a launching share sees,
         *  where the endpoint is `agents` only because that is the default. */
        seedSelection: Boolean = true,
        body: suspend TestScope.(ChatViewModel) -> Unit,
    ) = runTest(testDispatcher) {
        every { settingsDataStore.uploadRoutingMode } returns (preferenceFlow ?: flowOf(mode))
        endpointConfigs.value = configs
        // An un-seeded run must also have nothing for the model pipeline to seed FROM, or init
        // picks the sole available model and the cold-start window closes before the body runs.
        availableModels.value = if (seedSelection) mapOf(endpoint to listOf(model)) else emptyMap()
        val providerResult: Result<String?> = agentProvider
            ?.let { Result.Success(it) }
            ?: Result.Error(message = "unresolved")
        if (providerAnswer != null) {
            coEvery { agentRepository.getAgentProvider(any()) } coAnswers { providerAnswer() }
        } else {
            coEvery { agentRepository.getAgentProvider(any()) } returns providerResult
        }
        val vm = newViewModel()
        runCurrent()
        if (seedSelection) {
            // Drive the selection through the model selector, the same entry point the user's tap
            // uses, so the agent-provider resolution runs exactly as it does in the app.
            vm.onModelSelected(endpoint, model)
            runCurrent()
        }
        try {
            body(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private fun newViewModel(): ChatViewModel =
        fixture.build(
            defaultDispatcher = testDispatcher,
            initialConversationId = CONVERSATION_ID,
        )

    private companion object {
        const val CONVERSATION_ID = "conv-1"
        const val MODEL = "claude-haiku-4-5"
        const val AGENT_ID = "agent_1"
        const val PDF = "application/pdf"
        const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
