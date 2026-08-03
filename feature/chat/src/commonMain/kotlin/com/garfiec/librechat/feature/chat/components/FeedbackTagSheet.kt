package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.FeedbackRating
import com.garfiec.librechat.core.model.FeedbackTag
import com.garfiec.librechat.core.ui.components.LowProfileDragHandle
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import org.jetbrains.compose.resources.stringResource

private const val MAX_COMMENT_LENGTH = 1024

/**
 * Reason picker shown before a thumbs-up or thumbs-down is submitted.
 *
 * The route's schema requires a tag, so both directions come through here — a bare rating is
 * rejected. One composable for both: only the tag list and the prompt differ, while the scroll,
 * IME and selection plumbing is the part worth not duplicating.
 *
 * A sheet rather than a dialog because `AlertDialog`'s text slot neither scrolls nor gets more
 * than its measured share of the height, so seven reasons plus a comment field would clip the
 * buttons out of reach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedbackTagSheet(
    rating: FeedbackRating,
    onSubmit: (tag: FeedbackTag, comment: String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState()
    val tags = remember(rating) { FeedbackTag.forRating(rating) }
    // A sheet opened while idle outlives a stream that starts under it — `onResume` adopting a run
    // another client began, or a queued message draining. Submitting then reaches the sink guard
    // and drops the reason and up to 1024 characters silently, which is the failure disabling the
    // thumbs was meant to remove. The sheet stays up and keeps the draft; Submit re-enables when
    // the run ends.
    val submitEnabled = LocalFeedbackEnabled.current

    // Saveable: the sheet outlives a rotation or a fold, and a plain `remember` silently eats the
    // typed comment. Enum names, not entries — the default saver only handles primitives.
    var selectedTagName by rememberSaveable(rating) { mutableStateOf<String?>(null) }
    var comment by rememberSaveable(rating) { mutableStateOf("") }
    val selectedTag = tags.firstOrNull { it.name == selectedTagName }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { LowProfileDragHandle() },
        modifier = modifier,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(
                    if (rating == FeedbackRating.THUMBS_UP) {
                        Res.string.feedback_question_positive
                    } else {
                        Res.string.feedback_question
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            // selectableGroup, not just the per-row roles: without it TalkBack reads each radio in
            // isolation ("radio button, not checked") with no position, and D-pad does not treat
            // the set as one stop.
            Column(modifier = Modifier.selectableGroup()) {
                tags.forEach { tag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .selectable(
                                selected = selectedTag == tag,
                                onClick = { selectedTagName = tag.name },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedTag == tag, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = feedbackTagLabel(tag),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!submitEnabled) {
                Text(
                    text = stringResource(Res.string.feedback_unavailable_while_generating),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            OutlinedTextField(
                value = comment,
                onValueChange = { if (it.length <= MAX_COMMENT_LENGTH) comment = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(Res.string.hint_optional_comment)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2,
                maxLines = 5,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.cancel))
                }
                TextButton(
                    // The server rejects a tagless submission, so an enabled button here would
                    // just produce a 400 the user never sees.
                    enabled = selectedTag != null && submitEnabled,
                    onClick = {
                        selectedTag?.let { onSubmit(it, comment.trim().ifBlank { null }) }
                    },
                ) {
                    Text(stringResource(Res.string.action_submit))
                }
            }
        }
    }
}

@Composable
private fun feedbackTagLabel(tag: FeedbackTag): String = stringResource(
    when (tag) {
        FeedbackTag.NOT_MATCHED -> Res.string.feedback_tag_not_matched
        FeedbackTag.INACCURATE -> Res.string.feedback_tag_inaccurate
        FeedbackTag.BAD_STYLE -> Res.string.feedback_tag_bad_style
        FeedbackTag.MISSING_IMAGE -> Res.string.feedback_tag_missing_image
        FeedbackTag.UNJUSTIFIED_REFUSAL -> Res.string.feedback_tag_unjustified_refusal
        FeedbackTag.NOT_HELPFUL -> Res.string.feedback_tag_not_helpful
        FeedbackTag.OTHER -> Res.string.feedback_tag_other
        FeedbackTag.ACCURATE_RELIABLE -> Res.string.feedback_tag_accurate_reliable
        FeedbackTag.CREATIVE_SOLUTION -> Res.string.feedback_tag_creative_solution
        FeedbackTag.CLEAR_WELL_WRITTEN -> Res.string.feedback_tag_clear_well_written
        FeedbackTag.ATTENTION_TO_DETAIL -> Res.string.feedback_tag_attention_to_detail
    },
)
