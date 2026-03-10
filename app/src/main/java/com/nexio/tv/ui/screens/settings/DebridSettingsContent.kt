@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.BuildConfig
import com.nexio.tv.R
import com.nexio.tv.data.local.PremiumizeSettingsDataStore
import com.nexio.tv.data.local.RealDebridAuthDataStore
import com.nexio.tv.data.repository.PremiumizeService
import com.nexio.tv.data.repository.RealDebridAuthService
import com.nexio.tv.data.repository.RealDebridTokenPollResult
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

internal enum class DebridConnectionMode {
    UNAVAILABLE,
    DISCONNECTED,
    AWAITING_APPROVAL,
    CONNECTED
}

internal data class DebridUiState(
    val realDebridMode: DebridConnectionMode = DebridConnectionMode.DISCONNECTED,
    val realDebridUsername: String? = null,
    val realDebridUserCode: String? = null,
    val realDebridVerificationUrl: String? = null,
    val premiumizeConnected: Boolean = false,
    val premiumizeCustomerId: Int? = null
)

@Composable
fun DebridSettingsContent(
    viewModel: DebridSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val savingPremiumize by viewModel.savingPremiumize.collectAsStateWithLifecycle()
    val premiumizeApiKey by viewModel.premiumizeApiKey.collectAsStateWithLifecycle(initialValue = "")
    val context = LocalContext.current
    var showPremiumizeDialog by remember { mutableStateOf(false) }
    val premiumizeCustomerId = uiState.premiumizeCustomerId

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.debrid_title),
            subtitle = stringResource(R.string.debrid_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "debrid_rd") {
                    SettingsActionRow(
                        title = stringResource(R.string.debrid_real_debrid_title),
                        subtitle = when (uiState.realDebridMode) {
                            DebridConnectionMode.UNAVAILABLE -> stringResource(R.string.debrid_real_debrid_unavailable)
                            DebridConnectionMode.CONNECTED -> stringResource(
                                R.string.debrid_real_debrid_connected_as,
                                uiState.realDebridUsername ?: stringResource(R.string.debrid_connected)
                            )
                            DebridConnectionMode.AWAITING_APPROVAL -> stringResource(R.string.debrid_real_debrid_waiting)
                            DebridConnectionMode.DISCONNECTED -> stringResource(R.string.debrid_real_debrid_description)
                        },
                        value = when (uiState.realDebridMode) {
                            DebridConnectionMode.UNAVAILABLE -> stringResource(R.string.debrid_unavailable)
                            DebridConnectionMode.CONNECTED -> stringResource(R.string.debrid_disconnect_action)
                            DebridConnectionMode.AWAITING_APPROVAL -> uiState.realDebridUserCode ?: stringResource(R.string.debrid_pending)
                            DebridConnectionMode.DISCONNECTED -> stringResource(R.string.debrid_connect_action)
                        },
                        enabled = uiState.realDebridMode != DebridConnectionMode.UNAVAILABLE,
                        onClick = {
                            when (uiState.realDebridMode) {
                                DebridConnectionMode.CONNECTED -> viewModel.disconnectRealDebrid()
                                DebridConnectionMode.AWAITING_APPROVAL -> viewModel.pollRealDebrid()
                                DebridConnectionMode.DISCONNECTED -> viewModel.startRealDebrid()
                                DebridConnectionMode.UNAVAILABLE -> Unit
                            }
                        },
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }

                if (uiState.realDebridMode == DebridConnectionMode.AWAITING_APPROVAL) {
                    item(key = "debrid_rd_code") {
                        SettingsActionRow(
                            title = stringResource(R.string.debrid_activation_code_title),
                            subtitle = uiState.realDebridVerificationUrl ?: "https://real-debrid.com/device",
                            value = uiState.realDebridUserCode ?: "",
                            onClick = { viewModel.pollRealDebrid() }
                        )
                    }
                }

                item(key = "debrid_pm") {
                    SettingsActionRow(
                        title = stringResource(R.string.debrid_premiumize_title),
                        subtitle = stringResource(R.string.debrid_premiumize_description),
                        value = when {
                            uiState.premiumizeConnected && premiumizeCustomerId != null ->
                                stringResource(R.string.debrid_premiumize_connected_customer, premiumizeCustomerId)
                            uiState.premiumizeConnected -> stringResource(R.string.debrid_connected)
                            else -> maskApiKey(premiumizeApiKey, stringResource(R.string.mdblist_not_set))
                        },
                        onClick = { showPremiumizeDialog = true }
                    )
                }
            }
        }
    }

    if (showPremiumizeDialog) {
        PremiumizeApiKeyDialog(
            currentValue = premiumizeApiKey,
            saving = savingPremiumize,
            onSave = { value, onSuccess -> viewModel.savePremiumizeApiKey(value, onSuccess) },
            onClear = {
                viewModel.savePremiumizeApiKey("") { }
                showPremiumizeDialog = false
            },
            onDismiss = { showPremiumizeDialog = false }
        )
    }
}

