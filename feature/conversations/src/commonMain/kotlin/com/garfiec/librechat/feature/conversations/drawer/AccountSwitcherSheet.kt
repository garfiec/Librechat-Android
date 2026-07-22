package com.garfiec.librechat.feature.conversations.drawer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.ui.components.AvatarImage
import com.garfiec.librechat.core.ui.components.LowProfileDragHandle
import com.garfiec.librechat.core.ui.components.avatarColorForSeed
import com.garfiec.librechat.core.ui.components.testTagsAsResourceIdSubtree
import com.garfiec.librechat.feature.conversations.resources.Res
import com.garfiec.librechat.feature.conversations.resources.accounts
import com.garfiec.librechat.feature.conversations.resources.add_account
import com.garfiec.librechat.feature.conversations.resources.cancel
import com.garfiec.librechat.feature.conversations.resources.cd_active_account
import com.garfiec.librechat.feature.conversations.resources.remove
import com.garfiec.librechat.feature.conversations.resources.remove_account
import com.garfiec.librechat.feature.conversations.resources.remove_account_message
import com.garfiec.librechat.feature.conversations.resources.remove_account_title
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/** Vertical drag past this distance (up or down) on the chip commits a round-robin switch. */
private val SwipeSwitchThreshold = 40.dp

/**
 * How far the avatar visually follows the finger during a swipe. Clamped well below
 * [SwipeSwitchThreshold] so the chip nudges just enough to signal the gesture is live, then
 * springs back on release.
 */
private val DragVisualLimit = 14.dp

/** How far the avatar shrinks at the edge of a drag (1.0 = full size). */
private const val MinDragScale = 0.7f

/** How far the avatar fades at the edge of a drag (1.0 = fully opaque). */
private const val MinDragAlpha = 0.75f

/**
 * The drawer-footer account chip (Gmail-style): just the active account's avatar, tapped to open
 * the roster sheet ([AccountSwitcherSheet]). Swiping up/down round-robins to the adjacent account
 * (like Gmail/YouTube profile swipe) via [onSwitchAdjacent] — passed +1 for a swipe up (next) and
 * -1 for a swipe down (previous); null disables the gesture (single account). Rendered only while
 * an account is resolved — the drawer isn't reachable from the auth flow, so [account] is normally
 * non-null.
 */
@Composable
fun AccountChip(
    account: AccountUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSwitchAdjacent: ((Int) -> Unit)? = null,
) {
    // Visual offset the avatar follows during a drag (clamped to DragVisualLimit), springing back
    // to rest on release. Declared unconditionally so the graphicsLayer below is stable even when
    // the gesture is disabled (single account), where it simply stays at 0.
    val dragOffsetY = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val swipeModifier = if (onSwitchAdjacent != null) {
        val threshold = with(LocalDensity.current) { SwipeSwitchThreshold.toPx() }
        val visualLimit = with(LocalDensity.current) { DragVisualLimit.toPx() }
        val currentOnSwitch by rememberUpdatedState(onSwitchAdjacent)
        Modifier.pointerInput(Unit) {
            var dragTotal = 0f
            var settleJob: Job? = null
            detectVerticalDragGestures(
                onDragStart = {
                    settleJob?.cancel()
                    dragTotal = 0f
                },
                onDragEnd = {
                    // Up (negative Y) = next; down (positive Y) = previous. Wraparound is the
                    // caller's job (it holds the account ordering).
                    when {
                        dragTotal <= -threshold -> currentOnSwitch(1)
                        dragTotal >= threshold -> currentOnSwitch(-1)
                    }
                    settleJob = scope.launch {
                        animate(
                            initialValue = dragOffsetY.floatValue,
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        ) { value, _ -> dragOffsetY.floatValue = value }
                    }
                },
                onDragCancel = {
                    settleJob = scope.launch {
                        animate(dragOffsetY.floatValue, 0f) { value, _ -> dragOffsetY.floatValue = value }
                    }
                },
            ) { change, dragAmount ->
                dragTotal += dragAmount
                dragOffsetY.floatValue = dragTotal.coerceIn(-visualLimit, visualLimit)
                change.consume()
            }
        }
    } else {
        Modifier
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            .testTag("account_chip")
            .graphicsLayer {
                translationY = dragOffsetY.floatValue
                // Shrink and fade toward the drag limit, tracking the spring-back on release.
                val fraction = (abs(dragOffsetY.floatValue) / DragVisualLimit.toPx()).coerceIn(0f, 1f)
                val scale = 1f - fraction * (1f - MinDragScale)
                scaleX = scale
                scaleY = scale
                alpha = 1f - fraction * (1f - MinDragAlpha)
            }
            .then(swipeModifier),
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        AnimatedContent(
            targetState = account,
            // Morph only on a real account switch: keying on the id keeps an in-place update to the
            // same account (e.g. a late-loading avatar) from triggering a spurious swap animation.
            contentKey = { it.accountId },
            // Expand + fade in the incoming avatar, shrink + fade out the outgoing one — the same
            // vocabulary as the drag. Because this sits inside the drag graphicsLayer, the container's
            // spring back to full size/opacity multiplies through, so the exit continues from the
            // dragged scale/alpha rather than snapping back to full first.
            transitionSpec = {
                (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut())
            },
            label = "accountAvatar",
        ) { acct ->
            AvatarImage(
                imageUrl = acct.avatarUrl,
                size = 32.dp,
                fallbackText = acct.displayLabel,
                fallbackBackgroundColor = avatarColorForSeed(acct.accountId),
                contentDescription = acct.displayLabel,
                modifier = Modifier.padding(6.dp),
            )
        }
    }
}

