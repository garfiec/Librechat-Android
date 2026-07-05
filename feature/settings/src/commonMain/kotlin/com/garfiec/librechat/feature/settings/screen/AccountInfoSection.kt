package com.garfiec.librechat.feature.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.garfiec.librechat.core.ui.components.ErrorBanner
import com.garfiec.librechat.feature.settings.model.UserDisplayData
import com.garfiec.librechat.feature.settings.resources.Res
import com.garfiec.librechat.feature.settings.resources.cd_change_avatar
import com.garfiec.librechat.feature.settings.resources.cd_loading_profile
import com.garfiec.librechat.feature.settings.resources.cd_user_avatar
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AccountInfoSection(
    user: UserDisplayData?,
    profileLoadError: String?,
    onAvatarClick: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        user != null -> AccountInfo(
            name = user.name,
            email = user.email,
            avatarUrl = user.avatar,
            onAvatarClick = onAvatarClick,
        )
        profileLoadError != null -> ErrorBanner(message = profileLoadError, onRetry = onRetry)
        else -> {
            val loadingDescription = stringResource(Res.string.cd_loading_profile)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .semantics { contentDescription = loadingDescription },
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun AccountInfo(
    name: String,
    email: String,
    avatarUrl: String?,
    onAvatarClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primaryContainer,
            onClick = onAvatarClick,
        ) {
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = stringResource(Res.string.cd_user_avatar),
                    modifier = Modifier.size(48.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (name.isNotBlank()) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (email.isNotBlank()) {
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onAvatarClick) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = stringResource(Res.string.cd_change_avatar),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
