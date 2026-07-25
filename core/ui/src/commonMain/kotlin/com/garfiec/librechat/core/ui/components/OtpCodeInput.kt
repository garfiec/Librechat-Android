package com.garfiec.librechat.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Six-box numeric OTP entry: one [BasicTextField] behind a row of digit boxes.
 *
 * A single field (rather than six) is what makes the whole code pasteable in one action and keeps
 * focus handling trivial; the boxes are only a decoration.
 *
 * [onValueChange] is filtered to digits and capped at [length], so callers can auto-submit on
 * `value.length == length` without re-validating. Callers that auto-submit must guard against a
 * second submit, since composition can deliver the terminal value more than once.
 */
@Composable
fun OtpCodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    length: Int = OTP_LENGTH,
) {
    BasicTextField(
        value = value,
        onValueChange = { new ->
            if (new.length <= length && new.all { it.isDigit() }) onValueChange(new)
        },
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        modifier = modifier.fillMaxWidth(),
        decorationBox = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                repeat(length) { index ->
                    val char = value.getOrNull(index)?.toString() ?: ""
                    val isFocused = index == value.length
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .border(
                                width = if (isFocused) 2.dp else 1.dp,
                                color = if (isFocused) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                shape = DIGIT_BOX_SHAPE,
                            )
                            .background(MaterialTheme.colorScheme.surface, DIGIT_BOX_SHAPE),
                    ) {
                        Text(
                            text = char,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
    )
}

const val OTP_LENGTH = 6

private val DIGIT_BOX_SHAPE = RoundedCornerShape(8.dp)
