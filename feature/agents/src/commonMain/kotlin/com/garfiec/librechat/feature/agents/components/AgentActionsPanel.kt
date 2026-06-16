package com.garfiec.librechat.feature.agents.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.garfiec.librechat.core.model.ActionAuth
import com.garfiec.librechat.core.model.ActionMetadata
import com.garfiec.librechat.core.model.request.FunctionTool
import com.garfiec.librechat.feature.agents.AgentActionDisplayData
import com.garfiec.librechat.feature.agents.resources.*
import com.garfiec.librechat.feature.agents.resources.Res
import com.garfiec.librechat.feature.agents.util.OpenApiSpecParser
import com.garfiec.librechat.feature.agents.util.ParsedFunctionInfo
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

private const val VALIDATION_DEBOUNCE_MS = 800L

/** Collapsible CRUD panel for OpenAPI actions with full editor. */
@Composable
fun AgentActionsPanel(
    actions: List<AgentActionDisplayData>,
    onSaveAction: (actionId: String?, metadata: ActionMetadata, functions: List<FunctionTool>) -> Unit,
    onDeleteAction: (actionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var editingAction by remember { mutableStateOf<AgentActionDisplayData?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }

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
                text = stringResource(Res.string.openapi_actions_count, actions.size),
                style = MaterialTheme.typography.titleSmall,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) stringResource(Res.string.cd_collapse) else stringResource(Res.string.cd_expand),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (actions.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.no_actions_configured),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                actions.forEach { action ->
                    val actionId = action.actionId ?: return@forEach
                    ActionCard(
                        action = action,
                        onEdit = {
                            editingAction = action
                            showEditor = true
                        },
                        onDelete = { onDeleteAction(actionId) },
                    )
                }

                OutlinedButton(
                    onClick = {
                        editingAction = null
                        showEditor = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.add_action))
                }
            }
        }
    }

    if (showEditor) {
        ActionEditorDialog(
            existingAction = editingAction,
            onDismiss = {
                showEditor = false
                editingAction = null
            },
            onSave = { actionId, metadata, functions ->
                onSaveAction(actionId, metadata, functions)
                showEditor = false
                editingAction = null
            },
        )
    }
}