@Composable
private fun PremiumizeApiKeyDialog(
    currentValue: String,
    saving: Boolean,
    onSave: (String, onSuccess: () -> Unit) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.debrid_premiumize_key_title),
        subtitle = stringResource(R.string.debrid_premiumize_key_subtitle),
        width = 700.dp
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NexioColors.BackgroundElevated,
                focusedContainerColor = NexioColors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(1.dp, NexioColors.Border),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = androidx.compose.foundation.BorderStroke(2.dp, NexioColors.FocusRing),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NexioColors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NexioColors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.debrid_api_key_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NexioColors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundElevated,
                    contentColor = NexioColors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_cancel)) }

            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onClear,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundElevated,
                    contentColor = NexioColors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_clear)) }

            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (!saving) {
                        onSave(value) { onDismiss() }
                    }
                },
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary
                )
            ) { Text(if (saving) stringResource(R.string.action_saving) else stringResource(R.string.action_save)) }
        }
    }
}

private fun maskApiKey(key: String, notSetLabel: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}

@HiltViewModel
class DebridSettingsViewModel @Inject constructor(
    private val realDebridAuthService: RealDebridAuthService,
    realDebridAuthDataStore: RealDebridAuthDataStore,
    private val premiumizeService: PremiumizeService,
    premiumizeSettingsDataStore: PremiumizeSettingsDataStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(DebridUiState())
    internal val uiState: StateFlow<DebridUiState> = _uiState.asStateFlow()
    internal val savingPremiumize = MutableStateFlow(false)
    internal val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    internal val premiumizeApiKey = premiumizeSettingsDataStore.settings.map { it.apiKey }

    init {
        viewModelScope.launch {
            combine(
                realDebridAuthDataStore.state,
                premiumizeService.observeAccountState()
            ) { realDebridState, premiumizeState ->
                DebridUiState(
                    realDebridMode = when {
                        realDebridState.isAuthenticated -> DebridConnectionMode.CONNECTED
                        !realDebridState.deviceCode.isNullOrBlank() -> DebridConnectionMode.AWAITING_APPROVAL
                        BuildConfig.REAL_DEBRID_CLIENT_ID.isBlank() -> DebridConnectionMode.UNAVAILABLE
                        else -> DebridConnectionMode.DISCONNECTED
                    },
                    realDebridUsername = realDebridState.username,
                    realDebridUserCode = realDebridState.userCode,
                    realDebridVerificationUrl = realDebridState.verificationUrl,
                    premiumizeConnected = premiumizeState.isConnected,
                    premiumizeCustomerId = premiumizeState.customerId
                )
            }.collect { _uiState.value = it }
        }

        viewModelScope.launch {
            premiumizeService.refreshAccountState()
        }
    }

    fun startRealDebrid() {
        viewModelScope.launch {
            realDebridAuthService.startDeviceAuth()
                .onFailure { error ->
                    messages.tryEmit(error.message ?: "Failed to start Real-Debrid auth")
                }
        }
    }

    fun pollRealDebrid() {
        viewModelScope.launch {
            when (val result = realDebridAuthService.pollDeviceToken()) {
                RealDebridTokenPollResult.Pending -> messages.tryEmit("Real-Debrid approval still pending")
                RealDebridTokenPollResult.Expired -> messages.tryEmit("Real-Debrid device code expired")
                RealDebridTokenPollResult.Denied -> messages.tryEmit("Real-Debrid authorization denied")
                is RealDebridTokenPollResult.Approved -> {
                    messages.tryEmit("Connected to Real-Debrid as ${result.username ?: "user"}")
                }
                is RealDebridTokenPollResult.Failed -> messages.tryEmit(result.reason)
            }
        }
    }

    fun disconnectRealDebrid() {
        viewModelScope.launch {
            realDebridAuthService.revokeAndLogout()
            messages.tryEmit("Disconnected from Real-Debrid")
        }
    }

    fun savePremiumizeApiKey(value: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            savingPremiumize.value = true
            premiumizeService.validateAndSaveApiKey(value)
                .onSuccess {
                    messages.tryEmit(
                        if (value.isBlank()) "Premiumize key cleared" else "Premiumize connected"
                    )
                    onSuccess()
                }
                .onFailure { error ->
                    messages.tryEmit(error.message ?: "Failed to save Premiumize API key")
                }
            savingPremiumize.value = false
        }
    }
}
