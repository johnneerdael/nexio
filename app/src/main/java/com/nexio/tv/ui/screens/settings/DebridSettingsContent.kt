@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import android.view.KeyEvent
import android.content.Context
import androidx.compose.ui.Alignment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.BuildConfig
import com.nexio.tv.R
import com.nexio.tv.data.local.EasyDebridSettingsDataStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.PremiumizeSettingsDataStore
import com.nexio.tv.data.local.RealDebridAuthDataStore
import com.nexio.tv.data.local.TorBoxSettingsDataStore
import com.nexio.tv.data.repository.EasyDebridService
import com.nexio.tv.data.repository.PremiumizeService
import com.nexio.tv.data.repository.RealDebridSettingsAuthGateway
import com.nexio.tv.data.repository.RealDebridTokenPollResult
import com.nexio.tv.data.repository.TorBoxAuthService
import com.nexio.tv.data.repository.TorBoxDeviceFlow
import com.nexio.tv.data.repository.TorBoxService
import com.nexio.tv.data.repository.TorBoxStartDeviceFlowResult
import com.nexio.tv.data.repository.TorBoxTokenPollResult
import com.nexio.tv.data.repository.benchmark.CollectorPublicDashboardLinkProvider
import com.nexio.tv.data.repository.benchmark.CollectorPublicDashboardLinkResult
import com.nexio.tv.core.qr.QrCodeGenerator
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
    val premiumizeCustomerId: Int? = null,
    val serviceWrapEnabled: Boolean = false,
    val serviceWrapAvailable: Boolean = false,
    val shadowAutoplayDataCollectionEnabled: Boolean = false,
    val collectorDashboardUrl: String? = null,
    val collectorDashboardUnavailableReason: CollectorPublicDashboardLinkResult.Reason? = null,
    val collectorDashboardAvailable: Boolean = false,
    val deterministicAutoplayEnabled: Boolean = false,
    val deterministicAutoplayAvailable: Boolean = false,
    val torBoxConnected: Boolean = false,
    val torBoxEmail: String? = null,
    val torBoxPlan: String? = null,
    val easyDebridConnected: Boolean = false,
    val easyDebridUserId: String? = null,
    val easyDebridPaidUntil: String? = null,
)

private data class DebridConnectionSnapshot(
    val realDebridMode: DebridConnectionMode,
    val realDebridUsername: String?,
    val realDebridUserCode: String?,
    val realDebridVerificationUrl: String?,
    val premiumizeConnected: Boolean,
    val premiumizeCustomerId: Int?,
    val torBoxConnected: Boolean,
    val torBoxEmail: String?,
    val torBoxPlan: String?,
    val easyDebridConnected: Boolean,
    val easyDebridUserId: String?,
    val easyDebridPaidUntil: String?
)

private data class DebridCollectorDashboardSnapshot(
    val collectorDashboardUrl: String?,
    val collectorDashboardUnavailableReason: CollectorPublicDashboardLinkResult.Reason?,
    val collectorDashboardAvailable: Boolean
)

private data class DebridSettingsToggleSnapshot(
    val deterministicAutoplayEnabled: Boolean,
    val deterministicAutoplayAvailable: Boolean,
    val serviceWrapEnabled: Boolean,
    val shadowAutoplayDataCollectionEnabled: Boolean
)

private data class DebridPlayerSettingsSnapshot(
    val deterministicAutoplayEnabled: Boolean,
    val serviceWrapEnabled: Boolean,
    val shadowAutoplayDataCollectionEnabled: Boolean
)

private data class DebridUiBaseSnapshot(
    val connection: DebridConnectionSnapshot
)

