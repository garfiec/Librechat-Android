package com.librechat.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EModelEndpoint {
    @SerialName("azureOpenAI")
    AZURE_OPENAI,

    @SerialName("openAI")
    OPENAI,

    @SerialName("google")
    GOOGLE,

    @SerialName("anthropic")
    ANTHROPIC,

    @SerialName("assistants")
    ASSISTANTS,

    @SerialName("azureAssistants")
    AZURE_ASSISTANTS,

    @SerialName("agents")
    AGENTS,

    @SerialName("custom")
    CUSTOM,

    @SerialName("bedrock")
    BEDROCK,
}

/** JSON `endpoint` value for built-ins (matches each enum’s @SerialName). */
fun EModelEndpoint.toSerialName(): String = when (this) {
    EModelEndpoint.AZURE_OPENAI -> "azureOpenAI"
    EModelEndpoint.OPENAI -> "openAI"
    EModelEndpoint.GOOGLE -> "google"
    EModelEndpoint.ANTHROPIC -> "anthropic"
    EModelEndpoint.ASSISTANTS -> "assistants"
    EModelEndpoint.AZURE_ASSISTANTS -> "azureAssistants"
    EModelEndpoint.AGENTS -> "agents"
    EModelEndpoint.CUSTOM -> "custom"
    EModelEndpoint.BEDROCK -> "bedrock"
}

@Serializable
enum class ContentType {
    @SerialName("text")
    TEXT,

    @SerialName("think")
    THINK,

    @SerialName("text_delta")
    TEXT_DELTA,

    @SerialName("tool_call")
    TOOL_CALL,

    @SerialName("image_file")
    IMAGE_FILE,

    @SerialName("image_url")
    IMAGE_URL,

    @SerialName("video_url")
    VIDEO_URL,

    @SerialName("input_audio")
    INPUT_AUDIO,

    @SerialName("agent_update")
    AGENT_UPDATE,

    @SerialName("error")
    ERROR,
}

@Serializable
enum class StepType {
    @SerialName("tool_calls")
    TOOL_CALLS,

    @SerialName("message_creation")
    MESSAGE_CREATION,
}

@Serializable
enum class ToolCallType {
    @SerialName("function")
    FUNCTION,

    @SerialName("retrieval")
    RETRIEVAL,

    @SerialName("file_search")
    FILE_SEARCH,

    @SerialName("code_interpreter")
    CODE_INTERPRETER,

    @SerialName("tool_call")
    TOOL_CALL,
}

@Serializable
enum class FeedbackRating {
    @SerialName("thumbsUp")
    THUMBS_UP,

    @SerialName("thumbsDown")
    THUMBS_DOWN,
}

@Serializable
enum class Provider {
    @SerialName("openAI")
    OPENAI,

    @SerialName("anthropic")
    ANTHROPIC,

    @SerialName("azureOpenAI")
    AZURE,

    @SerialName("google")
    GOOGLE,

    @SerialName("vertexai")
    VERTEXAI,

    @SerialName("bedrock")
    BEDROCK,

    @SerialName("mistralai")
    MISTRALAI,

    @SerialName("mistral")
    MISTRAL,

    @SerialName("deepseek")
    DEEPSEEK,

    @SerialName("moonshot")
    MOONSHOT,

    @SerialName("openrouter")
    OPENROUTER,

    @SerialName("xai")
    XAI,
}
