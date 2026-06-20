package com.garfiec.librechat.feature.agents.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.garfiec.librechat.feature.agents.resources.*
import com.garfiec.librechat.feature.agents.resources.Res
import org.jetbrains.compose.resources.stringResource

internal const val HIDDEN_PLACEHOLDER = "<HIDDEN>"

@Composable
internal fun AuthConfigDialog(
    currentAuthType: String,
    apiKey: String,
    authorizationType: String,
    customAuthHeader: String,
    oauthClientId: String,
    oauthClientSecret: String,
    authorizationUrl: String,
    clientUrl: String,
    scope: String,
    tokenExchangeMethod: String,
    isEditing: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        authType: String,
        apiKey: String,
        authorizationType: String,
        customAuthHeader: String,
        oauthClientId: String,
        oauthClientSecret: String,
        authorizationUrl: String,
        clientUrl: String,
        scope: String,
        tokenExchangeMethod: String,
    ) -> Unit,
) {
    var selectedAuthType by rememberSaveable { mutableStateOf(currentAuthType) }
    var localApiKey by rememberSaveable { mutableStateOf(apiKey) }
    var localAuthorizationType by rememberSaveable { mutableStateOf(authorizationType) }
    var localCustomHeader by rememberSaveable { mutableStateOf(customAuthHeader) }
    var localOauthClientId by rememberSaveable { mutableStateOf(oauthClientId) }
    var localOauthClientSecret by rememberSaveable { mutableStateOf(oauthClientSecret) }
    var localAuthUrl by rememberSaveable { mutableStateOf(authorizationUrl) }
    var localClientUrl by rememberSaveable { mutableStateOf(clientUrl) }
    var localScope by rememberSaveable { mutableStateOf(scope) }
    var localTokenExchangeMethod by rememberSaveable { mutableStateOf(tokenExchangeMethod) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.label_authentication)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Auth type radio group
                Text(
                    text = stringResource(Res.string.label_authentication_type),
                    style = MaterialTheme.typography.labelLarge,
                )

                AuthTypeRadioOption(
                    label = stringResource(Res.string.auth_none),
                    selected = selectedAuthType == "none",
                    onClick = { selectedAuthType = "none" },
                )
                AuthTypeRadioOption(
                    label = stringResource(Res.string.auth_api_key),
                    selected = selectedAuthType == "service_http",
                    onClick = { selectedAuthType = "service_http" },
                )
                AuthTypeRadioOption(
                    label = stringResource(Res.string.auth_oauth),
                    selected = selectedAuthType == "oauth",
                    onClick = { selectedAuthType = "oauth" },
                )

                // API Key configuration
                AnimatedVisibility(visible = selectedAuthType == "service_http") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HorizontalDivider()
                        Text(
                            text = stringResource(Res.string.label_api_key_settings),
                            style = MaterialTheme.typography.labelLarge,
                        )

                        OutlinedTextField(
                            value = localApiKey,
                            onValueChange = { localApiKey = it },
                            label = { Text(stringResource(Res.string.label_api_key)) },
                            placeholder = {
                                Text(if (isEditing) HIDDEN_PLACEHOLDER else "sk-...")
                            },
                            singleLine = true,
                            visualTransformation = if (localApiKey == HIDDEN_PLACEHOLDER) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Text(
                            text = stringResource(Res.string.label_authorization_type),
                            style = MaterialTheme.typography.labelMedium,
                        )

                        AuthTypeRadioOption(
                            label = stringResource(Res.string.auth_basic),
                            selected = localAuthorizationType == "basic",
                            onClick = { localAuthorizationType = "basic" },
                        )
                        AuthTypeRadioOption(
                            label = stringResource(Res.string.auth_bearer),
                            selected = localAuthorizationType == "bearer",
                            onClick = { localAuthorizationType = "bearer" },
                        )
                        AuthTypeRadioOption(
                            label = stringResource(Res.string.auth_custom),
                            selected = localAuthorizationType == "custom",
                            onClick = { localAuthorizationType = "custom" },
                        )

                        AnimatedVisibility(visible = localAuthorizationType == "custom") {
                            OutlinedTextField(
                                value = localCustomHeader,
                                onValueChange = { localCustomHeader = it },
                                label = { Text(stringResource(Res.string.label_custom_auth_header)) },
                                placeholder = { Text("X-Api-Key") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // OAuth configuration
                AnimatedVisibility(visible = selectedAuthType == "oauth") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HorizontalDivider()
                        Text(
                            text = stringResource(Res.string.label_oauth_settings),
                            style = MaterialTheme.typography.labelLarge,
                        )

                        OutlinedTextField(
                            value = localOauthClientId,
                            onValueChange = { localOauthClientId = it },
                            label = { Text(stringResource(Res.string.label_client_id)) },
                            placeholder = {
                                Text(if (isEditing) HIDDEN_PLACEHOLDER else "client-id")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = localOauthClientSecret,
                            onValueChange = { localOauthClientSecret = it },
                            label = { Text(stringResource(Res.string.label_client_secret)) },
                            placeholder = {
                                Text(if (isEditing) HIDDEN_PLACEHOLDER else "client-secret")
                            },
                            singleLine = true,
                            visualTransformation = if (localOauthClientSecret == HIDDEN_PLACEHOLDER) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = localAuthUrl,
                            onValueChange = { localAuthUrl = it },
                            label = { Text(stringResource(Res.string.label_authorization_url)) },
                            placeholder = { Text("https://auth.example.com/authorize") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = localClientUrl,
                            onValueChange = { localClientUrl = it },
                            label = { Text(stringResource(Res.string.label_token_url)) },
                            placeholder = { Text("https://auth.example.com/token") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = localScope,
                            onValueChange = { localScope = it },
                            label = { Text(stringResource(Res.string.label_scope)) },
                            placeholder = { Text("openid profile") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Text(
                            text = stringResource(Res.string.label_token_exchange_method),
                            style = MaterialTheme.typography.labelMedium,
                        )

                        AuthTypeRadioOption(
                            label = stringResource(Res.string.token_default_post),
                            selected = localTokenExchangeMethod == "default_post",
                            onClick = { localTokenExchangeMethod = "default_post" },
                        )
                        AuthTypeRadioOption(
                            label = stringResource(Res.string.token_basic_auth_header),
                            selected = localTokenExchangeMethod == "basic_auth_header",
                            onClick = { localTokenExchangeMethod = "basic_auth_header" },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        selectedAuthType,
                        localApiKey,
                        localAuthorizationType,
                        localCustomHeader,
                        localOauthClientId,
                        localOauthClientSecret,
                        localAuthUrl,
                        localClientUrl,
                        localScope,
                        localTokenExchangeMethod,
                    )
                },
            ) {
                Text(stringResource(Res.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

@Composable
private fun AuthTypeRadioOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
