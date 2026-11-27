package com.ocrapp.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ocrapp.R
import com.ocrapp.auth.ManagedSignIn
import com.ocrapp.auth.SignInOutcome
import com.ocrapp.auth.SignInState
import com.ocrapp.data.SettingsStore
import com.ocrapp.ocr.BackendSettings
import com.ocrapp.ocr.BackendUnavailableException
import com.ocrapp.ocr.ConnectionLookup
import com.ocrapp.ocr.ConnectionMode
import com.ocrapp.ocr.FallbackReason
import com.ocrapp.ocr.OcrConnection
import com.ocrapp.ocr.RemoteOcrEngine
import com.ocrapp.ocr.RunPodCredentials
import com.ocrapp.ocr.RunPodWorkers
import com.ocrapp.ocr.runCatchingCancellable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject

/** The outcome of the last connection test, if there was one. */
sealed interface ConnectionState {
    data object Unknown : ConnectionState

    data object Testing : ConnectionState

    data class Reachable(val workers: RunPodWorkers?) : ConnectionState

    /** RunPod answered, and said the key is not valid for this endpoint. */
    data object Unauthorized : ConnectionState

    /** RunPod answered, and has no endpoint with this ID. */
    data object EndpointNotFound : ConnectionState

    /** Nothing answered at all. */
    data object NoNetwork : ConnectionState

    data class Failed(val detail: String?) : ConnectionState
}

/**
 * Where the managed service's GitHub sign-in stands.
 *
 * Nothing secret from a sign-in in progress is held here: its `state` and verifier stay
 * inside [ManagedSignIn] and the store.
 */
sealed interface ManagedState {
    data object SignedOut : ManagedState

    /** GitHub's page is open in the browser; the app waits for GitHub to send the user back. */
    data object InBrowser : ManagedState

    /** GitHub sent the user back; the service is checking the sign-in. */
    data object Finishing : ManagedState

    data class Connected(val expiresAtSeconds: Long) : ManagedState

    /** There was a sign-in, and it has run out or was refused. */
    data object Expired : ManagedState

    data class Failed(@StringRes val messageRes: Int) : ManagedState
}

