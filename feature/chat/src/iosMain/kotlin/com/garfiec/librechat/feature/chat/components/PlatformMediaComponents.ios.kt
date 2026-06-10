package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.ui.platform.currentTopmostViewController
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cValue
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import platform.AVFAudio.AVAudioPlayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.timeControlStatus
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
actual fun AudioContent(
    data: String?,
    format: String?,
    modifier: Modifier,
) {
    if (data == null) {
        Text(stringResource(Res.string.audio_no_data), modifier = modifier)
        return
    }
    // Decode base64 audio data off the composition thread, then play via AVAudioPlayer.
    // `decodeState`: null = still decoding, Result wraps the decoded NSData (or null on failure).
    val decodeState by produceState<Result<NSData?>?>(initialValue = null, data) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                NSData.create(base64EncodedString = data, options = 0u)
            }.onFailure { Logger.e(it) { "Failed to decode audio data" } }
        }
    }

    // While decoding, render nothing to avoid flashing a failure message.
    val decodeResult = decodeState ?: return
    val audioData = decodeResult.getOrNull()
    if (audioData == null) {
        Text(stringResource(Res.string.audio_decode_failed), modifier = modifier)
        return
    }

    val player = remember(audioData) {
        try {
            AVAudioPlayer(data = audioData, error = null)?.apply {
                prepareToPlay()
            }
        } catch (e: Exception) {
            Logger.e(e) { "Failed to create AVAudioPlayer" }
            null
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    DisposableEffect(player) {
        onDispose {
            player?.stop()
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying && player != null) {
            val duration = player.duration
            if (duration > 0.0) {
                progress = (player.currentTime / duration).toFloat()
            }
            delay(250L)
            if (!player.isPlaying()) {
                isPlaying = false
                progress = 0f
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (player == null) return@IconButton
                    if (isPlaying) {
                        player.pause()
                        isPlaying = false
                    } else {
                        player.play()
                        isPlaying = true
                    }
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) Res.string.cd_pause else Res.string.cd_play),
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                val durationSec = player?.duration?.toInt() ?: 0
                val currentSec = (progress * durationSec).toInt()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(
                        text = formatDurationIos(currentSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = formatDurationIos(durationSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun AudioContentPlayer(
    audioUrl: String,
    modifier: Modifier,
) {
    val player = remember(audioUrl) {
        val url = NSURL.URLWithString(audioUrl) ?: return@remember null
        try {
            AVPlayer(uRL = url)
        } catch (e: Exception) {
            Logger.e(e) { "Failed to create AVPlayer for audio" }
            null
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    DisposableEffect(player) {
        onDispose {
            player?.pause()
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying && player != null) {
            val duration = player.currentItem?.duration?.let { CMTimeGetSeconds(it) } ?: 0.0
            val current = CMTimeGetSeconds(player.currentTime())
            if (duration > 0.0) {
                progress = (current / duration).toFloat()
            }
            delay(250L)
            if (player.timeControlStatus != AVPlayerTimeControlStatusPlaying) {
                isPlaying = false
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (player == null) return@IconButton
                    if (isPlaying) {
                        player.pause()
                        isPlaying = false
                    } else {
                        player.play()
                        isPlaying = true
                    }
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) Res.string.cd_pause else Res.string.cd_play),
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
actual fun AudioContentPlayerFromBytes(
    audioBytes: ByteArray,
    modifier: Modifier,
) {
    // Write bytes to a temp file off the composition thread, then play via AudioContentPlayer.
    val tempPath by produceState<String?>(initialValue = null, audioBytes) {
        value = withContext(Dispatchers.Default) {
            val path = NSTemporaryDirectory() + "audio_${audioBytes.hashCode()}.mp3"
            val nsData = audioBytes.usePinned { pinned ->
                NSData.create(
                    bytes = pinned.addressOf(0),
                    length = audioBytes.size.toULong(),
                )
            }
            nsData.writeToFile(path, atomically = true)
            path
        }
    }

    val path = tempPath ?: return

    DisposableEffect(path) {
        onDispose {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    AudioContentPlayer(
        audioUrl = path,
        modifier = modifier,
    )
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun VideoContent(
    url: String,
    modifier: Modifier,
) {
    val nsUrl = remember(url) { NSURL.URLWithString(url) }
    val player = remember(url) {
        nsUrl?.let { AVPlayer(uRL = it) }
    }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        onDispose {
            player?.pause()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (player != null) {
            UIKitView(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                factory = {
                    val view = UIView()
                    val playerLayer = AVPlayerLayer.playerLayerWithPlayer(player)
                    playerLayer.videoGravity = AVLayerVideoGravityResizeAspect
                    view.layer.addSublayer(playerLayer)
                    view
                },
                update = { view ->
                    val layer = view.layer.sublayers?.firstOrNull() as? AVPlayerLayer
                    layer?.setFrame(view.bounds)
                },
            )
        }

        if (!isPlaying) {
            IconButton(
                onClick = {
                    player?.play()
                    isPlaying = true
                },
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(Res.string.cd_play_video),
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
actual fun VideoContentPlayer(
    videoUrl: String,
    modifier: Modifier,
) {
    VideoContent(url = videoUrl, modifier = modifier)
}

@Composable
actual fun LatexBlock(
    latex: String,
    modifier: Modifier,
    useKatex: Boolean,
) {
    KatexWebView(
        latex = latex,
        displayMode = true,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        initialHeight = 80.dp,
    )
}

@Composable
actual fun LatexInline(
    latex: String,
    modifier: Modifier,
    useKatex: Boolean,
) {
    KatexWebView(
        latex = latex,
        displayMode = false,
        modifier = modifier,
        initialHeight = 24.dp,
    )
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun KatexWebView(
    latex: String,
    displayMode: Boolean,
    modifier: Modifier,
    initialHeight: Dp,
) {
    val bgComposeColor = MaterialTheme.colorScheme.background
    val isDarkTheme = (bgComposeColor.red * 0.299 + bgComposeColor.green * 0.587 + bgComposeColor.blue * 0.114) < 0.5
    val textColor = if (isDarkTheme) "#e0e0e0" else "#1a1a1a"
    val bgColor = remember(bgComposeColor) { colorToCssHex(bgComposeColor) }
    val html = remember(latex, textColor, bgColor) {
        buildKatexHtml(latex, displayMode = displayMode, textColor = textColor, bgColor = bgColor)
    }

    var contentHeight by remember { mutableStateOf(initialHeight) }

    UIKitView(
        modifier = modifier
            .height(contentHeight),
        factory = {
            val config = WKWebViewConfiguration()
            val webView = WKWebView(frame = cValue { }, configuration = config)
            val nativeBg = UIColor(
                red = bgComposeColor.red.toDouble(),
                green = bgComposeColor.green.toDouble(),
                blue = bgComposeColor.blue.toDouble(),
                alpha = 1.0,
            )
            webView.setBackgroundColor(nativeBg)
            webView.scrollView.setBackgroundColor(nativeBg)
            webView.scrollView.setScrollEnabled(false)
            webView.navigationDelegate = object : NSObject(), WKNavigationDelegateProtocol {
                override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
                    webView.evaluateJavaScript("document.body.scrollHeight") { result, _ ->
                        val height = (result as? Number)?.toDouble()
                        if (height != null && height > 0) {
                            contentHeight = height.dp
                        }
                    }
                }
            }
            webView.loadHTMLString(html, baseURL = NSURL.URLWithString("https://cdn.jsdelivr.net"))
            webView
        },
        update = { webView ->
            webView.loadHTMLString(html, baseURL = NSURL.URLWithString("https://cdn.jsdelivr.net"))
        },
    )
}

private fun colorToCssHex(color: Color): String {
    val r = (color.red * 255).toInt()
    val g = (color.green * 255).toInt()
    val b = (color.blue * 255).toInt()
    return "#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}"
}

private fun buildKatexHtml(latex: String, displayMode: Boolean, textColor: String, bgColor: String): String {
    val escapedLatex = latex
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("\"", "\\\"")
        .replace("<", "\\u003c")
        .replace("\n", "\\n")
    val displayModeJs = if (displayMode) "true" else "false"
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.21/dist/katex.min.css">
            <style>
                body {
                    margin: 0; padding: 0;
                    display: flex;
                    justify-content: ${if (displayMode) "center" else "flex-start"};
                    align-items: center;
                    background: $bgColor;
                    color: $textColor;
                    overflow: hidden;
                    min-height: 100%;
                }
                #output { font-size: 16px; }
                .katex { color: $textColor; }
                .error { color: #f44336; font-family: monospace; font-size: 12px; padding: 4px; }
            </style>
        </head>
        <body>
            <div id="output"></div>
            <script src="https://cdn.jsdelivr.net/npm/katex@0.16.21/dist/katex.min.js"></script>
            <script>
                try {
                    katex.render("$escapedLatex", document.getElementById('output'), {
                        displayMode: $displayModeJs,
                        throwOnError: false,
                        trust: true,
                        strict: false
                    });
                } catch(e) {
                    document.getElementById('output').innerHTML = '<div class="error">' + e.message + '</div>';
                }
            </script>
        </body>
        </html>
    """.trimIndent()
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun MermaidDiagram(
    code: String,
    modifier: Modifier,
) {
    var showCode by remember { mutableStateOf(false) }
    var showFullscreen by remember { mutableStateOf(false) }

    val isDarkTheme = MaterialTheme.colorScheme.surface.let { color ->
        val r = (color.red * 255).toInt()
        val g = (color.green * 255).toInt()
        val b = (color.blue * 255).toInt()
        (r * 0.299 + g * 0.587 + b * 0.114) < 128
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.label_mermaid),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { showCode = !showCode }) {
                Icon(
                    imageVector = if (showCode) Icons.Default.Image else Icons.Default.Code,
                    contentDescription = stringResource(if (showCode) Res.string.cd_show_diagram else Res.string.cd_view_code),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(if (showCode) Res.string.label_diagram else Res.string.label_code),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = { showFullscreen = true }) {
                Icon(
                    imageVector = Icons.Default.Fullscreen,
                    contentDescription = stringResource(Res.string.cd_fullscreen),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Content
        AnimatedContent(
            targetState = showCode,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "mermaid_content",
        ) { isCode ->
            if (isCode) {
                CodeBlock(code = code, language = "mermaid")
            } else {
                MermaidWKWebView(
                    code = code,
                    isDarkTheme = isDarkTheme,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .padding(8.dp),
                )
            }
        }
    }

    if (showFullscreen) {
        Dialog(
            onDismissRequest = { showFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                MermaidWKWebView(
                    code = code,
                    isDarkTheme = isDarkTheme,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
                IconButton(
                    onClick = { showFullscreen = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(Res.string.cd_close_fullscreen),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun MermaidWKWebView(
    code: String,
    isDarkTheme: Boolean,
    modifier: Modifier,
) {
    val escapedCode = remember(code) {
        code.replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")
            .replace("\"", "\\\"")
            .replace("<", "\\u003c")
            .replace("\n", "\\n")
    }
    val theme = if (isDarkTheme) "dark" else "default"
    val html = remember(escapedCode, theme) { buildMermaidHtml(escapedCode, theme) }

    UIKitView(
        modifier = modifier,
        factory = {
            val config = WKWebViewConfiguration()
            val webView = WKWebView(frame = cValue { }, configuration = config)
            webView.setOpaque(false)
            webView.loadHTMLString(html, baseURL = NSURL.URLWithString("https://cdn.jsdelivr.net"))
            webView
        },
        update = { webView ->
            webView.loadHTMLString(html, baseURL = NSURL.URLWithString("https://cdn.jsdelivr.net"))
        },
    )
}

private fun buildMermaidHtml(escapedCode: String, theme: String): String {
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body {
                    margin: 0; padding: 8px;
                    display: flex; justify-content: center; align-items: center;
                    background: transparent; overflow: hidden;
                }
                .mermaid { width: 100%; }
                .mermaid svg { max-width: 100%; height: auto; }
                .error { color: #f44336; font-family: monospace; font-size: 12px; padding: 8px; }
            </style>
        </head>
        <body>
            <div class="mermaid" id="diagram"></div>
            <script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
            <script>
                mermaid.initialize({ startOnLoad: false, theme: '$theme', securityLevel: 'strict', flowchart: { useMaxWidth: true } });
                try {
                    var code = "$escapedCode";
                    mermaid.render('rendered', code).then(function(result) {
                        document.getElementById('diagram').innerHTML = result.svg;
                    }).catch(function(err) {
                        document.getElementById('diagram').innerHTML = '<div class="error">Diagram error: ' + err.message + '</div>';
                    });
                } catch(e) {
                    document.getElementById('diagram').innerHTML = '<div class="error">Diagram error: ' + e.message + '</div>';
                }
            </script>
        </body>
        </html>
    """.trimIndent()
}

actual fun shareArtifact(title: String, content: String, language: String) {
    val viewController = currentTopmostViewController() ?: return
    val activityVC = UIActivityViewController(
        activityItems = listOf(content),
        applicationActivities = null,
    )
    viewController.presentViewController(activityVC, animated = true, completion = null)
}

private fun formatDurationIos(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