@Composable
internal fun DebridSettingsContent(
    viewModel: DebridSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val savingPremiumize by viewModel.savingPremiumize.collectAsStateWithLifecycle()
    val savingTorBox by viewModel.savingTorBox.collectAsStateWithLifecycle()
    val savingEasyDebrid by viewModel.savingEasyDebrid.collectAsStateWithLifecycle()
    val premiumizeApiKey by viewModel.premiumizeApiKey.collectAsStateWithLifecycle(initialValue = "")
    val torBoxApiKey by viewModel.torBoxApiKey.collectAsStateWithLifecycle(initialValue = "")
    val easyDebridApiKey by viewModel.easyDebridApiKey.collectAsStateWithLifecycle(initialValue = "")
    val context = LocalContext.current
    var showPremiumizeDialog by remember { mutableStateOf(false) }
    var showTorBoxDialog by remember { mutableStateOf(false) }
    var showEasyDebridDialog by remember { mutableStateOf(false) }
    val showRealDebridActivationQr = uiState.realDebridMode == DebridConnectionMode.AWAITING_APPROVAL
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

                item(key = "debrid_service_wrap") {
                    SettingsToggleRow(
                        title = stringResource(R.string.debrid_service_wrap_title),
                        subtitle = if (uiState.serviceWrapAvailable) {
                            "Only show debrid-wrapped cached links in deterministic playback flows"
                        } else {
                            "Requires at least one connected debrid provider"
                        },
                        checked = uiState.serviceWrapEnabled,
                        enabled = uiState.serviceWrapAvailable,
                        onToggle = {
                            viewModel.setServiceWrapEnabled(!uiState.serviceWrapEnabled)
                        }
                    )
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
                item(key = "debrid_tb") {
                    SettingsActionRow(
                        title = stringResource(R.string.debrid_torbox_title),
                        subtitle = stringResource(R.string.debrid_torbox_description),
                        value = when {
                            uiState.torBoxConnected -> buildString {
                                append(uiState.torBoxEmail ?: stringResource(R.string.debrid_connected))
                                uiState.torBoxPlan?.takeIf { it.isNotBlank() }?.let { plan ->
                                    append(" • ")
                                    append(plan)
                                }
                            }
                            else -> maskApiKey(torBoxApiKey, stringResource(R.string.mdblist_not_set))
                        },
                        onClick = { showTorBoxDialog = true }
                    )
                }
                item(key = "debrid_ed") {
                    SettingsActionRow(
                        title = stringResource(R.string.debrid_easydebrid_title),
                        subtitle = stringResource(R.string.debrid_easydebrid_description),
                        value = when {
                            uiState.easyDebridConnected && !uiState.easyDebridUserId.isNullOrBlank() ->
                                stringResource(R.string.debrid_easydebrid_connected_user, uiState.easyDebridUserId ?: "")
                            uiState.easyDebridConnected -> stringResource(R.string.debrid_connected)
                            else -> maskApiKey(easyDebridApiKey, stringResource(R.string.mdblist_not_set))
                        },
                        onClick = { showEasyDebridDialog = true }
                    )
                }
            }
        }
    }

    if (showPremiumizeDialog) {
        DebridApiKeyDialog(
            title = stringResource(R.string.debrid_premiumize_key_title),
            subtitle = stringResource(R.string.debrid_premiumize_key_subtitle),
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

    val torBoxPairing by viewModel.torBoxPairing.collectAsStateWithLifecycle()

    if (showTorBoxDialog) {
        TorBoxConnectionDialog(
            currentValue = torBoxApiKey,
            saving = savingTorBox,
            onPair = { viewModel.startTorBoxDevicePairing() },
            onSave = { value, onSuccess -> viewModel.saveTorBoxApiKey(value, onSuccess) },
            onClear = {
                viewModel.saveTorBoxApiKey("") { }
                showTorBoxDialog = false
            },
            onDismiss = { showTorBoxDialog = false }
        )
    }

    if (torBoxPairing !is TorBoxPairingUiState.Idle) {
        TorBoxDevicePairingDialog(
            state = torBoxPairing,
            onCheckNow = { viewModel.pollTorBoxNow() },
            onDismiss = { viewModel.cancelTorBoxDevicePairing() }
        )
    }

    if (showEasyDebridDialog) {
        DebridApiKeyDialog(
            title = stringResource(R.string.debrid_easydebrid_key_title),
            subtitle = stringResource(R.string.debrid_easydebrid_key_subtitle),
            currentValue = easyDebridApiKey,
            saving = savingEasyDebrid,
            onSave = { value, onSuccess -> viewModel.saveEasyDebridApiKey(value, onSuccess) },
            onClear = {
                viewModel.saveEasyDebridApiKey("") { }
                showEasyDebridDialog = false
            },
            onDismiss = { showEasyDebridDialog = false }
        )
    }

    if (showRealDebridActivationQr) {
        RealDebridActivationQrDialog(
            url = uiState.realDebridVerificationUrl,
            onDismiss = {}
        )
    }
}

@Composable
private fun RealDebridActivationQrDialog(
    url: String?,
    onDismiss: () -> Unit
) {
    val qrBitmap = remember(url) {
        runCatching { url?.let { QrCodeGenerator.generate(it, 420) } }.getOrNull()
    }

    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.debrid_rd_activation_qr_title),
        subtitle = stringResource(R.string.debrid_rd_activation_qr_subtitle),
        width = 700.dp
    ) {
        if (qrBitmap == null) {
            Text(
                text = url ?: "",
                style = MaterialTheme.typography.bodyLarge,
                color = NexioColors.TextPrimary,
                textAlign = TextAlign.Center
            )
            return@NexioDialog
        }

        Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.cd_real_debrid_activation_qr),
            modifier = Modifier
                .heightIn(max = 260.dp)
                .fillMaxWidth(),
            alignment = Alignment.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.debrid_rd_activation_qr_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = NexioColors.TextSecondary,
            textAlign = TextAlign.Center
        )

        SettingsActionButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun TorBoxConnectionDialog(
    currentValue: String,
    saving: Boolean,
    onPair: () -> Unit,
    onSave: (String, onSuccess: () -> Unit) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var pasteExpanded by remember { mutableStateOf(currentValue.isNotBlank()) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.debrid_torbox_key_title),
        subtitle = stringResource(R.string.debrid_torbox_key_subtitle),
        width = 700.dp
    ) {
        SettingsActionButton(
            onClick = {
                onPair()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.debrid_torbox_pair_action))
        }

        SettingsActionButton(
            onClick = { pasteExpanded = !pasteExpanded },
            surface = SettingsButtonSurface.BackgroundElevated,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.debrid_torbox_pair_or_paste))
        }

        if (pasteExpanded) {
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
                        shape = RoundedCornerShape(10.dp)
                    ),
                    focusedBorder = Border(
                        border = androidx.compose.foundation.BorderStroke(2.dp, NexioColors.FocusRing),
                        shape = RoundedCornerShape(10.dp)
                    )
                ),
                shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
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
                            else Color.Transparent
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
                SettingsActionButton(
                    onClick = onDismiss,
                    surface = SettingsButtonSurface.BackgroundElevated
                ) { Text(stringResource(R.string.action_cancel)) }
                Spacer(modifier = Modifier.width(8.dp))
                SettingsActionButton(
                    onClick = onClear,
                    surface = SettingsButtonSurface.BackgroundElevated
                ) { Text(stringResource(R.string.action_clear)) }
                Spacer(modifier = Modifier.width(8.dp))
                SettingsActionButton(
                    onClick = {
                        if (!saving) {
                            onSave(value) { onDismiss() }
                        }
                    },
                    enabled = !saving
                ) {
                    Text(if (saving) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
                }
            }
        } else {
            SettingsActionButton(
                onClick = onDismiss,
                surface = SettingsButtonSurface.BackgroundElevated,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun TorBoxDevicePairingDialog(
    state: TorBoxPairingUiState,
    onCheckNow: () -> Unit,
    onDismiss: () -> Unit
) {
    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.debrid_torbox_qr_title),
        subtitle = stringResource(R.string.debrid_torbox_qr_subtitle),
        width = 700.dp
    ) {
        when (state) {
            is TorBoxPairingUiState.Idle -> Unit

            is TorBoxPairingUiState.Starting -> {
                Text(
                    text = stringResource(R.string.debrid_torbox_qr_starting),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NexioColors.TextPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingsActionButton(
                    onClick = onDismiss,
                    surface = SettingsButtonSurface.BackgroundElevated,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.action_cancel)) }
            }

            is TorBoxPairingUiState.Error -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Red,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingsActionButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.action_cancel)) }
            }

            is TorBoxPairingUiState.AwaitingApproval -> {
                val flow = state.flow
                // Prefer the friendly verification URL for QR — it's shorter and renders crisper.
                val qrTarget = flow.friendlyVerificationUrl.ifBlank { flow.verificationUrl }
                val qrBitmap = remember(qrTarget) {
                    runCatching { QrCodeGenerator.generate(qrTarget, 420) }.getOrNull()
                }

                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_torbox_activation_qr),
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .fillMaxWidth(),
                        alignment = Alignment.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Text(
                    text = flow.userCode,
                    style = MaterialTheme.typography.headlineMedium,
                    color = NexioColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = qrTarget,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexioColors.TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.debrid_torbox_qr_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Live, second-grained countdown without retaining the millisecond timestamp in continuations.
                val expiryLabel = produceTorBoxExpiryLabel(flow.expiresAtMillis)
                Text(
                    text = expiryLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexioColors.TextTertiary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    SettingsActionButton(
                        onClick = onDismiss,
                        surface = SettingsButtonSurface.BackgroundElevated
                    ) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(modifier = Modifier.width(8.dp))
                    SettingsActionButton(onClick = onCheckNow) {
                        Text(stringResource(R.string.action_check_now))
                    }
                }
            }
        }
    }
}

