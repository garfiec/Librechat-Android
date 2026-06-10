package com.garfiec.librechat.feature.chat.components.artifact

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.garfiec.librechat.core.ui.media.sweepStaleFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shares artifacts via FileProvider temp file + system share sheet. Falls back to plain text.
 * FileProvider authority must match the app's declared authority in AndroidManifest.
 * Temp files are written to cacheDir/artifacts/ and cleaned up after sharing.
 */
object ArtifactDownloadHelper {

    /** How long to wait before deleting the shared file (30s gives the share target time to read). */
    private const val CLEANUP_DELAY_MS = 30_000L

    /** Files older than this are considered stale and cleaned up opportunistically. */
    private const val STALE_THRESHOLD_MS = 60_000L

    suspend fun share(context: Context, artifact: Artifact) {
        val intent = try {
            shareViaFile(context, artifact)
        } catch (_: Exception) {
            shareAsText(artifact)
        }
        context.startActivity(Intent.createChooser(intent, "Share artifact"))
    }

    private suspend fun shareViaFile(context: Context, artifact: Artifact): Intent {
        val extension = extensionForArtifact(artifact)
        val fileName = sanitizeFileName(artifact.title) + extension
        val dir = File(context.cacheDir, "artifacts")
        val file = File(dir, fileName)
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            file.writeText(artifact.content)
        }

        // Schedule cleanup: delete this file and any stale artifacts after a delay
        scheduleCleanup(file, dir)

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeForExtension(extension)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, artifact.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Schedules deletion of the shared [file] and any stale files in [dir] after a delay.
     * This gives the share target enough time to read the file before it is removed.
     */
    private fun scheduleCleanup(file: File, dir: File) {
        Handler(Looper.getMainLooper()).postDelayed({
            file.delete()
            sweepStaleFiles(dir, STALE_THRESHOLD_MS)
        }, CLEANUP_DELAY_MS)
    }

    private fun shareAsText(artifact: Artifact): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, artifact.title)
            putExtra(Intent.EXTRA_TEXT, artifact.content)
        }
    }

    private fun extensionForArtifact(artifact: Artifact): String {
        artifact.language?.let { lang ->
            LANGUAGE_EXTENSIONS[lang.lowercase()]?.let { return it }
        }
        return when {
            artifact.type.contains("mermaid") -> ".mmd"
            artifact.type.contains("html") || artifact.type.contains("code-html") -> ".html"
            artifact.type.contains("react") -> ".jsx"
            artifact.type.contains("svg") -> ".svg"
            artifact.type.contains("css") -> ".css"
            artifact.type.contains("json") -> ".json"
            artifact.type.contains("xml") -> ".xml"
            artifact.type.contains("markdown") || artifact.type == "text/md" -> ".md"
            else -> ".txt"
        }
    }

    private fun mimeTypeForExtension(extension: String): String {
        return when (extension) {
            ".html" -> "text/html"
            ".svg" -> "image/svg+xml"
            ".mmd" -> "text/plain"
            ".jsx" -> "text/javascript"
            ".css" -> "text/css"
            ".json" -> "application/json"
            ".xml" -> "application/xml"
            ".md" -> "text/markdown"
            ".py" -> "text/x-python"
            ".js", ".ts", ".tsx", ".jsx" -> "text/javascript"
            ".kt", ".kts" -> "text/x-kotlin"
            ".java" -> "text/x-java-source"
            ".sh" -> "text/x-shellscript"
            else -> "text/plain"
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").take(100)
    }

    private val LANGUAGE_EXTENSIONS = mapOf(
        "python" to ".py",
        "javascript" to ".js",
        "typescript" to ".ts",
        "tsx" to ".tsx",
        "jsx" to ".jsx",
        "kotlin" to ".kt",
        "java" to ".java",
        "html" to ".html",
        "css" to ".css",
        "json" to ".json",
        "xml" to ".xml",
        "markdown" to ".md",
        "shell" to ".sh",
        "bash" to ".sh",
        "sql" to ".sql",
        "rust" to ".rs",
        "go" to ".go",
        "ruby" to ".rb",
        "swift" to ".swift",
        "c" to ".c",
        "cpp" to ".cpp",
        "csharp" to ".cs",
        "yaml" to ".yaml",
        "toml" to ".toml",
        "svg" to ".svg",
    )
}
