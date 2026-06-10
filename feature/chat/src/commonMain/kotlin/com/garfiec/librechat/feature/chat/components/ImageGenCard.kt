package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.pow
import kotlin.random.Random

data class ImageGenResult(
    val imageUrl: String? = null,
    val prompt: String? = null,
    val isGenerating: Boolean = false,
    /** Image-gen quality ("low" | "medium" | "high"). Tunes the faux-progress
     *  duration while [isGenerating], matching the web client. */
    val quality: String? = null,
)

/** Renders a DALL-E / image generation result card. [ImageGenResult.isGenerating] drives spinner vs image display. */
@Composable
fun ImageGenCard(
    result: ImageGenResult,
    modifier: Modifier = Modifier,
    showDescription: Boolean = true,
) {
    val openMedia = LocalChatMediaViewer.current

    // Faux progress, ported from web's OpenAIImageGen (the server sends no real
    // progress). Ticks 0.1 → 0.9 over a quality-tuned duration with jitter, then
    // snaps to done the moment the real image (imageUrl) lands and isGenerating flips.
    var fauxProgress by remember { mutableFloatStateOf(0.1f) }
    LaunchedEffect(result.isGenerating, result.quality) {
        if (!result.isGenerating) {
            fauxProgress = 1f
            return@LaunchedEffect
        }
        fauxProgress = 0.1f
        val baseDuration = when (result.quality?.lowercase()) {
            "low" -> 10_000
            "high" -> 50_000
            else -> 20_000
        }
        val jitter = (baseDuration * 0.3f).toInt().coerceAtLeast(1)
        val totalDuration = Random.nextInt(jitter) + baseDuration
        val updateInterval = 200L
        val totalSteps = (totalDuration / updateInterval).toInt().coerceAtLeast(1)
        var step = 0
        while (step < totalSteps) {
            delay(updateInterval)
            step++
            val ratio = step.toFloat() / totalSteps
            val mapRatio = if (ratio < 0.8f) {
                ratio.pow(1.1f)
            } else {
                val sub = (ratio - 0.8f) / 0.2f
                0.8f + (1f - (1f - sub).pow(2)) * 0.2f
            }
            fauxProgress = (0.1f + mapRatio * 0.8f).coerceAtMost(0.9f)
        }
        fauxProgress = 0.9f
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(if (result.isGenerating) Res.string.generating_image else Res.string.image_generated),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (result.isGenerating) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${(fauxProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val imageUrl = result.imageUrl
            if (imageUrl != null) {
                SubcomposeAsyncImage(
                    model = imageUrl,
                    contentDescription = result.prompt ?: stringResource(Res.string.cd_generated_image),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { openMedia(imageUrl) }
                        .semantics { role = Role.Image },
                    loading = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    RoundedCornerShape(8.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    },
                    error = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    RoundedCornerShape(8.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.BrokenImage,
                                contentDescription = stringResource(Res.string.cd_failed_to_load_generated_image),
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            } else if (result.isGenerating) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    PixelRevealCard(
                        progress = fauxProgress,
                        modifier = Modifier.fillMaxSize(),
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                        ),
                    )
                }
            }

            val prompt = result.prompt
            if (showDescription && !prompt.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
