package com.garfiec.librechat.shortcuts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.garfiec.librechat.MainActivity
import com.garfiec.librechat.R
import com.garfiec.librechat.core.model.ModelRef

/**
 * Publishes the user's most-used models as dynamic home-screen app shortcuts (long-press the app
 * icon). Each shortcut deep-links to `librechat://model?endpoint=…&model=…`, which opens a new chat
 * pre-selected on that model. Driven by [com.garfiec.librechat.core.data.datastore.SettingsDataStore.topUsedModels];
 * [publish] fully replaces the set, so an empty list (logged out) clears them.
 */
object ModelShortcuts {

    /** Launchers surface at most ~4 static+dynamic shortcuts; keep within the platform max. */
    val maxCount: Int
        get() = MAX_SHORTCUTS

    fun publish(context: Context, models: List<ModelRef>) {
        val capped = models.take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(MAX_SHORTCUTS))
        val shortcuts = capped.mapIndexed { index, ref ->
            val uri = Uri.parse(
                "librechat://model?endpoint=${Uri.encode(ref.endpoint)}&model=${Uri.encode(ref.model)}",
            )
            val intent = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
            ShortcutInfoCompat.Builder(context, shortcutId(ref))
                .setShortLabel(ref.model)
                .setLongLabel(ref.model)
                .setRank(index)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(intent)
                .build()
        }
        // Replaces the whole dynamic set; an empty list clears every model shortcut.
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    // Stable per-model id so the same model keeps its shortcut identity across republishes.
    private fun shortcutId(ref: ModelRef) = "model:${ref.endpoint}:${ref.model}"

    private const val MAX_SHORTCUTS = 4
}
