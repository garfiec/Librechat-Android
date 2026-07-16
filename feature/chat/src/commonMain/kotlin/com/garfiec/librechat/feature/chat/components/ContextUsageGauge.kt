package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.usage.ContextUsage
import com.garfiec.librechat.core.model.usage.TokenUsage
import com.garfiec.librechat.core.ui.components.LowProfileDragHandle
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.resources.context_usage_free
import com.garfiec.librechat.feature.chat.resources.context_usage_input
import com.garfiec.librechat.feature.chat.resources.context_usage_label
import com.garfiec.librechat.feature.chat.resources.context_usage_messages
import com.garfiec.librechat.feature.chat.resources.context_usage_output
import com.garfiec.librechat.feature.chat.resources.context_usage_summary
import com.garfiec.librechat.feature.chat.resources.context_usage_system
import com.garfiec.librechat.feature.chat.resources.context_usage_tools
import com.garfiec.librechat.feature.chat.resources.context_usage_window
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Compact context-window usage gauge (v0.8.7). A slim pill with a thin progress
 * bar and a "used / window" percentage; tapping opens a breakdown sheet. The
 * caller gates visibility on `contextUsageEnabled` and a non-null snapshot.
 */
@Composable
fun ContextUsageGauge(
    usage: ContextUsage,
    modifier: Modifier = Modifier,
    tokenUsage: TokenUsage? = null,
) {
    var showSheet by remember { mutableStateOf(false) }
    val percent = (usage.usedFraction * 100).toInt()

    Surface(
        onClick = { showSheet = true },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        ContextGaugePill(percent = percent, usedFraction = usage.usedFraction)
    }

    if (showSheet) {
        ContextUsageSheet(usage = usage, tokenUsage = tokenUsage, onDismiss = { showSheet = false })
    }
}

/**
 * Context-usage gauge styled as a top-bar overflow-menu entry: a standard [DropdownMenuItem] (so it
 * shares the menu's leading-icon column, padding, and row height with the other actions instead of
 * floating as a padded pill) with the label and bar + percentage trailing. Tapping it dismisses the
 * menu (via [onClick]) and the host opens the breakdown [ContextUsageSheet] — the breakdown can't be
 * opened from inside the open menu popup itself (that would nest modal surfaces), so the menu hands
 * the trigger up to its host (see [ChatFloatingTopBar]). The bar is a fixed width (not weighted): a
 * DropdownMenu measures its content at IntrinsicSize.Max, where a stretchy child would blow the menu
 * out to full screen width.
 */
@Composable
fun ContextUsageMenuItem(
    usage: ContextUsage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val percent = (usage.usedFraction * 100).toInt()
    DropdownMenuItem(
        modifier = modifier,
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.context_usage_label),
                    modifier = Modifier.weight(1f),
                )
                LinearProgressIndicator(
                    progress = { usage.usedFraction },
                    modifier = Modifier.width(72.dp),
                    drawStopIndicator = {},
                )
                Text(text = "$percent%")
            }
        },
        onClick = onClick,
        leadingIcon = {
            Icon(Icons.Outlined.DataUsage, contentDescription = null)
        },
    )
}

/**
 * Full-width gauge that expands in place to reveal the breakdown — for the composer "+" sheet,
 * where a tap can't open a modal sheet (that would nest modal surfaces) but the inline space is
 * available. The header pill fills the row; tapping it toggles the [ContextUsageBreakdown] below.
 * The expanded state is hoisted so the caller can persist it across sheet openings.
 */
