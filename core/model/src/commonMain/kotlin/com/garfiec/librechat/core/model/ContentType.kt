package com.garfiec.librechat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

    @SerialName("summary")
    SUMMARY,

    @SerialName("error")
    ERROR,
}
