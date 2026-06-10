package com.garfiec.librechat.feature.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Platform-specific audio player from base64-encoded data. */
@Composable
expect fun AudioContent(
    data: String?,
    format: String?,
    modifier: Modifier = Modifier,
)

/** Platform-specific audio player with seekbar and controls. */
@Composable
expect fun AudioContentPlayer(
    audioUrl: String,
    modifier: Modifier = Modifier,
)

/** Platform-specific audio player from raw bytes. */
@Composable
expect fun AudioContentPlayerFromBytes(
    audioBytes: ByteArray,
    modifier: Modifier = Modifier,
)

/** Platform-specific video player from URL. */
@Composable
expect fun VideoContent(
    url: String,
    modifier: Modifier = Modifier,
)

/** Platform-specific video player with loading indicator. */
@Composable
expect fun VideoContentPlayer(
    videoUrl: String,
    modifier: Modifier = Modifier,
)

/** Platform-specific LaTeX rendering. */
@Composable
expect fun LatexBlock(
    latex: String,
    modifier: Modifier = Modifier,
    useKatex: Boolean = false,
)

/** Platform-specific LaTeX inline rendering. */
@Composable
expect fun LatexInline(
    latex: String,
    modifier: Modifier = Modifier,
    useKatex: Boolean = false,
)

/** Platform-specific Mermaid diagram renderer. */
@Composable
expect fun MermaidDiagram(
    code: String,
    modifier: Modifier = Modifier,
)

/** Platform-specific artifact download/share helper. */
expect fun shareArtifact(title: String, content: String, language: String)