@Composable
private fun produceTorBoxExpiryLabel(expiresAtMillis: Long): String {
    val remainingMinutes by androidx.compose.runtime.produceState(
        initialValue = ((expiresAtMillis - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0),
        key1 = expiresAtMillis
    ) {
        while (true) {
            val now = System.currentTimeMillis()
            value = ((expiresAtMillis - now) / 60_000L).toInt().coerceAtLeast(0)
            if (now >= expiresAtMillis) break
            delay(30_000L)
        }
    }
    return if (remainingMinutes <= 0) {
        stringResource(R.string.debrid_torbox_qr_expiry_soon)
    } else {
        stringResource(R.string.debrid_torbox_qr_expiry, remainingMinutes)
    }
}

@Composable
internal fun CollectorDashboardQrDialog(
    url: String?,
    unavailableReason: CollectorPublicDashboardLinkResult.Reason?,
    onDismiss: () -> Unit
) {
    val qrBitmap = remember(url) {
        runCatching {
            url?.let { QrCodeGenerator.generate(it, 420) }
        }.getOrNull()
    }

    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.debrid_data_collection_qr_title),
        subtitle = stringResource(R.string.debrid_data_collection_qr_subtitle),
        width = 700.dp
    ) {
        if (unavailableReason != null) {
            Text(
                text = collectorDashboardUnavailableReason(unavailableReason),
                color = Color.Red,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            return@NexioDialog
        }

        if (qrBitmap == null) {
            Text(
                text = stringResource(R.string.debrid_data_collection_qr_unavailable),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Red,
                textAlign = TextAlign.Center
            )
            return@NexioDialog
        }

        Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.debrid_data_collection_qr_code_content_description),
            modifier = Modifier
                .heightIn(max = 260.dp)
                .fillMaxWidth(),
            alignment = Alignment.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = url ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = NexioColors.TextTertiary,
            textAlign = TextAlign.Center
        )

        SettingsActionButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun collectorDashboardUnavailableReason(reason: CollectorPublicDashboardLinkResult.Reason): String {
    return when (reason) {
        CollectorPublicDashboardLinkResult.Reason.BASE_URL_MISSING ->
            stringResource(R.string.debrid_data_collection_qr_base_url_missing)
        CollectorPublicDashboardLinkResult.Reason.ANDROID_ID_MISSING ->
            stringResource(R.string.debrid_data_collection_qr_android_id_missing)
        CollectorPublicDashboardLinkResult.Reason.HASHING_FAILED ->
            stringResource(R.string.debrid_data_collection_qr_hash_failed)
    }
}

