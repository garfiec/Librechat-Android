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

    // Mid-run steering part (upstream #14220, 0.8.8 line). Declared so a persisted message
    // content carrying a `steer` part deserializes instead of throwing — an unknown enum value
    // is NOT rescued by `ignoreUnknownKeys`, so its absence crashed conversation load on newer
    // servers. Forward-compat only; harmless on older backends that never emit it.
    @SerialName("steer")
    STEER,

    @SerialName("error")
    ERROR,
}