/**
 * The account id one round-robin step from the active account, for the chip's swipe gesture.
 * Cycles a STABLE order (by [AccountUiModel.accountId]) rather than the display list's active-first
 * order — the latter moves the target under the user's finger after every switch. [delta] is +1
 * (next) / -1 (previous), wrapping at the ends. Returns null when there's nothing to switch to
 * (fewer than two accounts, or no active entry).
 */
internal fun adjacentAccountId(accounts: List<AccountUiModel>, delta: Int): String? {
    if (accounts.size < 2) return null
    val ordered = accounts.sortedBy { it.accountId }
    val activeIndex = ordered.indexOfFirst { it.isActive }
    if (activeIndex < 0) return null
    val size = ordered.size
    val target = ordered[((activeIndex + delta) % size + size) % size]
    return if (target.isActive) null else target.accountId
}

/**
 * The roster bottom sheet: every signed-in account (active first), tap to switch, per-row remove
 * (confirmed by [RemoveAccountDialog], hoisted by the caller), and an add-account entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcherSheet(
    accounts: List<AccountUiModel>,
    onSwitchAccount: (String) -> Unit,
    onRemoveAccountRequest: (AccountUiModel) -> Unit,
    onAddAccount: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { LowProfileDragHandle() },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.testTagsAsResourceIdSubtree().navigationBarsPadding()) {
            Text(
                text = stringResource(Res.string.accounts),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            accounts.forEach { account ->
                AccountRow(
                    account = account,
                    onClick = { if (!account.isActive) onSwitchAccount(account.accountId) },
                    onRemoveRequest = { onRemoveAccountRequest(account) },
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("account_add")
                    .clickable(onClick = onAddAccount)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(Res.string.add_account),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AccountRow(
    account: AccountUiModel,
    onClick: () -> Unit,
    onRemoveRequest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarImage(
            imageUrl = account.avatarUrl,
            size = 36.dp,
            fallbackText = account.displayLabel,
            fallbackBackgroundColor = avatarColorForSeed(account.accountId),
            contentDescription = null,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.displayLabel,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = account.serverHost,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (account.isActive) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(Res.string.cd_active_account),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        IconButton(onClick = onRemoveRequest) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(Res.string.remove_account),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Destructive-action confirmation for removing [account] and its on-device data. */
@Composable
fun RemoveAccountDialog(
    account: AccountUiModel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.remove_account_title)) },
        text = { Text(stringResource(Res.string.remove_account_message, account.displayLabel)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(Res.string.remove),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}
