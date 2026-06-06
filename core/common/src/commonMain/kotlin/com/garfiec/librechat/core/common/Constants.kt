package com.garfiec.librechat.core.common

object EndpointConstants {
    const val AGENTS = "agents"
}

object ToolConstants {
    const val WEB_SEARCH = "web_search"
    const val CODE_INTERPRETER = "code_interpreter"
    const val FILE_SEARCH = "file_search"
    const val EXECUTE_CODE = "execute_code"
    const val PROGRAMMATIC_TOOLS = "programmatic_tools"
    const val DEFERRED_TOOLS = "deferred_tools"

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
