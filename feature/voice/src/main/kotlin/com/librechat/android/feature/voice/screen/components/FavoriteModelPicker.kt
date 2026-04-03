package com.librechat.android.feature.voice.screen.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librechat.android.core.model.UserFavorite
import com.librechat.android.core.ui.components.endpointIconRes
import com.librechat.android.core.ui.components.isMonochromeEndpointIcon
import com.librechat.android.feature.voice.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteModelPicker(
    favorites: List<UserFavorite>,
    onDismiss: () -> Unit,
    onSelect: (UserFavorite) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.voice_select_favorite_model),
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.voice_close),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (favorites.isEmpty()) {
            Text(
                text = stringResource(R.string.voice_no_favorites),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(bottom = 24.dp),
            ) {
                items(favorites) { favorite ->
                    FavoriteModelRow(
                        favorite = favorite,
                        onClick = { onSelect(favorite) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteModelRow(
    favorite: UserFavorite,
    onClick: () -> Unit,
) {
    val endpoint = favorite.endpoint
    val modelLabel = favorite.model ?: favorite.agentId ?: "Unknown"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val iconRes = endpointIconRes(endpoint)
        if (iconRes != null) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isMonochromeEndpointIcon(endpoint)) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    androidx.compose.ui.graphics.Color.Unspecified
                },
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column {
            Text(
                text = modelLabel,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = endpoint ?: "agents",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
