package com.ocrapp.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ocrapp.BuildConfig
import com.ocrapp.R
import com.ocrapp.ui.icons.ExpandLess
import com.ocrapp.ui.icons.ExpandMore
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val openFailedMessage = stringResource(R.string.settings_open_failed)
    // Whoever already chose their own endpoint finds it open; everyone else, collapsed.
    var advancedExpanded by rememberSaveable(state.isLoaded) { mutableStateOf(state.useDirect) }

    val messageRes = state.messageRes
    if (messageRes != null) {
        val message = stringResource(messageRes)
        LaunchedEffect(messageRes) {
            snackbarHostState.showSnackbar(message)
            viewModel.onMessageShown(messageRes)
        }
    }

    val signInPage = state.signInPage
    LaunchedEffect(signInPage) {
        if (signInPage == null) return@LaunchedEffect
        // The ordinary browser, outside this app: the page is GitHub's, not ours, and the
        // user may already be signed in to GitHub there.
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(signInPage)).addCategory(Intent.CATEGORY_BROWSABLE)
        val opened = runCatching { context.startActivity(open) }.isSuccess
        viewModel.onSignInPageOpened(opened)
        // Not in this effect, which the cleared page ends.
        if (!opened) scope.launch { snackbarHostState.showSnackbar(openFailedMessage) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_runpod_header),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ManagedSection(
                state = state,
                onSignIn = viewModel::signIn,
                onCancel = viewModel::cancelSignIn,
                onDisconnect = viewModel::disconnect,
                onTest = viewModel::testManagedConnection,
            )

            HorizontalDivider()

            AdvancedSection(
                state = state,
                expanded = advancedExpanded,
                onToggle = { advancedExpanded = !advancedExpanded },
                onUseDirectChange = viewModel::onUseDirectChange,
                onEndpointChange = viewModel::onEndpointChange,
                onApiKeyChange = viewModel::onApiKeyChange,
                onSave = viewModel::save,
                onTest = viewModel::testConnection,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The GitHub sign-in: the way to the managed service, approved on github.com in the browser. */
@Composable
private fun ManagedSection(
    state: SettingsUiState,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
    onTest: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_managed_title),
                style = MaterialTheme.typography.titleSmall,
            )

            when (val managed = state.managed) {
                ManagedState.SignedOut -> {
                    Text(
                        text = stringResource(R.string.settings_managed_signed_out),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onSignIn, enabled = state.canSignIn) {
                        Text(stringResource(R.string.settings_managed_sign_in))
                    }
                }

                ManagedState.InBrowser -> {
                    SignInProgress(stringResource(R.string.settings_managed_in_browser))
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }

                ManagedState.Finishing -> SignInProgress(stringResource(R.string.settings_managed_finishing))

                is ManagedState.Connected -> {
                    val expires = remember(managed.expiresAtSeconds) {
                        DateFormat.getDateInstance(DateFormat.MEDIUM)
                            .format(Date(managed.expiresAtSeconds * MILLIS_PER_SECOND))
                    }
                    Text(
                        text = stringResource(R.string.settings_managed_connected, expires),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onTest, enabled = state.canTestManaged) {
                            Text(stringResource(R.string.settings_test))
                        }
                        if (state.managedCheck == ConnectionState.Testing) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDisconnect) {
                            Text(stringResource(R.string.settings_managed_disconnect))
                        }
                    }
                    ConnectionMessage(state.managedCheck, managed = true)
                }

                ManagedState.Expired -> {
                    Text(
                        text = stringResource(R.string.settings_managed_expired),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onSignIn, enabled = state.canSignIn) {
                        Text(stringResource(R.string.settings_managed_sign_in_again))
                    }
                }

                is ManagedState.Failed -> {
                    Text(
                        text = stringResource(managed.messageRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onSignIn, enabled = state.canSignIn) {
                        Text(stringResource(R.string.settings_managed_try_again))
                    }
                }
            }
        }
    }
}

@Composable
private fun SignInProgress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Optional and folded away: pages sent straight to a RunPod endpoint of the user's own. */
@Composable
private fun AdvancedSection(
    state: SettingsUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onUseDirectChange: (Boolean) -> Unit,
    onEndpointChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_advanced_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.settings_advanced_collapse else R.string.settings_advanced_expand,
            ),
        )
    }
    if (!expanded) return

    Text(
        text = stringResource(R.string.settings_advanced_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = state.useDirect,
                enabled = state.canEdit,
                role = Role.Switch,
                onValueChange = onUseDirectChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_use_own_endpoint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = state.useDirect, onCheckedChange = null, enabled = state.canEdit)
    }

    OutlinedTextField(
        value = state.endpointId,
        onValueChange = onEndpointChange,
        enabled = state.canEdit,
        label = { Text(stringResource(R.string.settings_endpoint_id)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
    )

    OutlinedTextField(
        value = state.apiKey,
        onValueChange = onApiKeyChange,
        enabled = state.canEdit,
        label = { Text(stringResource(R.string.settings_api_key)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onSave, enabled = state.canEdit) {
            Text(stringResource(R.string.action_save))
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onTest, enabled = state.canTest) {
            Text(stringResource(R.string.settings_test))
        }
        if (state.connection == ConnectionState.Testing) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }

    ConnectionMessage(state.connection, managed = false)
}

@Composable
private fun ConnectionMessage(connection: ConnectionState, managed: Boolean) {
    val text = when (connection) {
        ConnectionState.Unknown, ConnectionState.Testing -> return

        is ConnectionState.Reachable -> connection.workers
            ?.let { stringResource(R.string.settings_reachable_workers, it.ready, it.idle, it.running) }
            ?: stringResource(R.string.settings_reachable)

        ConnectionState.Unauthorized -> stringResource(R.string.settings_error_unauthorized)
        ConnectionState.EndpointNotFound -> stringResource(R.string.settings_error_not_found)
        ConnectionState.NoNetwork -> stringResource(
            R.string.settings_unreachable,
            stringResource(if (managed) R.string.settings_managed_error_network else R.string.settings_error_network),
        )

        is ConnectionState.Failed -> stringResource(
            R.string.settings_unreachable,
            connection.detail.orEmpty(),
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (connection is ConnectionState.Reachable) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
    )
}

private const val MILLIS_PER_SECOND = 1_000L
