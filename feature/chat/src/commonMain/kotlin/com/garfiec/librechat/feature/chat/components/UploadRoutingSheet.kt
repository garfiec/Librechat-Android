package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.response.UploadRoute
import com.garfiec.librechat.feature.chat.resources.*
import com.garfiec.librechat.feature.chat.resources.Res
import com.garfiec.librechat.feature.chat.viewmodel.PendingUploadFile
import org.jetbrains.compose.resources.stringResource

/**
 * Asks which way each picked file should be delivered, before anything is uploaded.
 *
 * A `ModalBottomSheet` rather than an `AlertDialog`: the dialog's text slot has no scroll
 * modifier, so a multi-file pick would clip its last rows out of reach.
 *
 * Files with only one usable mode are shown with their control disabled rather than hidden — the
 * user picked them, and silently omitting them reads as them having been dropped. The sheet is
 * only opened when at least one file genuinely has a choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadRoutingSheet(
    files: List<PendingUploadFile>,
    /** Route change for the row at this index — position, not value: a batch can hold the same
     *  file twice, and matching by value would flip both rows on one tap. */
    onRouteChange: (Int, UploadRoute) -> Unit,
    onApplyToAll: (UploadRoute) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The picker is `OpenMultipleDocuments`, so the batch is unbounded while
                // `ModalBottomSheet`'s content slot is a plain Column that Material adds no scroll
                // to. Without this a six-file pick pushes Attach/Cancel past the sheet's max height
                // and the only way out is to back out, losing the whole pick.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = if (files.size == 1) {
                    stringResource(Res.string.upload_routing_sheet_title_single)
                } else {
                    stringResource(Res.string.upload_routing_sheet_title)
                },
                style = MaterialTheme.typography.titleMedium,
            )

            files.forEachIndexed { index, staged ->
                UploadRoutingRow(
                    staged = staged,
                    onRouteChange = { onRouteChange(index, it) },
                )
            }

            if (files.count { it.choosable } > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(Res.string.upload_routing_apply_all),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    TextButton(onClick = { onApplyToAll(UploadRoute.PROVIDER) }) {
                        Text(stringResource(Res.string.upload_routing_option_provider))
                    }
                    TextButton(onClick = { onApplyToAll(UploadRoute.TEXT) }) {
                        Text(stringResource(Res.string.upload_routing_option_text))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.cancel))
                }
                Button(onClick = onConfirm) {
                    Text(stringResource(Res.string.upload_routing_attach))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadRoutingRow(
    staged: PendingUploadFile,
    onRouteChange: (UploadRoute) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = staged.file.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val options = UploadRoute.entries
            options.forEachIndexed { index, route ->
                SegmentedButton(
                    selected = staged.route == route,
                    onClick = { onRouteChange(route) },
                    enabled = staged.choosable,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(
                        text = when (route) {
                            UploadRoute.PROVIDER -> stringResource(Res.string.upload_routing_option_provider)
                            UploadRoute.TEXT -> stringResource(Res.string.upload_routing_option_text)
                        },
                    )
                }
            }
        }
        Text(
            text = routeExplanation(staged),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One line saying what will actually happen — the thing "to the model" / "as text" doesn't say. */
@Composable
private fun routeExplanation(staged: PendingUploadFile): String = when {
    !staged.choosable && staged.route == UploadRoute.PROVIDER ->
        stringResource(Res.string.upload_routing_why_provider_only)
    !staged.choosable -> stringResource(Res.string.upload_routing_why_text_only)
    staged.route == UploadRoute.PROVIDER -> stringResource(Res.string.upload_routing_why_provider)
    else -> stringResource(Res.string.upload_routing_why_text)
}
