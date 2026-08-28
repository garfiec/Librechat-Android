package com.garfiec.librechat.feature.chat.components.web

import co.touchlab.kermit.Logger
import com.garfiec.librechat.feature.chat.resources.Res
import kotlin.concurrent.Volatile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.WebKit.WKWebView

private const val CACHE_DIR_NAME = "webassets"
private const val MANIFEST_NAME = ".manifest"

private val materializeLock = Mutex()

@Volatile
private var cachedBaseUrl: String? = null

/**
 * Copies the vendored assets out of the framework bundle into the caches directory,
 * once, and returns that directory's URL.
 *
 * The copy exists solely to satisfy WKWebView. Handing it HTML through `loadHTMLString`
 * leaves the page unable to load *any* local subresource, whatever base URL is supplied;
 * the working route is `loadFileURL(page, allowingReadAccessTo: dir)`, and that requires
 * the page itself to live inside the granted directory. The bundle is read-only, so the
 * page cannot be written next to the assets there — hence a writable copy.
 *
 * The manifest file records which assets were copied and, through
 * [VendoredWebAssets.FINGERPRINT], what was in them. An app update that repins a library
 * changes the fingerprint, and the mismatch forces a fresh copy; without it a stale cache
 * would keep serving the previous version's scripts indefinitely.
 */
@OptIn(ExperimentalResourceApi::class)
internal actual suspend fun webAssetBaseUrl(): String {
    cachedBaseUrl?.let { cached ->
        // The memo is re-validated rather than trusted: Library/Caches is purgeable,
        // so iOS can delete the copy out from under a running app under disk pressure.
        // Returning the remembered path after that would hand every WebView a base URL
        // whose scripts have all vanished — which renders blank, silently, for the rest
        // of the process's life.
        if (NSFileManager.defaultManager.fileExistsAtPath(manifestPath())) return cached
        cachedBaseUrl = null
    }
    return materializeLock.withLock {
        cachedBaseUrl ?: materialize().also { cachedBaseUrl = it }
    }
}

private fun cacheRoot(): String {
    val caches = NSSearchPathForDirectoriesInDomains(
        NSCachesDirectory,
        NSUserDomainMask,
        true,
    ).firstOrNull() as? String ?: error("no caches directory")
    return "$caches/$CACHE_DIR_NAME"
}

private fun manifestPath(): String = "${cacheRoot()}/$MANIFEST_NAME"

@OptIn(ExperimentalResourceApi::class, ExperimentalForeignApi::class)
private suspend fun materialize(): String = withContext(Dispatchers.IO) {
    val fm = NSFileManager.defaultManager
    val root = cacheRoot()
    val manifestPath = manifestPath()
    // The fingerprint leads, because the file list alone cannot detect a repin: the pinned
    // filenames carry no version, so moving a library to a build that ships the same names
    // leaves the list identical and the stale copy would be served for good.
    val expected = VendoredWebAssets.FINGERPRINT + "\n" + VendoredWebAssets.FILES.joinToString("\n")

    val current = NSString.stringWithContentsOfFile(manifestPath, NSUTF8StringEncoding, null)
    if (current == expected) return@withContext root.asDirectoryUrl()

    // Any mismatch — first run, a repin, a half-finished copy — starts over. Copying
    // 7 MB is cheap next to reasoning about which files a partial copy left behind.
    fm.removeItemAtPath(root, null)
    fm.createDirectoryAtPath(root, withIntermediateDirectories = true, attributes = null, error = null)

    for (relative in VendoredWebAssets.FILES) {
        val bytes = Res.readBytes("${VendoredWebAssets.ROOT}/$relative")
        val target = "$root/$relative"
        val parent = target.substringBeforeLast('/')
        fm.createDirectoryAtPath(parent, withIntermediateDirectories = true, attributes = null, error = null)
        if (!bytes.toNSData().writeToFile(target, atomically = true)) {
            Logger.w { "Failed to write vendored web asset $relative" }
        }
    }

    // Written last: the manifest is the "copy completed" signal, so a crash midway
    // leaves it absent and the next launch redoes the copy rather than trusting it.
    expected.writeUtf8To(manifestPath)
    root.asDirectoryUrl()
}

/**
 * Loads a generated page against the vendored assets.
 *
 * `loadHTMLString` is not usable here whatever base URL it is given: a page loaded that
 * way cannot pull in local subresources, so every `<script src="katex/…">` would silently
 * do nothing. The page is written next to the assets instead and loaded through
 * `loadFileURL`, whose read-access grant covers the whole directory.
 *
 * Pages are named by content hash, so re-rendering the same artifact reuses one file and
 * two live WebViews cannot overwrite each other's page. They accumulate in the caches
 * directory, bounded by how many distinct documents have been rendered, and are cleared
 * wholesale whenever [materialize] re-runs.
 *
 * @param baseUrl the directory URL from [webAssetBaseUrl].
 */
@OptIn(ExperimentalForeignApi::class)
internal fun WKWebView.loadVendoredHtml(html: String, baseUrl: String) {
    val dirUrl = NSURL.URLWithString(baseUrl)
    val dirPath = dirUrl?.path
    if (dirPath == null) {
        Logger.w { "Vendored web assets unavailable; artifact page not loaded" }
        return
    }
    val pagePath = "$dirPath/page-${html.hashCode().toUInt()}.html"
    // Written every time rather than only when absent: a hash collision would otherwise
    // serve the wrong document, and rewriting a few KB costs nothing next to a page load.
    html.writeUtf8To(pagePath)
    loadFileURL(
        NSURL.fileURLWithPath(pagePath),
        allowingReadAccessToURL = NSURL.fileURLWithPath(dirPath, isDirectory = true),
    )
}

/**
 * Writes UTF-8 text through [NSData] rather than `(this as NSString).writeToFile(…)`.
 * That cast compiles with "this cast can never succeed" and depends on Kotlin/Native's
 * ObjC bridging holding for a class the compiler does not believe the receiver is.
 */
@OptIn(ExperimentalForeignApi::class)
private fun String.writeUtf8To(path: String) {
    if (!encodeToByteArray().toNSData().writeToFile(path, atomically = true)) {
        Logger.w { "Failed to write $path" }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun String.asDirectoryUrl(): String =
    NSURL.fileURLWithPath(this, isDirectory = true).absoluteString ?: "file://$this/"

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