@Composable
private fun DebridApiKeyDialog(
    title: String,
    subtitle: String,
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
        title = title,
        subtitle = subtitle,
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
            SettingsActionButton(
                onClick = onDismiss,
                surface = SettingsButtonSurface.BackgroundElevated
            ) { Text(stringResource(R.string.action_cancel)) }

            Spacer(modifier = Modifier.width(8.dp))
            SettingsActionButton(
                onClick = onClear,
                surface = SettingsButtonSurface.BackgroundElevated
            ) { Text(stringResource(R.string.action_clear)) }

            Spacer(modifier = Modifier.width(8.dp))
            SettingsActionButton(
                onClick = {
                    if (!saving) {
                        onSave(value) { onDismiss() }
                    }
                },
                enabled = !saving
            ) { Text(if (saving) stringResource(R.string.action_saving) else stringResource(R.string.action_save)) }
        }
    }
}

private fun maskApiKey(key: String, notSetLabel: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}

internal sealed interface TorBoxPairingUiState {
    data object Idle : TorBoxPairingUiState
    data object Starting : TorBoxPairingUiState
    data class AwaitingApproval(val flow: TorBoxDeviceFlow) : TorBoxPairingUiState
    data class Error(val message: String) : TorBoxPairingUiState
}

@HiltViewModel
internal class DebridSettingsViewModel @Inject internal constructor(
    @ApplicationContext private val appContext: Context,
    private val realDebridAuthService: RealDebridSettingsAuthGateway,
    realDebridAuthDataStore: RealDebridAuthDataStore,
    private val premiumizeService: PremiumizeService,
    premiumizeSettingsDataStore: PremiumizeSettingsDataStore,
    private val torBoxService: TorBoxService,
    private val torBoxAuthService: TorBoxAuthService,
    torBoxSettingsDataStore: TorBoxSettingsDataStore,
    private val easyDebridService: EasyDebridService,
    easyDebridSettingsDataStore: EasyDebridSettingsDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val collectorPublicDashboardLinkProvider: CollectorPublicDashboardLinkProvider,
) : ViewModel() {

    private var rdPollingJob: Job? = null
    private var torBoxPairingJob: Job? = null
    internal val torBoxPairing = MutableStateFlow<TorBoxPairingUiState>(TorBoxPairingUiState.Idle)
    private val _uiState = MutableStateFlow(DebridUiState())
    private val collectorDashboardLink = MutableStateFlow<CollectorPublicDashboardLinkResult>(
        CollectorPublicDashboardLinkResult.Unavailable(
            CollectorPublicDashboardLinkResult.Reason.BASE_URL_MISSING
        )
    )
    internal val uiState: StateFlow<DebridUiState> = _uiState.asStateFlow()
    internal val savingPremiumize = MutableStateFlow(false)
    internal val savingTorBox = MutableStateFlow(false)
    internal val savingEasyDebrid = MutableStateFlow(false)
    internal val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    internal val premiumizeApiKey = premiumizeSettingsDataStore.settings.map { it.apiKey }
    internal val torBoxApiKey = torBoxSettingsDataStore.settings.map { it.apiKey }
    internal val easyDebridApiKey = easyDebridSettingsDataStore.settings.map { it.apiKey }

    init {
        val connectionState = combine(
            realDebridAuthDataStore.state,
            premiumizeService.observeAccountState(),
            torBoxService.observeAccountState(),
            easyDebridService.observeAccountState()
        ) { realDebridState, premiumizeState, torBoxState, easyDebridState ->
            DebridConnectionSnapshot(
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
                premiumizeCustomerId = premiumizeState.customerId,
                torBoxConnected = torBoxState.isConnected,
                torBoxEmail = torBoxState.email,
                torBoxPlan = torBoxState.plan,
                easyDebridConnected = easyDebridState.isConnected,
                easyDebridUserId = easyDebridState.userId,
                easyDebridPaidUntil = easyDebridState.paidUntil
            )
        }

        val playerSettingsSnapshot = playerSettingsDataStore.playerSettings
            .map {
                DebridPlayerSettingsSnapshot(
                    deterministicAutoplayEnabled = it.deterministicAutoplayEnabled,
                    serviceWrapEnabled = it.serviceWrapEnabled,
                    shadowAutoplayDataCollectionEnabled = it.shadowAutoplayDataCollectionEnabled
                )
            }

        val collectorDashboardState = collectorDashboardLink.map { result ->
            when (result) {
                is CollectorPublicDashboardLinkResult.Available -> DebridCollectorDashboardSnapshot(
                    collectorDashboardUrl = result.url,
                    collectorDashboardUnavailableReason = null,
                    collectorDashboardAvailable = true
                )

                is CollectorPublicDashboardLinkResult.Unavailable -> DebridCollectorDashboardSnapshot(
                    collectorDashboardUrl = null,
                    collectorDashboardUnavailableReason = result.reason,
                    collectorDashboardAvailable = false
                )
            }
        }

        viewModelScope.launch {
            val baseState = connectionState.map { connection ->
                DebridUiBaseSnapshot(connection = connection)
            }

            val settingsState = playerSettingsSnapshot.map { playerSettings ->
                DebridSettingsToggleSnapshot(
                    deterministicAutoplayEnabled = playerSettings.deterministicAutoplayEnabled,
                    deterministicAutoplayAvailable = true,
                    serviceWrapEnabled = playerSettings.serviceWrapEnabled,
                    shadowAutoplayDataCollectionEnabled = playerSettings.shadowAutoplayDataCollectionEnabled
                )
            }

            combine(baseState, settingsState, collectorDashboardState) {
                base,
                settings,
                collectorDashboard
            ->
                DebridUiState(
                    realDebridMode = base.connection.realDebridMode,
                    realDebridUsername = base.connection.realDebridUsername,
                    realDebridUserCode = base.connection.realDebridUserCode,
                    realDebridVerificationUrl = base.connection.realDebridVerificationUrl,
                    premiumizeConnected = base.connection.premiumizeConnected,
                    premiumizeCustomerId = base.connection.premiumizeCustomerId,
                    serviceWrapEnabled = settings.serviceWrapEnabled,
                    serviceWrapAvailable = base.connection.realDebridMode == DebridConnectionMode.CONNECTED ||
                        base.connection.premiumizeConnected ||
                        base.connection.torBoxConnected ||
                        base.connection.easyDebridConnected,
                    shadowAutoplayDataCollectionEnabled = settings.shadowAutoplayDataCollectionEnabled,
                    collectorDashboardUrl = collectorDashboard.collectorDashboardUrl,
                    collectorDashboardUnavailableReason = collectorDashboard.collectorDashboardUnavailableReason,
                    collectorDashboardAvailable = collectorDashboard.collectorDashboardAvailable,
                    deterministicAutoplayEnabled = settings.deterministicAutoplayEnabled,
                    deterministicAutoplayAvailable = settings.deterministicAutoplayAvailable,
                    torBoxConnected = base.connection.torBoxConnected,
                    torBoxEmail = base.connection.torBoxEmail,
                    torBoxPlan = base.connection.torBoxPlan,
                    easyDebridConnected = base.connection.easyDebridConnected,
                    easyDebridUserId = base.connection.easyDebridUserId,
                    easyDebridPaidUntil = base.connection.easyDebridPaidUntil
                )
            }.collect { next ->
                _uiState.value = next
            }
        }

        viewModelScope.launch {
            premiumizeService.refreshAccountState()
            torBoxService.refreshAccountState()
            easyDebridService.refreshAccountState()
        }

        refreshPublicCollectorDashboardLink()

        viewModelScope.launch {
            uiState
                .map { it.realDebridMode }
                .distinctUntilChanged()
                .collect { mode ->
                    if (mode == DebridConnectionMode.AWAITING_APPROVAL) {
                        if (rdPollingJob?.isActive != true) {
                            startRdPollingLoop()
                        }
                    } else {
                        rdPollingJob?.cancel()
                        rdPollingJob = null
                    }
                }
        }

        viewModelScope.launch {
            connectionState
                .map { conn ->
                    conn.realDebridMode == DebridConnectionMode.CONNECTED ||
                        conn.premiumizeConnected ||
                        conn.torBoxConnected ||
                        conn.easyDebridConnected
                }
                .distinctUntilChanged()
                .collect { anyConnected ->
                    if (!anyConnected) {
                        setServiceWrapEnabled(false)
                    }
                }
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

    private fun startRdPollingLoop() {
        rdPollingJob?.cancel()
        rdPollingJob = viewModelScope.launch {
            while (true) {
                delay(5_000)
                when (val result = realDebridAuthService.pollDeviceToken()) {
                    RealDebridTokenPollResult.Pending -> { /* keep polling */ }
                    RealDebridTokenPollResult.Expired -> {
                        messages.tryEmit("Real-Debrid device code expired")
                        break
                    }
                    RealDebridTokenPollResult.Denied -> {
                        messages.tryEmit("Real-Debrid authorization denied")
                        break
                    }
                    is RealDebridTokenPollResult.Approved -> {
                        messages.tryEmit("Connected to Real-Debrid as ${result.username ?: "user"}")
                        break
                    }
                    is RealDebridTokenPollResult.Failed -> {
                        messages.tryEmit(result.reason)
                        break
                    }
                }
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

    fun setDeterministicAutoplayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !uiState.value.deterministicAutoplayAvailable) return@launch
            playerSettingsDataStore.setDeterministicAutoplayEnabled(enabled)
        }
    }

    fun setShadowAutoplayDataCollectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            playerSettingsDataStore.setShadowAutoplayDataCollectionEnabled(enabled)
        }
    }

    fun setServiceWrapEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !uiState.value.serviceWrapAvailable) return@launch
            playerSettingsDataStore.setServiceWrapEnabled(enabled)
        }
    }

    fun refreshPublicCollectorDashboardLink() {
        collectorDashboardLink.value = collectorPublicDashboardLinkProvider.resolvePublicDashboardLink()
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

    fun saveTorBoxApiKey(value: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            savingTorBox.value = true
            torBoxService.validateAndSaveApiKey(value)
                .onSuccess {
                    messages.tryEmit(
                        if (value.isBlank()) "TorBox key cleared" else "TorBox connected"
                    )
                    onSuccess()
                }
                .onFailure { error ->
                    messages.tryEmit(error.message ?: "Failed to save TorBox API key")
                }
            savingTorBox.value = false
        }
    }

    fun startTorBoxDevicePairing() {
        if (torBoxPairingJob?.isActive == true) return
        torBoxPairingJob = viewModelScope.launch {
            torBoxPairing.value = TorBoxPairingUiState.Starting
            when (val started = torBoxAuthService.startDeviceAuth()) {
                is TorBoxStartDeviceFlowResult.Success -> {
                    torBoxPairing.value = TorBoxPairingUiState.AwaitingApproval(started.flow)
                    pollTorBoxUntilApprovedOrExpired(started.flow)
                }
                is TorBoxStartDeviceFlowResult.Failed -> {
                    torBoxPairing.value = TorBoxPairingUiState.Error(started.message)
                    messages.tryEmit(started.message)
                }
            }
        }
    }

    fun cancelTorBoxDevicePairing() {
        torBoxPairingJob?.cancel()
        torBoxPairingJob = null
        torBoxPairing.value = TorBoxPairingUiState.Idle
    }

    private suspend fun pollTorBoxUntilApprovedOrExpired(flow: TorBoxDeviceFlow) {
        val intervalMillis = flow.intervalSeconds.coerceAtLeast(1) * 1000L
        while (true) {
            if (System.currentTimeMillis() >= flow.expiresAtMillis) {
                torBoxPairing.value = TorBoxPairingUiState.Error("TorBox device code expired.")
                messages.tryEmit("TorBox device code expired.")
                return
            }
            delay(intervalMillis)
            when (val result = torBoxAuthService.pollDeviceToken(flow.deviceCode)) {
                TorBoxTokenPollResult.Pending -> continue
                TorBoxTokenPollResult.Expired -> {
                    torBoxPairing.value = TorBoxPairingUiState.Error("TorBox device code expired.")
                    messages.tryEmit("TorBox device code expired.")
                    return
                }
                is TorBoxTokenPollResult.Failed -> {
                    torBoxPairing.value = TorBoxPairingUiState.Error(result.message)
                    messages.tryEmit(result.message)
                    return
                }
                is TorBoxTokenPollResult.Approved -> {
                    savingTorBox.value = true
                    torBoxService.validateAndSaveApiKey(result.apiKey)
                        .onSuccess {
                            messages.tryEmit("TorBox connected")
                            torBoxPairing.value = TorBoxPairingUiState.Idle
                        }
                        .onFailure { error ->
                            val message = error.message ?: "Failed to save TorBox API key"
                            torBoxPairing.value = TorBoxPairingUiState.Error(message)
                            messages.tryEmit(message)
                        }
                    savingTorBox.value = false
                    return
                }
            }
        }
    }

    fun pollTorBoxNow() {
        val current = torBoxPairing.value as? TorBoxPairingUiState.AwaitingApproval ?: return
        // Replace any pending background poll with an immediate one — the existing job's delay will be cancelled.
        torBoxPairingJob?.cancel()
        torBoxPairingJob = viewModelScope.launch {
            when (val result = torBoxAuthService.pollDeviceToken(current.flow.deviceCode)) {
                TorBoxTokenPollResult.Pending -> pollTorBoxUntilApprovedOrExpired(current.flow)
                TorBoxTokenPollResult.Expired -> {
                    torBoxPairing.value = TorBoxPairingUiState.Error("TorBox device code expired.")
                    messages.tryEmit("TorBox device code expired.")
                }
                is TorBoxTokenPollResult.Failed -> {
                    torBoxPairing.value = TorBoxPairingUiState.Error(result.message)
                    messages.tryEmit(result.message)
                }
                is TorBoxTokenPollResult.Approved -> {
                    savingTorBox.value = true
                    torBoxService.validateAndSaveApiKey(result.apiKey)
                        .onSuccess {
                            messages.tryEmit("TorBox connected")
                            torBoxPairing.value = TorBoxPairingUiState.Idle
                        }
                        .onFailure { error ->
                            val message = error.message ?: "Failed to save TorBox API key"
                            torBoxPairing.value = TorBoxPairingUiState.Error(message)
                            messages.tryEmit(message)
                        }
                    savingTorBox.value = false
                }
            }
        }
    }

    fun saveEasyDebridApiKey(value: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            savingEasyDebrid.value = true
            easyDebridService.validateAndSaveApiKey(value)
                .onSuccess {
                    messages.tryEmit(
                        if (value.isBlank()) "EasyDebrid key cleared" else "EasyDebrid connected"
                    )
                    onSuccess()
                }
                .onFailure { error ->
                    messages.tryEmit(error.message ?: "Failed to save EasyDebrid API key")
            }
            savingEasyDebrid.value = false
        }
    }

}
