package com.garfiec.librechat.feature.chat.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

private val SheetRowShape = RoundedCornerShape(8.dp)

// Insets a tappable bottom-sheet row and clips its ripple to [SheetRowShape], so the highlight
// reads as an inset rounded button instead of a full-bleed rectangle. Apply before .clickable so
// the indication is bounded by the rounded shape.
internal fun Modifier.sheetRowRipple(): Modifier =
    padding(horizontal = 4.dp).clip(SheetRowShape)
