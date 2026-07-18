package com.garfiec.librechat.feature.files

import androidx.compose.runtime.Immutable

@Immutable
data class FileDisplayData(
    val fileId: String,
    val filename: String,
    val type: String,
    val formattedSize: String,
    val createdAt: String?,
    val previewUrl: String?,
)

@Immutable
data class FilePreviewDisplayData(
    val fileId: String,
    val filename: String,
    val type: String,
    val formattedSize: String,
    val createdAt: String?,
    val source: String?,
    val userId: String?,
)
