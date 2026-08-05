package com.garfiec.librechat.core.data.prefetch

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException

/**
 * Warms Coil's disk cache, which the app configures on Android only.
 *
 * Goes through the singleton loader the UI reads from, so a warmed image is a cache hit for the
 * composable that later asks for it — a private loader would fill a cache nothing looks in.
 */
class CoilAttachmentWarmer(
    private val context: Context,
) : AttachmentWarmer {

    override val isSupported: Boolean = true

    override suspend fun warm(url: String) {
        val loader = SingletonImageLoader.get(context)
        try {
            loader.execute(
                ImageRequest.Builder(context)
                    .data(url)
                    // Decoding to a bitmap costs memory the user gets no benefit from — nothing is on
                    // screen. The disk cache entry is the whole point, and it is written either way.
                    .build(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Opportunistic: a missing or expired attachment must not disturb the pass.
        }
    }
}
