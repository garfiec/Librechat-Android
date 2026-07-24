package com.garfiec.librechat.feature.agents.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.core.model.AccessRole
import com.garfiec.librechat.core.model.Principal
import com.garfiec.librechat.core.model.PrincipalType
import com.garfiec.librechat.feature.agents.resources.Res
import com.garfiec.librechat.feature.agents.resources.acl_add_principal
import com.garfiec.librechat.feature.agents.resources.acl_grant_dialog_title
import com.garfiec.librechat.feature.agents.resources.acl_no_grants
import com.garfiec.librechat.feature.agents.resources.acl_principal_group
import com.garfiec.librechat.feature.agents.resources.acl_principal_role
import com.garfiec.librechat.feature.agents.resources.acl_principal_user
import com.garfiec.librechat.feature.agents.resources.acl_public_description
import com.garfiec.librechat.feature.agents.resources.acl_public_label
import com.garfiec.librechat.feature.agents.resources.acl_revoke
import com.garfiec.librechat.feature.agents.resources.acl_role
import com.garfiec.librechat.feature.agents.resources.acl_search_empty
import com.garfiec.librechat.feature.agents.resources.acl_search_hint
import com.garfiec.librechat.feature.agents.resources.acl_sharing_title
import com.garfiec.librechat.feature.agents.resources.cd_collapse
import com.garfiec.librechat.feature.agents.resources.cd_expand
import com.garfiec.librechat.feature.agents.resources.close
import com.garfiec.librechat.feature.agents.resources.sharing_and_permissions
import com.garfiec.librechat.feature.agents.viewmodel.AgentAclViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * v0.8.5+ ACL-based sharing section. List grants, grant new principals, toggle
 * public access, revoke. Gate this composable at the call site behind
 * BackendVersion.isCompatibleOrNewer(actual, "0.8.5-rc1"); on older servers, keep
 * showing the legacy [AgentSharingSection].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentAclSharingSection(
    viewModel: AgentAclViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.acl_sharing_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) Res.string.cd_collapse else Res.string.cd_expand,
                ),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PublicToggle(
                    isPublic = uiState.isPublic,
                    publicAccessRoleId = uiState.publicAccessRoleId,
                    roles = uiState.availableRoles,
                    onSetPublic = viewModel::setPublic,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(Res.string.sharing_and_permissions),
                    style = MaterialTheme.typography.labelLarge,
                )

                if (uiState.isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (uiState.principals.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.acl_no_grants),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    uiState.principals.forEach { principal ->
                        PrincipalGrantRow(
                            principal = principal,
                            onRevoke = { viewModel.revoke(principal) },
                        )
                    }
                }

                OutlinedButton(
                    onClick = viewModel::openGrantDialog,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(Res.string.acl_add_principal))
                }

                uiState.error?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (uiState.showGrantDialog) {
        GrantAccessDialog(
            searchQuery = uiState.searchQuery,
            results = uiState.searchResults,
            isSearching = uiState.isSearching,
            roles = uiState.availableRoles,
            onQueryChange = viewModel::onSearchQueryChanged,
            onGrant = viewModel::grant,
            onDismiss = viewModel::dismissGrantDialog,
        )
    }
}

@Composable
private fun PrincipalGrantRow(
    principal: Principal,
    onRevoke: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = principal.name ?: principal.id ?: "?",
                    style = MaterialTheme.typography.bodyLarge,
                )
                val typeLabel = stringResource(
                    when (principal.type) {
                        PrincipalType.USER -> Res.string.acl_principal_user
                        PrincipalType.GROUP -> Res.string.acl_principal_group
                        PrincipalType.ROLE -> Res.string.acl_principal_role
                        else -> Res.string.acl_principal_user
                    },
                )
                val subtitle = listOfNotNull(typeLabel, principal.accessRoleId).joinToString(" • ")
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRevoke) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(Res.string.acl_revoke),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PublicToggle(
    isPublic: Boolean,
    publicAccessRoleId: String?,
    roles: List<AccessRole>,
    onSetPublic: (Boolean, String?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.acl_public_label), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(Res.string.acl_public_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = isPublic,
                onCheckedChange = { enabled ->
                    val roleId = publicAccessRoleId ?: roles.firstOrNull()?.accessRoleId
                    onSetPublic(enabled, roleId)
                },
            )
        }
        if (isPublic && roles.isNotEmpty()) {
            var roleMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = roleMenu,
                onExpandedChange = { roleMenu = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = publicAccessRoleId ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(Res.string.acl_role)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = roleMenu) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                    roles.forEach { role ->
                        DropdownMenuItem(
                            text = { Text(role.name ?: role.accessRoleId) },
                            onClick = {
                                onSetPublic(true, role.accessRoleId)
                                roleMenu = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GrantAccessDialog(
    searchQuery: String,
    results: List<Principal>,
    isSearching: Boolean,
    roles: List<AccessRole>,
    onQueryChange: (String) -> Unit,
    onGrant: (Principal, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedRole by remember(roles) { mutableStateOf(roles.firstOrNull()?.accessRoleId) }
    var roleMenu by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.acl_grant_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(Res.string.acl_search_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                ExposedDropdownMenuBox(
                    expanded = roleMenu,
                    onExpandedChange = { roleMenu = it },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = selectedRole ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(Res.string.acl_role)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = roleMenu) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                        roles.forEach { role ->
                            DropdownMenuItem(
                                text = { Text(role.name ?: role.accessRoleId) },
                                onClick = {
                                    selectedRole = role.accessRoleId
                                    roleMenu = false
                                },
                            )
                        }
                    }
                }

                if (isSearching) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else if (searchQuery.isNotBlank() && results.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.acl_search_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    results.forEach { principal ->
                        AssistChip(
                            onClick = {
                                val role = selectedRole ?: return@AssistChip
                                onGrant(principal, role)
                            },
                            label = {
                                Text(principal.name ?: principal.id ?: "?")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
            ) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}
