package com.garfiec.librechat.core.ui.components

import androidx.compose.ui.Modifier

/**
 * Opts a Dialog/ModalBottomSheet content root into surfacing the `testTag`s in its subtree as
 * Android `resource-id`s for on-device UiAutomator. The Activity-level flag set on the root Surface
 * does not reach these subtrees — each renders in its own window/AndroidComposeView — so every such
 * root must opt in itself. No-op on iOS.
 */
expect fun Modifier.testTagsAsResourceIdSubtree(): Modifier
