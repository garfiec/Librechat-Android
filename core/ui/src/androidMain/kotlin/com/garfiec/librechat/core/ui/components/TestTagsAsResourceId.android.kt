package com.garfiec.librechat.core.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.testTagsAsResourceIdSubtree(): Modifier =
    semantics { testTagsAsResourceId = true }
