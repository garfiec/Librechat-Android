package com.garfiec.librechat.core.common

object EndpointConstants {
    const val AGENTS = "agents"
}

object ToolConstants {
    const val WEB_SEARCH = "web_search"

    /** Google Gemini "URL Context" grounding (v0.8.7). A Google-only model parameter,
     *  sibling of [WEB_SEARCH] — backed by `modelParameters.urlContext`, not `enabledTools`. */
    const val URL_CONTEXT = "url_context"
    const val CODE_INTERPRETER = "code_interpreter"
    const val FILE_SEARCH = "file_search"
    const val EXECUTE_CODE = "execute_code"
    const val PROGRAMMATIC_TOOLS = "programmatic_tools"
    const val DEFERRED_TOOLS = "deferred_tools"

    /** Server-side retrieval tool (`retrieval` / RAG). Rendered as a generic tool card on
     *  mobile — its structured sources live in the attachment's file_search payload, which
     *  the mobile Attachment model does not yet carry. */
    const val RETRIEVAL = "retrieval"

    /** Sandbox "bash tool" — runs a shell command whose `code` arg is the command line.
     *  Routed to the code-execution card (matches upstream `BashCall`, `commandField="code"`). */
    const val BASH_TOOL = "bash_tool"

    /** Programmatic tool calling: the agent emits Python (`run_tools_with_code`) or bash
     *  (`run_tools_with_bash`) that orchestrates other tools. Both surface a `code` arg and
     *  render as the code-execution card, like upstream ExecuteCode/BashCall. */
    const val PROGRAMMATIC_TOOL_CALLING = "run_tools_with_code"
    const val BASH_PROGRAMMATIC_TOOL_CALLING = "run_tools_with_bash"

    /** Prefix of the agent-handoff tool a supervisor emits to transfer control to a named
     *  child agent (`lc_transfer_to_<agent>`). Rendered as an agent-handoff row (upstream
     *  `AgentHandoff`). */
    const val LC_TRANSFER_TO_PREFIX = "lc_transfer_to_"

    /** The `subagent` tool a parent agent invokes to delegate to a child agent
     *  (v0.8.6). Matches upstream `Constants.SUBAGENT`; used to correlate live
     *  `on_subagent_update` traces to their parent tool_call and to render the
     *  subagent trace card. */
    const val SUBAGENT = "subagent"
}

object ChatLayoutConstants {
    const val THREAD = "thread"
    const val TWO_SIDED = "two_sided"
}
