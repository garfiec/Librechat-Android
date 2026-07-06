package com.garfiec.librechat.feature.chat.components.artifact

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.garfiec.librechat.core.model.ArtifactShortcut
import com.garfiec.librechat.core.model.displayGlyph
import com.garfiec.librechat.core.model.displayLabel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fully-qualified launcher activity — referenced by string because :app isn't visible from here. */
private const val MAIN_ACTIVITY = "com.garfiec.librechat.MainActivity"

/** Adaptive-icon canvas edge in px; sized for xxhdpi so the launcher downscales rather than upscales. */
private const val ICON_SIZE_PX = 324

private const val ACTION_PIN_CONFIRMED = "com.garfiec.librechat.artifact.PIN_CONFIRMED"
private const val EXTRA_SHORTCUT_ID = "artifact_shortcut_id"

/** A snapshot dispatched to the pin prompt, plus how to persist it once the launcher confirms. */
private class PendingPin(val shortcut: ArtifactShortcut, val save: suspend (ArtifactShortcut) -> Unit)

/**
 * Snapshots dispatched to the system pin prompt but not yet confirmed, keyed by shortcut id. A row is
 * persisted (via its paired [PendingPin.save]) only when the launcher's success callback fires — so
 * declining the prompt (which fires no callback) writes no orphan row. Because a decline is silent,
 * its entry can't be removed on confirmation; a timeout ([PIN_CONFIRM_TIMEOUT_MS]) evicts it so an
 * unconfirmed snapshot doesn't retain the artifact content for the life of the process.
 */
private val pendingPins = ConcurrentHashMap<String, PendingPin>()

/** App-lifetime scope so a confirmation arriving after the originating screen is gone still persists. */
private val pinScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private val pinReceiverRegistered = AtomicBoolean(false)

/** Monotonic request code so each in-flight pin gets a distinct PendingIntent (see [requestPinArtifactShortcut]). */
private val pinRequestCode = AtomicInteger(0)

/** A pin prompt is answered in seconds; evict an unconfirmed snapshot well after that so a silent decline can't leak it. */
private const val PIN_CONFIRM_TIMEOUT_MS = 5 * 60 * 1000L

/** Registers (once) the receiver for our own pin-confirmation broadcast on the application context. */
private fun ensurePinReceiver(appContext: Context) {
    if (!pinReceiverRegistered.compareAndSet(false, true)) return
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getStringExtra(EXTRA_SHORTCUT_ID) ?: return
            val pending = pendingPins.remove(id) ?: return
            pinScope.launch { pending.save(pending.shortcut) }
        }
    }
    ContextCompat.registerReceiver(
        appContext,
        receiver,
        IntentFilter(ACTION_PIN_CONFIRMED),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
}

/**
 * Requests the system pin-shortcut prompt for an already-built [ArtifactShortcut] snapshot, persisting
 * it via [save] only if the launcher actually places the icon (a success callback). Declining the
 * prompt writes nothing, so no orphan snapshot is left behind. Returns `false` (without prompting) when
 * the launcher can't pin at all. The bitmap render runs off the main thread.
 */
suspend fun requestPinArtifactShortcut(
    context: Context,
    shortcut: ArtifactShortcut,
    save: suspend (ArtifactShortcut) -> Unit,
): Boolean {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
    val appContext = context.applicationContext
    ensurePinReceiver(appContext)

    val intent = Intent(Intent.ACTION_VIEW, "librechat://artifact/${shortcut.id}".toUri())
        // Explicit component so the launcher routes straight to us regardless of other VIEW handlers.
        .setClassName(context.packageName, MAIN_ACTIVITY)

    val icon = withContext(Dispatchers.Default) { renderIcon(shortcut) }
    val info = ShortcutInfoCompat.Builder(context, shortcut.id)
        .setShortLabel(shortcut.displayLabel)
        .setIcon(IconCompat.createWithAdaptiveBitmap(icon))
        .setIntent(intent)
        .build()

    pendingPins[shortcut.id] = PendingPin(shortcut, save)
    // Fresh request code per pin: the callback intents are filterEquals-equal (same action, differing
    // only by extras), so a shared code would let FLAG_UPDATE_CURRENT merge two in-flight pins and
    // clobber the earlier one's id extra. A distinct code keeps each PendingIntent separate.
    val callback = PendingIntent.getBroadcast(
        appContext,
        pinRequestCode.getAndIncrement(),
        Intent(ACTION_PIN_CONFIRMED)
            .setPackage(appContext.packageName)
            .putExtra(EXTRA_SHORTCUT_ID, shortcut.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val dispatched = ShortcutManagerCompat.requestPinShortcut(context, info, callback.intentSender)
    if (dispatched) {
        // A declined prompt fires no callback, so evict the unconfirmed snapshot rather than leak it.
        pinScope.launch {
            delay(PIN_CONFIRM_TIMEOUT_MS)
            pendingPins.remove(shortcut.id)
        }
    } else {
        pendingPins.remove(shortcut.id)
    }
    return dispatched
}

/** Snapshots the artifact primitives plus the user-chosen label/emoji into a storable model. */
fun buildArtifactShortcut(
    id: String,
    label: String,
    emoji: String?,
    artifact: Artifact,
): ArtifactShortcut = ArtifactShortcut(
    id = id,
    label = label,
    emoji = emoji,
    identifier = artifact.identifier,
    type = artifact.type,
    title = artifact.title,
    language = artifact.language,
    content = artifact.content,
    version = artifact.version,
    createdAt = Clock.System.now().toEpochMilliseconds(),
)

/**
 * Renders the launcher icon at runtime (the repo ships no per-artifact drawables): a solid full-bleed
 * background with a centered glyph — the user's emoji when set, otherwise a type-representative emoji.
 * Full-bleed so the launcher's adaptive mask can crop to any shape.
 */
private fun renderIcon(shortcut: ArtifactShortcut): Bitmap {
    val glyph = shortcut.displayGlyph
    val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(ARTIFACT_ICON_BACKGROUND_ARGB.toInt())

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        // Kept within the adaptive safe zone (~66/108 of the canvas) so the mask never clips the glyph.
        textSize = ICON_SIZE_PX * 0.42f
    }
    // Vertically center on the text's own metrics, not the baseline.
    val centerY = ICON_SIZE_PX / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(glyph, ICON_SIZE_PX / 2f, centerY, textPaint)
    return bitmap
}
