package com.garfiec.librechat.feature.settings.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import org.koin.mp.KoinPlatformTools

actual fun openUri(uri: String): Boolean {
    val context = KoinPlatformTools.defaultContext().get().get<Context>()
    // Android 11+ package visibility hides other apps from resolveActivity(), but startActivity()
    // is still resolved by the system, so catching ActivityNotFoundException -- not pre-checking --
    // is the way to detect "no authenticator installed" without a <queries> manifest entry.
    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
