package com.garfiec.librechat.feature.chat.components.artifact

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import co.touchlab.kermit.Logger
import com.garfiec.librechat.core.data.repository.ArtifactShortcutRepository
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.koin.compose.koinInject

/**
 * Android preview surface — a `WebView` hosting the artifact's rendered HTML.
 * The shell, header, selector, and code body are shared in the common
 * `ArtifactPanel`.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
actual fun ArtifactPreviewSurface(
    content: String,
    type: String,
    isDarkTheme: Boolean,
    modifier: Modifier,
) {
    val bgColor = MaterialTheme.colorScheme.surface.toArgb()
    var isLoading by remember { mutableStateOf(true) }

    val html = remember(content, type, isDarkTheme) {
        ArtifactWebContent.buildHtml(content, type, isDarkTheme, inline = false)
    }

    // Track what HTML is currently loaded to avoid reloading on every recomposition.
    // Without this, the isLoading state changes from WebView callbacks trigger
    // recompositions that call update, which reloads the page, creating an infinite loop
    // that prevents external scripts (like mermaid.js) from ever finishing.
    var loadedHtml by remember { mutableStateOf("") }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // Prevent touch events from propagating to the bottom sheet
                    setOnTouchListener { v, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false // let the WebView handle the event normally
                    }
                    setBackgroundColor(bgColor)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            if (newProgress >= 80) isLoading = false
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            isLoading = true
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                view?.loadDataWithBaseURL(
                                    null,
                                    buildErrorHtml(error?.description?.toString() ?: "Unknown error"),
                                    "text/html",
                                    "UTF-8",
                                    null,
                                )
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?,
                        ) {
                            handler?.cancel()
                            Logger.w { "SSL error in artifact WebView: ${error?.primaryError}" }
                        }
                    }
                    loadDataWithBaseURL("https://cdn.jsdelivr.net", html, "text/html", "UTF-8", null)
                    loadedHtml = html
                }
            },
            update = { webView ->
                webView.setBackgroundColor(bgColor)
                if (html != loadedHtml) {
                    webView.loadDataWithBaseURL("https://cdn.jsdelivr.net", html, "text/html", "UTF-8", null)
                    loadedHtml = html
                }
            },
        )

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
        }
    }
}

@Composable
actual fun rememberShareArtifact(): (Artifact) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context) {
        { artifact ->
            scope.launch { ArtifactDownloadHelper.share(context = context, artifact = artifact) }
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
@Composable
actual fun rememberAddArtifactToHomeScreen(): ((artifact: Artifact, label: String, emoji: String?) -> Unit)? {
    val context = LocalContext.current
    val repository = koinInject<ArtifactShortcutRepository>()
    val scope = rememberCoroutineScope()
    return remember(context, repository) {
        { artifact, label, emoji ->
            val shortcut = buildArtifactShortcut(
                id = Uuid.random().toString(),
                label = label,
                emoji = emoji,
                artifact = artifact,
            )
            // The snapshot is persisted from the launcher's pin-confirmation callback (see
            // requestPinArtifactShortcut), so declining the system prompt leaves no orphan row.
            scope.launch {
                requestPinArtifactShortcut(context, shortcut) { repository.save(it) }
            }
        }
    }
}

private fun buildErrorHtml(errorMessage: String): String {
    val safeMessage = ArtifactWebContent.escapeHtml(errorMessage)
    return """
        <!DOCTYPE html>
        <html>
        <head><meta name="viewport" content="width=device-width, initial-scale=1.0"></head>
        <body style="margin:0;padding:16px;font-family:sans-serif;background:#FFFBFE;">
            <div style="padding:16px;background:#B3261E22;border:1px solid #B3261E;border-radius:8px;">
                <strong>Failed to load preview</strong><br>
                <span style="font-size:13px;color:#666;">$safeMessage</span>
            </div>
        </body>
        </html>
    """.trimIndent()
}
