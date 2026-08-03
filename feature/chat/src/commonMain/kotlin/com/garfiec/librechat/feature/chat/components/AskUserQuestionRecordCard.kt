package com.garfiec.librechat.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.AskUserQuestionRequest
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * The durable record of an `ask_user_question` exchange — what was asked and what the user said —
 * shown in place of the generic tool card for that call.
 *
 * The generic card is wrong for this tool twice over: it labels the call by its internal name and
 * hides the exchange behind a JSON dump of `{question, options…}` and a raw answer string, and
 * while the run is paused it renders a spinner for a "call" that is really waiting on the user.
 * Everything rendered here is already on the wire — the question, its description and options come
 * from the call's own arguments, the answer from its output — so no extra request or client-side
 * bookkeeping stands behind it.
 *
 * Collapsed it reads as a two-line exchange (question, answer). Expanded it shows the full
 * question, the reason the agent gave for asking, and which of the offered options the answer
 * picked. See [askAnswerDisplay] for why an answer is shown by option *label* only when every
 * segment of it maps.
 */
@Composable
internal fun AskUserQuestionRecordCard(
    question: AskUserQuestionRequest?,
    answer: String,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
) {
    val display = remember(question, answer) { askAnswerDisplay(question, answer) }
    val answered = answer.isNotBlank() && !failed
    val description = question?.description?.takeIf { it.isNotBlank() }
    val options = question?.options.orEmpty()

    // Expandable only when expanding reveals something the collapsed rows cannot: the agent's
    // reason for asking, the options it offered, or an answer too long to sit on one line.
    val hasDetail = description != null || options.isNotEmpty() ||
        (answered && !display.declined && display.label.length > SINGLE_LINE_ANSWER_CHARS)
    var isExpanded by remember { mutableStateOf(false) }

    val headline = when {
        failed -> stringResource(Res.string.ask_user_question_record_failed)
        answered -> stringResource(Res.string.ask_user_question_record_asked)
        else -> stringResource(Res.string.ask_user_question_record_asking)
    }
    // Null when the call carries no arguments — a question the run abandoned before it streamed
    // any. The headline already names what this card is, so nothing stands in for the question:
    // repeating the headline underneath itself reads as a question the agent literally asked.
    val questionText = question?.question?.takeIf { it.isNotBlank() }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column {
            val toggleCd = stringResource(
                if (isExpanded) Res.string.cd_collapse_ask_user_question else Res.string.cd_expand_ask_user_question,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (hasDetail) {
                            Modifier
                                .clickable { isExpanded = !isExpanded }
                                .semantics {
                                    role = Role.Button
                                    contentDescription = toggleCd
                                }
                        } else {
                            Modifier
                        },
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = if (failed) Icons.Default.WarningAmber else Icons.Default.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (questionText != null) {
                        Text(
                            text = questionText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (isExpanded) Int.MAX_VALUE else COLLAPSED_QUESTION_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AnswerLine(display = display, answered = answered, failed = failed, isExpanded = isExpanded)
                }
                if (hasDetail) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded && hasDetail,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(start = 38.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (description != null) {
                        HorizontalDivider()
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (options.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                if (question?.multiSelect == true) {
                                    Res.string.ask_user_question_record_options_multi
                                } else {
                                    Res.string.ask_user_question_record_options
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        options.forEach { option ->
                            OptionRow(
                                label = option.label.ifBlank { option.value },
                                picked = option.value in display.selectedValues,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The answer, or why there isn't one. Clamped to one line while collapsed. */
// Every branch below returns after emitting exactly one thing; the rule cannot see through
// guard clauses, and giving it a single root would add a layout node per answered question.
@Suppress("MultipleEmitters")
@Composable
private fun AnswerLine(
    display: AskAnswerDisplay,
    answered: Boolean,
    failed: Boolean,
    isExpanded: Boolean,
) {
    if (failed) {
        Text(
            text = stringResource(Res.string.ask_user_question_record_failed_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (!answered || display.declined) {
        Text(
            text = stringResource(
                if (display.declined) {
                    Res.string.ask_user_question_record_skipped
                } else {
                    Res.string.ask_user_question_record_unanswered
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row {
        Text(
            text = stringResource(Res.string.ask_user_question_record_answer_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = display.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (isExpanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One offered option, marked when the answer picked it. */
@Composable
private fun OptionRow(label: String, picked: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (picked) Icons.Default.Check else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (picked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (picked) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private const val COLLAPSED_QUESTION_LINES = 2

/** Roughly what fits on the answer's single collapsed line; only decides whether a chevron is
 *  offered, so an approximation is enough. */
private const val SINGLE_LINE_ANSWER_CHARS = 48
