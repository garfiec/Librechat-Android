package com.garfiec.librechat.feature.chat.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolCallRoutingTest {

    @Test
    fun `code interpreter and execute names route to code card`() {
        assertThat(isCodeExecutionToolCall("code_interpreter")).isTrue()
        assertThat(isCodeExecutionToolCall("execute_code")).isTrue()
    }

    @Test
    fun `bash and programmatic tool names route to code card`() {
        assertThat(isCodeExecutionToolCall("bash_tool")).isTrue()
        assertThat(isCodeExecutionToolCall("run_tools_with_bash")).isTrue()
        assertThat(isCodeExecutionToolCall("run_tools_with_code")).isTrue()
    }

    @Test
    fun `bash names are recognized as bash tool calls`() {
        assertThat(isBashToolCall("bash_tool")).isTrue()
        assertThat(isBashToolCall("run_tools_with_bash")).isTrue()
        assertThat(isBashToolCall("run_tools_with_code")).isFalse()
        assertThat(isBashToolCall("code_interpreter")).isFalse()
    }

    @Test
    fun `non-code tools do not route to code card`() {
        assertThat(isCodeExecutionToolCall("web_search")).isFalse()
        assertThat(isCodeExecutionToolCall("file_search")).isFalse()
        assertThat(isCodeExecutionToolCall("lc_transfer_to_agent")).isFalse()
    }
}
