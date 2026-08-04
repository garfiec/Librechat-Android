package com.garfiec.librechat.feature.chat.components

import android.annotation.SuppressLint
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import co.touchlab.kermit.Logger
import com.garfiec.librechat.feature.chat.components.web.safelyDestroyWebView
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun MermaidDiagram(
    code: String,
    modifier: Modifier,
) {
    var showCode by remember { mutableStateOf(false) }
    var showFullscreen by remember { mutableStateOf(false) }

    val isDarkTheme = MaterialTheme.colorScheme.surface.toArgb().let { argb ->
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        (r * 0.299 + g * 0.587 + b * 0.114) < 128
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        // Header
        DisableSelection {
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
                TextButton(
                    onClick = { showCode = !showCode },
                ) {
                    Icon(
                        imageVector = if (showCode) Icons.Default.Image else Icons.Default.Code,
                        contentDescription = stringResource(if (showCode) Res.string.cd_show_diagram else Res.string.cd_view_code),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(if (showCode) Res.string.label_diagram else Res.string.code),
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
        }

        // Content
        AnimatedContent(
            targetState = showCode,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "mermaid_content",
        ) { isCode ->
            if (isCode) {
                CodeBlock(
                    code = code,
                    language = "mermaid",
                )
            } else {
                MermaidWebView(
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
                MermaidWebView(
                    code = code,
                    isDarkTheme = isDarkTheme,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )
                IconButton(
                    onClick = { showFullscreen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MermaidWebView(
    code: String,
    isDarkTheme: Boolean,
    modifier: Modifier,
) {
    val bgColor = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()
    val escapedCode = remember(code) {
        code.replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")
            .replace("\"", "\\\"")
            .replace("<", "\\u003c")
            .replace("\n", "\\n")
    }
    val theme = if (isDarkTheme) "dark" else "default"

    val html = remember(escapedCode, theme) {
        buildMermaidHtml(escapedCode, theme)
    }

    var loadedHtml by remember { mutableStateOf("") }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(bgColor)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?,
                    ) {
                        handler?.cancel()
                        Logger.w { "SSL error in Mermaid WebView: ${error?.primaryError}" }
                    }
                }
                loadDataWithBaseURL(
                    "https://cdn.jsdelivr.net",
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
                loadedHtml = html
            }
        },
        update = { webView ->
            webView.setBackgroundColor(bgColor)
            if (html != loadedHtml) {
                webView.loadDataWithBaseURL(
                    "https://cdn.jsdelivr.net",
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
                loadedHtml = html
            }
        },
        onRelease = ::safelyDestroyWebView,
    )
}

private fun buildMermaidHtml(escapedCode: String, theme: String): String {
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' https://cdn.jsdelivr.net; style-src 'unsafe-inline'; img-src data:;">
            <style>
                html, body { max-width: 100%; }
                body {
                    margin: 0;
                    padding: 8px;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    background: transparent;
                    overflow: visible;
                }
                .mermaid {
                    width: 100%;
                }
                .mermaid svg {
                    max-width: 100%;
                    height: auto;
                }
                .error {
                    color: #f44336;
                    font-family: monospace;
                    font-size: 12px;
                    padding: 8px;
                }
            </style>
        </head>
        <body>
            <div class="mermaid" id="diagram">
            </div>
            <script>
                if (typeof structuredClone === 'undefined') {
                    window.structuredClone = function(obj) {
                        return JSON.parse(JSON.stringify(obj));
                    };
                }
                if (!Object.hasOwn) {
                    Object.hasOwn = function(obj, prop) {
                        return Object.prototype.hasOwnProperty.call(obj, prop);
                    };
                }
                if (typeof Array.prototype.at === 'undefined') {
                    Array.prototype.at = function(n) {
                        n = Math.trunc(n) || 0;
                        if (n < 0) n += this.length;
                        return this[n];
                    };
                }
                if (typeof String.prototype.at === 'undefined') {
                    String.prototype.at = function(n) {
                        n = Math.trunc(n) || 0;
                        if (n < 0) n += this.length;
                        return this[n];
                    };
                }
                if (typeof String.prototype.replaceAll === 'undefined') {
                    String.prototype.replaceAll = function(search, replacement) {
                        return this.split(search).join(replacement);
                    };
                }
            </script>
            <script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
            <script>
                mermaid.initialize({
                    startOnLoad: false,
                    theme: '$theme',
                    securityLevel: 'strict',
                    flowchart: { useMaxWidth: true },
                });
                try {
                    var code = "$escapedCode";
                    mermaid.render('rendered', code).then(function(result) {
                        document.getElementById('diagram').innerHTML = result.svg;
                    }).catch(function(err) {
                        document.getElementById('diagram').innerHTML =
                            '<div class="error">Diagram error: ' + err.message + '</div>';
                    });
                } catch(e) {
                    document.getElementById('diagram').innerHTML =
                        '<div class="error">Diagram error: ' + e.message + '</div>';
                }
            </script>
        </body>
        </html>
    """.trimIndent()
}
