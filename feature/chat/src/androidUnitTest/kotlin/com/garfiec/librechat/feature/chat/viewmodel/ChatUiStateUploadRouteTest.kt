package com.garfiec.librechat.feature.chat.viewmodel

import com.garfiec.librechat.core.common.EndpointConstants
import com.garfiec.librechat.core.model.EndpointConfig
import com.garfiec.librechat.core.model.response.UploadRoute
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The state-level half of upload routing: which arguments [ChatUiState] hands the router.
 *
 * `UploadRoutingTest` in `:core:model` pins the table and `ChatViewModelUploadRoutingTest` pins
 * the composition. Neither can see this seam — a state that passes the *wrong* provider gets a
 * perfectly correct answer to a question about a selection the user has already left.
 */
class ChatUiStateUploadRouteTest {

    private val pdf = "application/pdf"
    private val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    private fun state(
        endpoint: String,
        agentProvider: String? = null,
        configs: Map<String, EndpointConfig> = mapOf(endpoint to EndpointConfig()),
    ) = ChatUiState(
        selection = ModelSelectionState(
            selectedEndpoint = endpoint,
            selectedAgentProvider = agentProvider,
            endpointConfigs = configs,
        ),
    )

    @Test
    fun `an agent provider left over from a previous selection does not route another endpoint`() {
        // `selectedAgentProvider` is documented as null off the agents endpoint, but the collector
        // enforcing that resumes on a LATER dispatch than the write that moved the endpoint. A pick
        // in that gap would route against an agent the selection has already left.
        val stale = state(endpoint = "openAI", agentProvider = "ollama")

        assertThat(stale.uploadRouteFor(pdf)).isEqualTo(UploadRoute.PROVIDER)
    }

    @Test
    fun `the agent provider still routes on the agents endpoint`() {
        val onAgents = state(endpoint = EndpointConstants.AGENTS, agentProvider = "ollama")

        // The guard must narrow *where* the provider is read, not stop reading it — dropping it
        // here would take every agent back to the pre-feature silent drop.
        assertThat(onAgents.uploadRouteFor(docx)).isEqualTo(UploadRoute.TEXT)
    }

    @Test
    fun `an unresolved provider is not a provider-only file`() {
        val unresolved = state(endpoint = EndpointConstants.AGENTS, agentProvider = null)

        // Auto keeps today's behaviour...
        assertThat(unresolved.uploadRouteFor(docx)).isEqualTo(UploadRoute.PROVIDER)
        // ...but "we could not identify the provider" is not "the provider takes it natively and
        // nothing else", and Manual mode must still offer the choice rather than skip the sheet.
        assertThat(unresolved.uploadRouteIsAmbiguous(docx)).isTrue()
    }

    @Test
    fun `a type the server cannot extract is never ambiguous, resolved or not`() {
        val unresolved = state(endpoint = EndpointConstants.AGENTS, agentProvider = null)

        // There is no second option to offer: a zip routed to text is a raw UTF-8 decode.
        assertThat(unresolved.uploadRouteIsAmbiguous("application/zip")).isFalse()
        assertThat(unresolved.uploadRouteIsAmbiguous("image/png")).isFalse()
    }
}