data class SettingsUiState(
    /** False until the stored values have been read; the fields are locked until then. */
    val isLoaded: Boolean = false,
    val managed: ManagedState = ManagedState.SignedOut,
    /** The outcome of the last check of the managed service. */
    val managedCheck: ConnectionState = ConnectionState.Unknown,
    /** Whether Document parsing goes to the user's own RunPod endpoint instead. */
    val useDirect: Boolean = false,
    val endpointId: String = "",
    val apiKey: String = "",
    val connection: ConnectionState = ConnectionState.Unknown,
    val isSaving: Boolean = false,
    @StringRes val messageRes: Int? = null,
    /**
     * GitHub's sign-in page, for the screen to open once in the browser and then report
     * to [SettingsViewModel.onSignInPageOpened].
     */
    val signInPage: String? = null,
) {
    val credentials: RunPodCredentials
        get() = RunPodCredentials(endpointId.trim(), apiKey.trim())

    val canEdit: Boolean
        get() = isLoaded && !isSaving && connection != ConnectionState.Testing

    val canTest: Boolean
        get() = canEdit && endpointId.isNotBlank() && apiKey.isNotBlank()

    val isSigningIn: Boolean
        get() = managed is ManagedState.InBrowser || managed is ManagedState.Finishing

    val canSignIn: Boolean
        get() = isLoaded && !isSigningIn && managed !is ManagedState.Connected &&
            managedCheck != ConnectionState.Testing

    val canTestManaged: Boolean
        get() = isLoaded && managed is ManagedState.Connected && managedCheck != ConnectionState.Testing
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val remoteEngine: RemoteOcrEngine,
    private val managedSignIn: ManagedSignIn,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** What the sign-in section shows whenever no sign-in is running. */
    private var restingManaged: ManagedState = ManagedState.SignedOut
    private var cancelingSignIn = false

    init {
        viewModelScope.launch {
            val stored = runCatchingCancellable { settings.load() }.getOrElse {
                _state.update { it.copy(messageRes = R.string.settings_read_failed) }
                return@launch
            }
            restingManaged = stored.managedState()
            _state.update {
                it.copy(
                    isLoaded = true,
                    // A sign-in that moved on while the settings were read keeps its state.
                    managed = if (it.managed == ManagedState.SignedOut) restingManaged else it.managed,
                    useDirect = stored.mode == ConnectionMode.DIRECT,
                    endpointId = stored.direct.endpointId,
                    apiKey = stored.direct.apiKey,
                )
            }
        }
        // The sign-in outlives this screen: it can finish while the screen is closed,
        // and a screen opened meanwhile picks it up where it stands.
        viewModelScope.launch {
            managedSignIn.state.collect { onSignInState(it) }
        }
    }

    fun signIn() {
        if (cancelingSignIn || !_state.value.canSignIn) return
        _state.update { it.copy(managed = ManagedState.InBrowser, managedCheck = ConnectionState.Unknown) }
        viewModelScope.launch {
            val page = managedSignIn.start()
            if (cancelingSignIn) return@launch
            if (page == null) {
                showSignInFailure(R.string.settings_signin_error_not_started)
            } else {
                // Cancelled before the page was ready: it is not opened at all.
                _state.update { if (it.managed == ManagedState.InBrowser) it.copy(signInPage = page) else it }
            }
        }
    }

    /** The screen opened [SettingsUiState.signInPage] in a browser, or found none to open it. */
    fun onSignInPageOpened(opened: Boolean) {
        _state.update { it.copy(signInPage = null) }
        if (!opened) cancelSignIn()
    }

    private fun onSignInState(signIn: SignInState) {
        when (signIn) {
            // Every way back to idle is one this screen started itself.
            SignInState.Idle -> Unit
            SignInState.InBrowser -> _state.update { it.copy(managed = ManagedState.InBrowser) }
            SignInState.Exchanging -> _state.update { it.copy(managed = ManagedState.Finishing, signInPage = null) }
            is SignInState.Finished -> {
                finishSignIn(signIn.outcome)
                managedSignIn.acknowledge(signIn)
            }
        }
    }

    private fun finishSignIn(outcome: SignInOutcome) {
        when (outcome) {
            is SignInOutcome.Authorized -> {
                restingManaged = ManagedState.Connected(outcome.session.expiresAtSeconds)
                _state.update {
                    // Signing in selects the managed service, as the store records.
                    it.copy(managed = restingManaged, useDirect = false, signInPage = null)
                }
            }

            SignInOutcome.Expired -> showSignInFailure(R.string.settings_signin_error_expired)
            SignInOutcome.Denied -> showSignInFailure(R.string.settings_signin_error_denied)
            SignInOutcome.Forbidden -> showSignInFailure(R.string.settings_signin_error_forbidden)
            SignInOutcome.Mismatch -> showSignInFailure(R.string.settings_signin_error_mismatch)
            SignInOutcome.Unreachable -> showSignInFailure(R.string.settings_signin_error_network)
            SignInOutcome.ServiceError -> showSignInFailure(R.string.settings_signin_error_service)
            SignInOutcome.NotSaved -> showSignInFailure(R.string.settings_signin_error_storage)
        }
    }

    private fun showSignInFailure(@StringRes messageRes: Int) {
        _state.update { it.copy(managed = ManagedState.Failed(messageRes), signInPage = null) }
    }

    /** Stops waiting for GitHub: its answer to this sign-in is then ignored. */
    fun cancelSignIn() {
        if (_state.value.managed != ManagedState.InBrowser || cancelingSignIn) return
        cancelingSignIn = true
        _state.update { it.copy(signInPage = null) }
        viewModelScope.launch {
            try {
                managedSignIn.cancel()
            } finally {
                _state.update {
                    if (it.managed == ManagedState.InBrowser) it.copy(managed = restingManaged) else it
                }
                cancelingSignIn = false
            }
        }
    }

    /** Ends the managed session at the service, best effort, and removes it from this device. */
    fun disconnect() {
        if (_state.value.managed !is ManagedState.Connected) return
        viewModelScope.launch {
            val cleared = runCatchingCancellable {
                withContext(NonCancellable) {
                    // At the service first, while the device still holds the session to name.
                    runCatchingCancellable { settings.load().session }.getOrNull()
                        ?.let { managedSignIn.signOut(it) }
                    settings.clearSession()
                }
            }.getOrDefault(false)
            if (cleared) {
                restingManaged = ManagedState.SignedOut
                _state.update { it.copy(managed = ManagedState.SignedOut, managedCheck = ConnectionState.Unknown) }
            } else {
                _state.update { it.copy(messageRes = R.string.settings_save_failed) }
            }
        }
    }

    /** Asks the managed service whether it accepts the stored session. */
    fun testManagedConnection() {
        if (!_state.value.canTestManaged) return
        _state.update { it.copy(managedCheck = ConnectionState.Testing) }
        viewModelScope.launch {
            val lookup = runCatchingCancellable { settings.managedLookup() }.getOrNull()
            val connection = (lookup as? ConnectionLookup.Available)?.connection as? OcrConnection.Managed
            if (connection == null) {
                // Nothing to ask with: the session ran out while this screen was open, or
                // could not be read.
                when (lookup) {
                    ConnectionLookup.SessionExpired -> sessionEnded(ManagedState.Expired)
                    ConnectionLookup.NotConfigured -> sessionEnded(ManagedState.SignedOut)
                    else -> _state.update { it.copy(managedCheck = ConnectionState.Failed(null)) }
                }
                return@launch
            }
            remoteEngine.testConnection(connection).fold(
                onSuccess = { health ->
                    _state.update { it.copy(managedCheck = ConnectionState.Reachable(health.workers)) }
                },
                onFailure = { error ->
                    if (error is BackendUnavailableException && error.reason == FallbackReason.SESSION_EXPIRED) {
                        sessionEnded(ManagedState.Expired)
                    } else {
                        _state.update { it.copy(managedCheck = error.toManagedCheck()) }
                    }
                },
            )
        }
    }

    private fun sessionEnded(managed: ManagedState) {
        restingManaged = managed
        _state.update { it.copy(managed = managed, managedCheck = ConnectionState.Unknown) }
    }

    fun onUseDirectChange(value: Boolean) {
        _state.update { if (it.canEdit) it.copy(useDirect = value) else it }
    }

    fun onEndpointChange(value: String) {
        _state.update {
            if (it.canEdit) it.copy(endpointId = value, connection = ConnectionState.Unknown) else it
        }
    }

    fun onApiKeyChange(value: String) {
        _state.update {
            if (it.canEdit) it.copy(apiKey = value, connection = ConnectionState.Unknown) else it
        }
    }

    fun save() {
        val current = _state.value
        if (!current.canEdit) return
        val credentials = current.credentials
        if (current.useDirect && !credentials.isComplete) {
            _state.update { it.copy(messageRes = R.string.settings_direct_incomplete) }
            return
        }
        val mode = if (current.useDirect) ConnectionMode.DIRECT else ConnectionMode.MANAGED
        _state.update { it.copy(isSaving = true, messageRes = null) }
        viewModelScope.launch {
            val saved = runCatchingCancellable { settings.saveDirect(credentials, mode) }.getOrDefault(false)
            _state.update {
                it.copy(
                    isSaving = false,
                    messageRes = if (saved) R.string.settings_saved else R.string.settings_save_failed,
                )
            }
        }
    }

    fun onMessageShown(@StringRes messageRes: Int) {
        _state.update { if (it.messageRes == messageRes) it.copy(messageRes = null) else it }
    }

    fun testConnection() {
        val current = _state.value
        if (!current.canTest) return
        val credentials = current.credentials

        _state.update { it.copy(connection = ConnectionState.Testing) }
        viewModelScope.launch {
            val connection = remoteEngine.testConnection(OcrConnection.Direct(credentials)).fold(
                onSuccess = { health -> ConnectionState.Reachable(health.workers) },
                onFailure = { error -> error.toConnectionState() },
            )
            _state.update { it.copy(connection = connection) }
        }
    }
}

private fun BackendSettings.managedState(): ManagedState = when (managed()) {
    is ConnectionLookup.Available -> session?.let { ManagedState.Connected(it.expiresAtSeconds) }
        ?: ManagedState.SignedOut
    ConnectionLookup.SessionExpired -> ManagedState.Expired
    ConnectionLookup.NotConfigured -> ManagedState.SignedOut
}

private fun Throwable.toConnectionState(): ConnectionState {
    val httpCode = (cause as? HttpException)?.code()
    return when {
        httpCode == HTTP_UNAUTHORIZED || httpCode == HTTP_FORBIDDEN -> ConnectionState.Unauthorized
        httpCode == HTTP_NOT_FOUND -> ConnectionState.EndpointNotFound
        this is BackendUnavailableException && reason == FallbackReason.UNREACHABLE ->
            ConnectionState.NoNetwork
        else -> ConnectionState.Failed(message)
    }
}

/** The managed service has no endpoint id or API key to be wrong, so only two outcomes remain. */
private fun Throwable.toManagedCheck(): ConnectionState =
    if (this is BackendUnavailableException && reason == FallbackReason.UNREACHABLE) {
        ConnectionState.NoNetwork
    } else {
        ConnectionState.Failed(message)
    }

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
