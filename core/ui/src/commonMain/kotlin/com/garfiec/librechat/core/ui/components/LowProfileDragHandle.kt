package com.garfiec.librechat.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.ui.resources.Res
import com.garfiec.librechat.core.ui.resources.cd_drag_handle
import org.jetbrains.compose.resources.stringResource

/**
 * The app-wide bottom-sheet drag handle: a slim, low-profile pill. Replaces M3's
 * heavier [androidx.compose.material3.BottomSheetDefaults.DragHandle] so every
 * `ModalBottomSheet` (and the chat pull-up surface, which shares this look) reads the
 * same. Pass to a sheet via `dragHandle = { LowProfileDragHandle() }`.
 *
 * Carries the same localized "Drag handle" accessibility label M3's default exposed, so
 * screen-reader users still hear the handle. Pass `contentDescription = null` to omit it
 * (e.g. a purely decorative surface where a sibling already announces the sheet).
 */
@Composable
fun LowProfileDragHandle(
    modifier: Modifier = Modifier,
    contentDescription: String? = stringResource(Res.string.cd_drag_handle),
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 5.dp)
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                )
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(2.dp),
                ),
        )
    }
}