@Composable
fun ContextUsageExpandableGauge(
    usage: ContextUsage,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    tokenUsage: TokenUsage? = null,
) {
    val percent = (usage.usedFraction * 100).toInt()
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "ctx_chevron")

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Surface(
                onClick = { onExpandedChange(!expanded) },
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                ContextGaugePill(percent = percent, usedFraction = usage.usedFraction, fillWidth = true) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.rotate(chevronRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                ContextUsageBreakdown(
                    usage = usage,
                    tokenUsage = tokenUsage,
                    // The header pill above already shows the progress bar, so suppress the
                    // breakdown's own bar here to avoid two identical bars stacked.
                    showProgressBar = false,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/**
 * The shared pill visual: "Context" label, a thin progress bar, and the used percentage. When
 * [fillWidth] is set the bar stretches to fill the row (the rest hug their content); [trailing]
 * appends an optional element (e.g. an expand chevron) after the percentage.
 */
@Composable
private fun ContextGaugePill(
    percent: Int,
    usedFraction: Float,
    fillWidth: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.context_usage_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { usedFraction },
            modifier = if (fillWidth) Modifier.weight(1f) else Modifier.width(72.dp),
            // Drop the M3 track stop indicator (the dot at the 100% end) so a low
            // percentage reads as a single thin bar, not "tiny dot … dot".
            drawStopIndicator = {},
        )
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        trailing()
    }
}

/**
 * Breakdown sheet mirroring the web client's `Breakdown.tsx`: a header line with
 * `used / window (percent)`, a progress bar, the per-component rows (each with its
 * share of the window), and — when a live token-usage snapshot exists — a divided
 * Input/Output section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextUsageSheet(
    usage: ContextUsage,
    tokenUsage: TokenUsage?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { LowProfileDragHandle() },
    ) {
        ContextUsageBreakdown(
            usage = usage,
            tokenUsage = tokenUsage,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        )
    }
}

/**
 * The breakdown body shared by the modal sheet ([ContextUsageSheet]) and the inline expand
 * ([ContextUsageExpandableGauge]): a header line with `used / window (percent)`, a progress bar,
 * the per-component rows (each with its share of the window), and — when a live token-usage
 * snapshot exists — a divided Input/Output section.
 */
@Composable
private fun ContextUsageBreakdown(
    usage: ContextUsage,
    tokenUsage: TokenUsage?,
    modifier: Modifier = Modifier,
    showProgressBar: Boolean = true,
) {
    val maxTokens = usage.maxContextTokens
    val used = usage.usedTokens
    val percent = (usage.usedFraction * 100).roundToInt()

    // Mirror the web breakdown math (Breakdown.tsx): split `used` into its parts.
    val instructionTokens = usage.effectiveInstructionTokens ?: usage.breakdown.instructionTokens
    val systemTokens = usage.breakdown.systemMessageTokens + usage.breakdown.dynamicInstructionTokens
    val summaryTokens = usage.breakdown.summaryTokens
    val messageTokens = (used - instructionTokens - summaryTokens).coerceAtLeast(0)
    val freeTokens = (maxTokens - used).coerceAtLeast(0)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.context_usage_window),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (maxTokens > 0) {
                    "${formatTokens(used)} / ${formatTokens(maxTokens)} ($percent%)"
                } else {
                    formatTokens(used)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showProgressBar) {
            LinearProgressIndicator(
                progress = { usage.usedFraction },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {},
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            UsageRow(stringResource(Res.string.context_usage_messages), messageTokens, maxTokens)
            if (systemTokens > 0) {
                UsageRow(stringResource(Res.string.context_usage_system), systemTokens, maxTokens)
            }
            UsageRow(stringResource(Res.string.context_usage_tools), usage.breakdown.toolSchemaTokens, maxTokens)
            if (summaryTokens > 0) {
                UsageRow(stringResource(Res.string.context_usage_summary), summaryTokens, maxTokens)
            }
            UsageRow(stringResource(Res.string.context_usage_free), freeTokens, maxTokens)
        }

        val input = tokenUsage?.inputTokens
        val output = tokenUsage?.outputTokens
        if (input != null || output != null) {
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                UsageRow(stringResource(Res.string.context_usage_input), input ?: 0, max = 0)
                UsageRow(stringResource(Res.string.context_usage_output), output ?: 0, max = 0)
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, tokens: Int, max: Int) {
    val percent = if (max > 0) ((tokens.toFloat() / max) * 100).coerceAtMost(100f).roundToInt() else null
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatTokens(tokens),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (percent != null) {
                Text(
                    text = " ($percent%)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatTokens(n: Int): String =
    if (n >= 1000) {
        val rounded = (n / 1000.0 * 10).roundToInt() / 10.0
        val text = if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
        "${text}K"
    } else {
        n.toString()
    }