@Composable
private fun ActionCard(
    action: AgentActionDisplayData,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = action.domain ?: stringResource(Res.string.unknown_author),
                    style = MaterialTheme.typography.bodyLarge,
                )
                val authLabel = when (action.authType) {
                    "service_http" -> stringResource(Res.string.auth_api_key)
                    "oauth" -> stringResource(Res.string.auth_oauth)
                    else -> stringResource(Res.string.auth_none)
                }
                Text(
                    text = stringResource(Res.string.action_auth_info, authLabel, action.functionCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(Res.string.cd_edit_action),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.cd_delete_action),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ==========================================
// Action Editor Dialog (Full-screen)
// ==========================================

@Composable
private fun ActionEditorDialog(
    existingAction: AgentActionDisplayData?,
    onDismiss: () -> Unit,
    onSave: (actionId: String?, metadata: ActionMetadata, functions: List<FunctionTool>) -> Unit,
) {
    // State for the editor
    var rawSpec by rememberSaveable { mutableStateOf(existingAction?.rawSpec ?: "") }
    var authType by rememberSaveable { mutableStateOf(existingAction?.authType ?: "none") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var authorizationType by rememberSaveable { mutableStateOf("bearer") }
    var customAuthHeader by rememberSaveable { mutableStateOf("") }
    var oauthClientId by rememberSaveable { mutableStateOf("") }
    var oauthClientSecret by rememberSaveable { mutableStateOf("") }
    var authorizationUrl by rememberSaveable { mutableStateOf("") }
    var clientUrl by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf("") }
    var tokenExchangeMethod by rememberSaveable { mutableStateOf("default_post") }
    var privacyPolicyUrl by rememberSaveable { mutableStateOf("") }

    // Validation state
    var parsedDomain by remember { mutableStateOf("") }
    var parsedFunctions by remember { mutableStateOf<List<FunctionTool>>(emptyList()) }
    var parsedFunctionInfos by remember { mutableStateOf<List<ParsedFunctionInfo>>(emptyList()) }
    var validationErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var isValidating by remember { mutableStateOf(false) }

    // Auth config dialog
    var showAuthConfig by rememberSaveable { mutableStateOf(false) }

    // For editing existing actions: show hidden placeholder for sensitive fields
    val isEditing = existingAction?.actionId != null

    // Debounced validation
    LaunchedEffect(rawSpec) {
        if (rawSpec.isBlank()) {
            parsedDomain = ""
            parsedFunctions = emptyList()
            parsedFunctionInfos = emptyList()
            validationErrors = emptyList()
            return@LaunchedEffect
        }
        isValidating = true
        delay(VALIDATION_DEBOUNCE_MS)
        val result = OpenApiSpecParser.parse(rawSpec)
        parsedDomain = result.domain
        parsedFunctions = result.functions
        parsedFunctionInfos = OpenApiSpecParser.extractFunctionInfo(rawSpec)
        validationErrors = result.errors
        isValidating = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Scaffold(
            topBar = {
                Surface(tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (isEditing) stringResource(Res.string.edit_action) else stringResource(Res.string.add_action),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row {
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(Res.string.cancel))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = {
                                    val auth = ActionAuth(
                                        type = authType,
                                        authorizationType = if (authType == "service_http") authorizationType else null,
                                        customAuthHeader = if (authType == "service_http" && authorizationType == "custom") {
                                            customAuthHeader.ifBlank { null }
                                        } else {
                                            null
                                        },
                                        authorizationUrl = if (authType == "oauth") authorizationUrl.ifBlank { null } else null,
                                        clientUrl = if (authType == "oauth") clientUrl.ifBlank { null } else null,
                                        scope = if (authType == "oauth") scope.ifBlank { null } else null,
                                        tokenExchangeMethod = if (authType == "oauth") tokenExchangeMethod else null,
                                    )
                                    val metadata = ActionMetadata(
                                        domain = parsedDomain,
                                        auth = auth,
                                        rawSpec = rawSpec,
                                        apiKey = if (
                                            authType == "service_http" &&
                                            apiKey.isNotBlank() &&
                                            apiKey != HIDDEN_PLACEHOLDER
                                        ) {
                                            apiKey
                                        } else {
                                            null
                                        },
                                        oauthClientId = if (
                                            authType == "oauth" &&
                                            oauthClientId.isNotBlank() &&
                                            oauthClientId != HIDDEN_PLACEHOLDER
                                        ) {
                                            oauthClientId
                                        } else {
                                            null
                                        },
                                        oauthClientSecret = if (
                                            authType == "oauth" &&
                                            oauthClientSecret.isNotBlank() &&
                                            oauthClientSecret != HIDDEN_PLACEHOLDER
                                        ) {
                                            oauthClientSecret
                                        } else {
                                            null
                                        },
                                        privacyPolicyUrl = privacyPolicyUrl.ifBlank { null },
                                    )
                                    onSave(existingAction?.actionId, metadata, parsedFunctions)
                                },
                                enabled = parsedFunctions.isNotEmpty() && validationErrors.isEmpty() && !isValidating,
                            ) {
                                Text(if (isEditing) stringResource(Res.string.save) else stringResource(Res.string.create))
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 1. Authentication section
                AuthenticationSection(
                    authType = authType,
                    onShowAuthConfig = { showAuthConfig = true },
                )

                HorizontalDivider()

                // 2. Schema input
                Text(
                    text = stringResource(Res.string.label_openapi_schema),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(Res.string.openapi_schema_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = rawSpec,
                    onValueChange = { rawSpec = it },
                    label = { Text(stringResource(Res.string.label_openapi_spec)) },
                    placeholder = {
                        Text(
                            text = "{\n  \"openapi\": \"3.0.0\",\n" +
                                "  \"info\": { ... },\n  \"paths\": { ... }\n}",
                            fontFamily = FontFamily.Monospace,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    minLines = 8,
                    maxLines = 20,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Validation feedback
                if (isValidating) {
                    Text(
                        text = stringResource(Res.string.validating),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (validationErrors.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        validationErrors.forEach { error ->
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                // Domain display
                if (parsedDomain.isNotBlank()) {
                    Text(
                        text = stringResource(Res.string.domain_prefix, parsedDomain),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // 3. Available actions table
                if (parsedFunctionInfos.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(Res.string.available_actions_count, parsedFunctionInfos.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    FunctionTable(functions = parsedFunctionInfos)
                }

                // 4. Privacy policy URL
                OutlinedTextField(
                    value = privacyPolicyUrl,
                    onValueChange = { privacyPolicyUrl = it },
                    label = { Text(stringResource(Res.string.label_privacy_policy_url)) },
                    placeholder = { Text("https://example.com/privacy") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // Auth configuration dialog
    if (showAuthConfig) {
        AuthConfigDialog(
            currentAuthType = authType,
            apiKey = apiKey,
            authorizationType = authorizationType,
            customAuthHeader = customAuthHeader,
            oauthClientId = oauthClientId,
            oauthClientSecret = oauthClientSecret,
            authorizationUrl = authorizationUrl,
            clientUrl = clientUrl,
            scope = scope,
            tokenExchangeMethod = tokenExchangeMethod,
            isEditing = isEditing,
            onDismiss = { showAuthConfig = false },
            onSave = { newAuthType, newApiKey, newAuthorizationType, newCustomHeader,
                       newOauthClientId, newOauthClientSecret, newAuthUrl, newClientUrl,
                       newScope, newTokenExchangeMethod ->
                authType = newAuthType
                apiKey = newApiKey
                authorizationType = newAuthorizationType
                customAuthHeader = newCustomHeader
                oauthClientId = newOauthClientId
                oauthClientSecret = newOauthClientSecret
                authorizationUrl = newAuthUrl
                clientUrl = newClientUrl
                scope = newScope
                tokenExchangeMethod = newTokenExchangeMethod
                showAuthConfig = false
            },
        )
    }
}

// ==========================================
// Authentication Section
// ==========================================

@Composable
private fun AuthenticationSection(
    authType: String,
    onShowAuthConfig: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.label_authentication),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onShowAuthConfig,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = when (authType) {
                    "service_http" -> Icons.Default.Lock
                    "oauth" -> Icons.Default.Security
                    else -> Icons.Default.Lock
                },
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when (authType) {
                    "service_http" -> stringResource(Res.string.auth_api_key_full)
                    "oauth" -> stringResource(Res.string.auth_oauth_full)
                    else -> stringResource(Res.string.auth_no_auth)
                },
            )
        }
    }
}

// ==========================================
// Function Table
// ==========================================

@Composable
private fun FunctionTable(
    functions: List<ParsedFunctionInfo>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.table_name),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(180.dp),
                )
                Text(
                    text = stringResource(Res.string.table_method),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(60.dp),
                )
                Text(
                    text = stringResource(Res.string.table_path),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(200.dp),
                )
            }
            HorizontalDivider()

            // Rows
            functions.forEach { func ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = func.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(180.dp),
                        maxLines = 1,
                    )
                    Text(
                        text = func.method,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(60.dp),
                    )
                    Text(
                        text = func.path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(200.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
