@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import android.view.KeyEvent
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
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
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
import com.nexio.tv.data.repository.RealDebridAuthService
import com.nexio.tv.data.repository.RealDebridTokenPollResult
import com.nexio.tv.data.repository.TorBoxService
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkRuntimeState
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkService
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSummary
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTerminationReason
import com.nexio.tv.data.repository.benchmark.CollectorPublicDashboardLinkProvider
import com.nexio.tv.data.repository.benchmark.CollectorPublicDashboardLinkResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkProfileResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkRuntimeState
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkService
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkStatus
import com.nexio.tv.core.qr.QrCodeGenerator
import com.nexio.tv.data.repository.benchmark.safeSustainedBudgetMbps
import com.nexio.tv.data.repository.benchmark.playbackStability
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale
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
    val realDebridBenchmark: DebridProviderBenchmarkUi = DebridProviderBenchmarkUi(),
    val realDebridConfigBenchmark: DebridProviderConfigBenchmarkUi = DebridProviderConfigBenchmarkUi(),
    val premiumizeConnected: Boolean = false,
    val premiumizeCustomerId: Int? = null,
    val premiumizeBenchmark: DebridProviderBenchmarkUi = DebridProviderBenchmarkUi(),
    val premiumizeConfigBenchmark: DebridProviderConfigBenchmarkUi = DebridProviderConfigBenchmarkUi(),
    val torBoxBenchmark: DebridProviderBenchmarkUi = DebridProviderBenchmarkUi(),
    val torBoxConfigBenchmark: DebridProviderConfigBenchmarkUi = DebridProviderConfigBenchmarkUi(),
    val easyDebridBenchmark: DebridProviderBenchmarkUi = DebridProviderBenchmarkUi(),
    val easyDebridConfigBenchmark: DebridProviderConfigBenchmarkUi = DebridProviderConfigBenchmarkUi(),
    val serviceWrapEnabled: Boolean = false,
    val serviceWrapAvailable: Boolean = false,
    val shadowAutoplayDataCollectionEnabled: Boolean = false,
    val debridBenchmarkDataCollectionEnabled: Boolean = false,
    val collectorDashboardUrl: String? = null,
    val collectorDashboardUnavailableReason: CollectorPublicDashboardLinkResult.Reason? = null,
    val collectorDashboardAvailable: Boolean = false,
    val deterministicAutoplayEnabled: Boolean = false,
    val deterministicAutoplayAvailable: Boolean = false,
    val benchmarkResultDialog: DebridBenchmarkResultDialogUi? = null,
    val configBenchmarkResultDialog: DebridConfigBenchmarkResultDialogUi? = null,
    val torBoxConnected: Boolean = false,
    val torBoxEmail: String? = null,
    val torBoxPlan: String? = null,
    val easyDebridConnected: Boolean = false,
    val easyDebridUserId: String? = null,
    val easyDebridPaidUntil: String? = null,
    // WP2 — playback diagnostics trace (gated by PLAYBACK_TRACE_UI_ENABLED)
    val playbackTraceEnabled: Boolean = false,
    val playbackTraceAdbControlEnabled: Boolean = false,
    val playbackTraceStatus: com.nexio.tv.instrumentation.TraceStatus =
        com.nexio.tv.instrumentation.TraceStatus.EMPTY,
    val lastTraceSummary: TraceFileSummary? = null
)

/**
 * WP2 — small read-only summary of the most recent playback trace file shown
 * under the debrid diagnostics section. Lifetime-tied to a single `sessionId`
 * so the UI can render "Last session: <sid> · <size> · <event count>" after
 * the writer thread finalizes the JSONL.
 */
internal data class TraceFileSummary(
    val sessionId: String,
    val path: String,
    val sizeBytes: Long,
    val eventCount: Int
)

internal data class DebridProviderBenchmarkUi(
    val isVisible: Boolean = false,
    val canRun: Boolean = false,
    val isRunning: Boolean = false,
    val blockedByActiveRun: Boolean = false,
    val latestResult: DebridBenchmarkResult? = null,
    val activeSummary: DebridBenchmarkSummary? = null
)

internal data class DebridBenchmarkResultDialogUi(
    val result: DebridBenchmarkResult
)

internal data class DebridProviderConfigBenchmarkUi(
    val isVisible: Boolean = false,
    val canRun: Boolean = false,
    val isRunning: Boolean = false,
    val blockedByActiveRun: Boolean = false,
    val latestResult: DebridConfigBenchmarkResult? = null,
    val activeProfileLabel: String? = null,
    val activeSummary: DebridBenchmarkSummary? = null
)

internal data class DebridConfigBenchmarkChunkGroupUi(
    val chunkSizeMb: Int,
    val rows: List<DebridConfigBenchmarkProfileResult>
)

internal data class DebridConfigBenchmarkResultDialogUi(
    val result: DebridConfigBenchmarkResult,
    val chunkGroups: List<DebridConfigBenchmarkChunkGroupUi>
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

private data class DebridBenchmarkSnapshot(
    val latestRealDebridResult: DebridBenchmarkResult?,
    val latestPremiumizeResult: DebridBenchmarkResult?,
    val latestTorBoxResult: DebridBenchmarkResult?,
    val latestEasyDebridResult: DebridBenchmarkResult?,
    val activeState: DebridBenchmarkRuntimeState
)

private data class DebridConfigBenchmarkSnapshot(
    val latestRealDebridResult: DebridConfigBenchmarkResult?,
    val latestPremiumizeResult: DebridConfigBenchmarkResult?,
    val latestTorBoxResult: DebridConfigBenchmarkResult?,
    val latestEasyDebridResult: DebridConfigBenchmarkResult?,
    val activeState: DebridConfigBenchmarkRuntimeState
)

private data class DebridDialogSnapshot(
    val benchmarkResultDialog: DebridBenchmarkResultDialogUi?,
    val configBenchmarkResultDialog: DebridConfigBenchmarkResultDialogUi?
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
    val shadowAutoplayDataCollectionEnabled: Boolean,
    val debridBenchmarkDataCollectionEnabled: Boolean
)

private data class DebridUiBaseSnapshot(
    val connection: DebridConnectionSnapshot,
    val benchmark: DebridBenchmarkSnapshot,
    val configBenchmark: DebridConfigBenchmarkSnapshot
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
    var showCollectorDashboardDialog by remember { mutableStateOf(false) }
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

                if (uiState.realDebridBenchmark.isVisible) {
                    val latestRealDebridResult = uiState.realDebridBenchmark.latestResult
                    item(key = "debrid_rd_benchmark") {
                        DebridBenchmarkRow(
                            provider = DebridBenchmarkProvider.REAL_DEBRID,
                            benchmark = uiState.realDebridBenchmark,
                            onStart = { viewModel.startBenchmark(DebridBenchmarkProvider.REAL_DEBRID) },
                            onCancel = { viewModel.cancelBenchmark() }
                        )
                    }
                    if (latestRealDebridResult != null) {
                        item(key = "debrid_rd_benchmark_result") {
                            DebridBenchmarkResultRow(
                                provider = DebridBenchmarkProvider.REAL_DEBRID,
                                result = latestRealDebridResult,
                                onOpen = {
                                    viewModel.openLatestBenchmarkResult(
                                        DebridBenchmarkProvider.REAL_DEBRID
                                    )
                                }
                            )
                        }
                    }
                }

                if (uiState.realDebridConfigBenchmark.isVisible) {
                    val latestRealDebridConfigResult = uiState.realDebridConfigBenchmark.latestResult
                    item(key = "debrid_rd_config_benchmark") {
                        DebridConfigBenchmarkRow(
                            provider = DebridBenchmarkProvider.REAL_DEBRID,
                            benchmark = uiState.realDebridConfigBenchmark,
                            onStart = { viewModel.startConfigBenchmark(DebridBenchmarkProvider.REAL_DEBRID) },
                            onCancel = { viewModel.cancelConfigBenchmark() }
                        )
                    }
                    if (latestRealDebridConfigResult != null) {
                        item(key = "debrid_rd_config_benchmark_result") {
                            DebridConfigBenchmarkResultRow(
                                provider = DebridBenchmarkProvider.REAL_DEBRID,
                                result = latestRealDebridConfigResult,
                                onOpen = {
                                    viewModel.openLatestConfigBenchmarkResult(
                                        DebridBenchmarkProvider.REAL_DEBRID
                                    )
                                }
                            )
                        }
                    }
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

                item(key = "debrid_deterministic_autoplay") {
                    SettingsToggleRow(
                        title = stringResource(R.string.debrid_deterministic_autoplay_title),
                        subtitle = if (uiState.deterministicAutoplayAvailable) {
                            "Skip stream selection and auto-play the best benchmark-aware match"
                        } else {
                            "Requires Service Wrap enabled and at least one completed debrid benchmark"
                        },
                        checked = uiState.deterministicAutoplayEnabled,
                        enabled = uiState.deterministicAutoplayAvailable,
                        onToggle = {
                            viewModel.setDeterministicAutoplayEnabled(
                                !uiState.deterministicAutoplayEnabled
                            )
                        }
                    )
                }

                item(key = "debrid_shadow_data_collection") {
                    SettingsToggleRow(
                        title = stringResource(R.string.debrid_shadow_autoplay_title),
                        subtitle = stringResource(R.string.debrid_shadow_autoplay_subtitle),
                        checked = uiState.shadowAutoplayDataCollectionEnabled,
                        enabled = true,
                        onToggle = {
                            viewModel.setShadowAutoplayDataCollectionEnabled(
                                !uiState.shadowAutoplayDataCollectionEnabled
                            )
                        }
                    )
                }

                item(key = "debrid_benchmark_data_collection") {
                    SettingsToggleRow(
                        title = stringResource(R.string.debrid_benchmark_title),
                        subtitle = stringResource(R.string.debrid_benchmark_subtitle),
                        checked = uiState.debridBenchmarkDataCollectionEnabled,
                        enabled = true,
                        onToggle = {
                            viewModel.setDebridBenchmarkDataCollectionEnabled(
                                !uiState.debridBenchmarkDataCollectionEnabled
                            )
                        }
                    )
                }

                item(key = "debrid_data_collection_qr") {
                    SettingsActionRow(
                        title = stringResource(R.string.debrid_data_collection_analyse_title),
                        subtitle = stringResource(R.string.debrid_data_collection_analyse_subtitle),
                        value = stringResource(R.string.debrid_data_collection_view_qr_action),
                        onClick = {
                            viewModel.refreshPublicCollectorDashboardLink()
                            showCollectorDashboardDialog = true
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

                if (uiState.premiumizeBenchmark.isVisible) {
                    val latestPremiumizeResult = uiState.premiumizeBenchmark.latestResult
                    item(key = "debrid_pm_benchmark") {
                        DebridBenchmarkRow(
                            provider = DebridBenchmarkProvider.PREMIUMIZE,
                            benchmark = uiState.premiumizeBenchmark,
                            onStart = { viewModel.startBenchmark(DebridBenchmarkProvider.PREMIUMIZE) },
                            onCancel = { viewModel.cancelBenchmark() }
                        )
                    }
                    if (latestPremiumizeResult != null) {
                        item(key = "debrid_pm_benchmark_result") {
                            DebridBenchmarkResultRow(
                                provider = DebridBenchmarkProvider.PREMIUMIZE,
                                result = latestPremiumizeResult,
                                onOpen = {
                                    viewModel.openLatestBenchmarkResult(
                                        DebridBenchmarkProvider.PREMIUMIZE
                                    )
                                }
                            )
                        }
                    }
                }

                if (uiState.premiumizeConfigBenchmark.isVisible) {
                    val latestPremiumizeConfigResult = uiState.premiumizeConfigBenchmark.latestResult
                    item(key = "debrid_pm_config_benchmark") {
                        DebridConfigBenchmarkRow(
                            provider = DebridBenchmarkProvider.PREMIUMIZE,
                            benchmark = uiState.premiumizeConfigBenchmark,
                            onStart = { viewModel.startConfigBenchmark(DebridBenchmarkProvider.PREMIUMIZE) },
                            onCancel = { viewModel.cancelConfigBenchmark() }
                        )
                    }
                    if (latestPremiumizeConfigResult != null) {
                        item(key = "debrid_pm_config_benchmark_result") {
                            DebridConfigBenchmarkResultRow(
                                provider = DebridBenchmarkProvider.PREMIUMIZE,
                                result = latestPremiumizeConfigResult,
                                onOpen = {
                                    viewModel.openLatestConfigBenchmarkResult(
                                        DebridBenchmarkProvider.PREMIUMIZE
                                    )
                                }
                            )
                        }
                    }
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

                if (uiState.torBoxBenchmark.isVisible) {
                    val latestTorBoxResult = uiState.torBoxBenchmark.latestResult
                    item(key = "debrid_tb_benchmark") {
                        DebridBenchmarkRow(
                            provider = DebridBenchmarkProvider.TORBOX,
                            benchmark = uiState.torBoxBenchmark,
                            onStart = { viewModel.startBenchmark(DebridBenchmarkProvider.TORBOX) },
                            onCancel = { viewModel.cancelBenchmark() }
                        )
                    }
                    if (latestTorBoxResult != null) {
                        item(key = "debrid_tb_benchmark_result") {
                            DebridBenchmarkResultRow(
                                provider = DebridBenchmarkProvider.TORBOX,
                                result = latestTorBoxResult,
                                onOpen = {
                                    viewModel.openLatestBenchmarkResult(
                                        DebridBenchmarkProvider.TORBOX
                                    )
                                }
                            )
                        }
                    }
                }

                if (uiState.torBoxConfigBenchmark.isVisible) {
                    val latestTorBoxConfigResult = uiState.torBoxConfigBenchmark.latestResult
                    item(key = "debrid_tb_config_benchmark") {
                        DebridConfigBenchmarkRow(
                            provider = DebridBenchmarkProvider.TORBOX,
                            benchmark = uiState.torBoxConfigBenchmark,
                            onStart = { viewModel.startConfigBenchmark(DebridBenchmarkProvider.TORBOX) },
                            onCancel = { viewModel.cancelConfigBenchmark() }
                        )
                    }
                    if (latestTorBoxConfigResult != null) {
                        item(key = "debrid_tb_config_benchmark_result") {
                            DebridConfigBenchmarkResultRow(
                                provider = DebridBenchmarkProvider.TORBOX,
                                result = latestTorBoxConfigResult,
                                onOpen = {
                                    viewModel.openLatestConfigBenchmarkResult(
                                        DebridBenchmarkProvider.TORBOX
                                    )
                                }
                            )
                        }
                    }
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

                if (uiState.easyDebridBenchmark.isVisible) {
                    val latestEasyDebridResult = uiState.easyDebridBenchmark.latestResult
                    item(key = "debrid_ed_benchmark") {
                        DebridBenchmarkRow(
                            provider = DebridBenchmarkProvider.EASY_DEBRID,
                            benchmark = uiState.easyDebridBenchmark,
                            onStart = { viewModel.startBenchmark(DebridBenchmarkProvider.EASY_DEBRID) },
                            onCancel = { viewModel.cancelBenchmark() }
                        )
                    }
                    if (latestEasyDebridResult != null) {
                        item(key = "debrid_ed_benchmark_result") {
                            DebridBenchmarkResultRow(
                                provider = DebridBenchmarkProvider.EASY_DEBRID,
                                result = latestEasyDebridResult,
                                onOpen = {
                                    viewModel.openLatestBenchmarkResult(
                                        DebridBenchmarkProvider.EASY_DEBRID
                                    )
                                }
                            )
                        }
                    }
                }

                if (uiState.easyDebridConfigBenchmark.isVisible) {
                    val latestEasyDebridConfigResult = uiState.easyDebridConfigBenchmark.latestResult
                    item(key = "debrid_ed_config_benchmark") {
                        DebridConfigBenchmarkRow(
                            provider = DebridBenchmarkProvider.EASY_DEBRID,
                            benchmark = uiState.easyDebridConfigBenchmark,
                            onStart = { viewModel.startConfigBenchmark(DebridBenchmarkProvider.EASY_DEBRID) },
                            onCancel = { viewModel.cancelConfigBenchmark() }
                        )
                    }
                    if (latestEasyDebridConfigResult != null) {
                        item(key = "debrid_ed_config_benchmark_result") {
                            DebridConfigBenchmarkResultRow(
                                provider = DebridBenchmarkProvider.EASY_DEBRID,
                                result = latestEasyDebridConfigResult,
                                onOpen = {
                                    viewModel.openLatestConfigBenchmarkResult(
                                        DebridBenchmarkProvider.EASY_DEBRID
                                    )
                                }
                            )
                        }
                    }
                }
                // Playback diagnostics trace. Compile-time elided when
                // `PLAYBACK_TRACE_UI_ENABLED` is false. Each interactive
                // element is emitted as its own LazyColumn item so the
                // Fire TV d-pad can focus rows individually — see the
                // header comment on [playbackDiagnosticsItems].
                @Suppress("KotlinConstantConditions")
                if (com.nexio.tv.instrumentation.PLAYBACK_TRACE_UI_ENABLED) {
                    playbackDiagnosticsItems(
                        enabled = uiState.playbackTraceEnabled,
                        adbControlEnabled = uiState.playbackTraceAdbControlEnabled,
                        status = uiState.playbackTraceStatus,
                        onToggleEnabled = {
                            viewModel.setPlaybackTraceEnabled(
                                !uiState.playbackTraceEnabled
                            )
                        },
                        onToggleAdbControl = {
                            viewModel.setPlaybackTraceAdbControlEnabled(
                                !uiState.playbackTraceAdbControlEnabled
                            )
                        },
                        onExportLast = {
                            viewModel.exportLastSession { intent ->
                                context.startActivity(intent)
                            }
                        },
                        onExportAll = {
                            viewModel.exportAllSessions { intent ->
                                context.startActivity(intent)
                            }
                        },
                        onCopyToDownloads = { uri ->
                            viewModel.copyLastTraceToDownloads(uri)
                        },
                        onClearAll = { viewModel.clearAllTraces() },
                    )
                }
            }
        }

        if (showCollectorDashboardDialog) {
            CollectorDashboardQrDialog(
                url = uiState.collectorDashboardUrl,
                unavailableReason = uiState.collectorDashboardUnavailableReason,
                onDismiss = { showCollectorDashboardDialog = false }
            )
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

    if (showTorBoxDialog) {
        DebridApiKeyDialog(
            title = stringResource(R.string.debrid_torbox_key_title),
            subtitle = stringResource(R.string.debrid_torbox_key_subtitle),
            currentValue = torBoxApiKey,
            saving = savingTorBox,
            onSave = { value, onSuccess -> viewModel.saveTorBoxApiKey(value, onSuccess) },
            onClear = {
                viewModel.saveTorBoxApiKey("") { }
                showTorBoxDialog = false
            },
            onDismiss = { showTorBoxDialog = false }
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

    uiState.benchmarkResultDialog?.let { dialog ->
        DebridBenchmarkResultDialog(
            dialog = dialog,
            onDismiss = viewModel::dismissBenchmarkResultDialog
        )
    }
    uiState.configBenchmarkResultDialog?.let { dialog ->
        DebridConfigBenchmarkResultDialog(
            dialog = dialog,
            onDismiss = viewModel::dismissConfigBenchmarkResultDialog
        )
    }
}

@Composable
private fun CollectorDashboardQrDialog(
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

        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
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

@Composable
private fun DebridBenchmarkRow(
    provider: DebridBenchmarkProvider,
    benchmark: DebridProviderBenchmarkUi,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    SettingsActionRow(
        title = when (provider) {
            DebridBenchmarkProvider.REAL_DEBRID -> stringResource(R.string.debrid_real_debrid_benchmark_title)
            DebridBenchmarkProvider.PREMIUMIZE -> stringResource(R.string.debrid_premiumize_benchmark_title)
            DebridBenchmarkProvider.TORBOX -> "TorBox Benchmark"
            DebridBenchmarkProvider.EASY_DEBRID -> "EasyDebrid Benchmark"
        },
        subtitle = benchmarkRowSubtitle(benchmark),
        value = stringResource(
            if (benchmark.isRunning) {
                R.string.debrid_benchmark_cancel_action
            } else {
                R.string.debrid_benchmark_run_action
            }
        ),
        enabled = benchmark.isRunning || benchmark.canRun,
        onClick = {
            if (benchmark.isRunning) {
                onCancel()
            } else {
                onStart()
            }
        }
    )
}

@Composable
private fun DebridConfigBenchmarkRow(
    provider: DebridBenchmarkProvider,
    benchmark: DebridProviderConfigBenchmarkUi,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    SettingsActionRow(
        title = when (provider) {
            DebridBenchmarkProvider.REAL_DEBRID -> stringResource(R.string.debrid_real_debrid_config_benchmark_title)
            DebridBenchmarkProvider.PREMIUMIZE -> stringResource(R.string.debrid_premiumize_config_benchmark_title)
            DebridBenchmarkProvider.TORBOX -> "TorBox Config Benchmark"
            DebridBenchmarkProvider.EASY_DEBRID -> "EasyDebrid Config Benchmark"
        },
        subtitle = configBenchmarkRowSubtitle(benchmark),
        value = stringResource(
            if (benchmark.isRunning) {
                R.string.debrid_benchmark_cancel_action
            } else {
                R.string.debrid_config_benchmark_run_action
            }
        ),
        enabled = benchmark.isRunning || benchmark.canRun,
        onClick = {
            if (benchmark.isRunning) {
                onCancel()
            } else {
                onStart()
            }
        }
    )
}

@Composable
private fun DebridBenchmarkResultRow(
    provider: DebridBenchmarkProvider,
    result: DebridBenchmarkResult,
    onOpen: () -> Unit
) {
    SettingsActionRow(
        title = when (provider) {
            DebridBenchmarkProvider.REAL_DEBRID ->
                stringResource(R.string.debrid_real_debrid_benchmark_result_title)
            DebridBenchmarkProvider.PREMIUMIZE ->
                stringResource(R.string.debrid_premiumize_benchmark_result_title)
            DebridBenchmarkProvider.TORBOX ->
                "TorBox Benchmark Result"
            DebridBenchmarkProvider.EASY_DEBRID ->
                "EasyDebrid Benchmark Result"
        },
        subtitle = formatLatestBenchmarkSummary(result),
        value = stringResource(R.string.debrid_benchmark_view_action),
        onClick = onOpen
    )
}

@Composable
private fun DebridConfigBenchmarkResultRow(
    provider: DebridBenchmarkProvider,
    result: DebridConfigBenchmarkResult,
    onOpen: () -> Unit
) {
    SettingsActionRow(
        title = when (provider) {
            DebridBenchmarkProvider.REAL_DEBRID ->
                stringResource(R.string.debrid_real_debrid_config_benchmark_result_title)
            DebridBenchmarkProvider.PREMIUMIZE ->
                stringResource(R.string.debrid_premiumize_config_benchmark_result_title)
            DebridBenchmarkProvider.TORBOX ->
                "TorBox Config Benchmark Result"
            DebridBenchmarkProvider.EASY_DEBRID ->
                "EasyDebrid Config Benchmark Result"
        },
        subtitle = formatLatestConfigBenchmarkSummary(result),
        value = stringResource(R.string.debrid_benchmark_view_action),
        onClick = onOpen
    )
}

@Composable
private fun DebridBenchmarkResultDialog(
    dialog: DebridBenchmarkResultDialogUi,
    onDismiss: () -> Unit
) {
    val result = dialog.result
    val providerName = stringResource(
        when (result.provider) {
            DebridBenchmarkProvider.REAL_DEBRID -> R.string.debrid_real_debrid_title
            DebridBenchmarkProvider.PREMIUMIZE -> R.string.debrid_premiumize_title
            DebridBenchmarkProvider.TORBOX -> R.string.debrid_torbox_title
            DebridBenchmarkProvider.EASY_DEBRID -> R.string.debrid_easydebrid_title
        }
    )
    val unavailable = stringResource(R.string.debrid_benchmark_metric_unavailable)
    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.debrid_benchmark_results_title, providerName),
        subtitle = null,
        width = 1360.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                result.candidate?.host?.let {
                    DebridBenchmarkHeaderMetric(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.debrid_benchmark_label_host),
                        value = it
                    )
                }
                DebridBenchmarkHeaderMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.debrid_benchmark_label_measured_at),
                    value = formatBenchmarkMeasuredAt(result.measuredAtMs)
                )
            }

            if (result.direct != null && result.optimized != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    DebridBenchmarkHighlightsCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.debrid_benchmark_column_direct),
                        subtitle = null,
                        profile = result.direct,
                        unavailable = unavailable
                    )
                    DebridBenchmarkHighlightsCard(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.debrid_benchmark_column_optimized),
                        subtitle = result.optimized.configSnapshot?.let { formatBenchmarkConfigSnapshot(it) },
                        profile = result.optimized,
                        unavailable = unavailable
                    )
                }
                DebridBenchmarkComparisonHighlights(
                    comparison = result.comparison,
                    direct = result.direct,
                    optimized = result.optimized
                )
            } else {
                result.optimized?.safeSustainedBudgetMbps()?.let { safeBudgetMbps ->
                    DebridBenchmarkMetricRow(
                        label = stringResource(R.string.debrid_benchmark_metric_safe_budget),
                        value = formatBenchmarkThroughput(safeBudgetMbps)
                    )
                }
                DebridBenchmarkMetricRow(
                    label = stringResource(R.string.debrid_benchmark_metric_startup),
                    value = result.summary.startupTimeMs?.let(::formatBenchmarkLatency) ?: unavailable
                )
                DebridBenchmarkMetricRow(
                    label = stringResource(R.string.debrid_benchmark_metric_sustained),
                    value = result.summary.sustainedThroughputMbps
                        ?.let(::formatBenchmarkThroughput)
                        ?.plus(" sustained")
                        ?: unavailable
                )
                DebridBenchmarkMetricRow(
                    label = stringResource(R.string.debrid_benchmark_metric_transferred),
                    value = formatBenchmarkBytes(result.summary.transferredBytes)
                )
                DebridBenchmarkMetricRow(
                    label = stringResource(R.string.debrid_benchmark_metric_elapsed),
                    value = formatBenchmarkElapsed(result.summary.elapsedMs)
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
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

@Composable
private fun DebridConfigBenchmarkResultDialog(
    dialog: DebridConfigBenchmarkResultDialogUi,
    onDismiss: () -> Unit
) {
    val result = dialog.result
    val providerName = stringResource(
        when (result.provider) {
            DebridBenchmarkProvider.REAL_DEBRID -> R.string.debrid_real_debrid_title
            DebridBenchmarkProvider.PREMIUMIZE -> R.string.debrid_premiumize_title
            DebridBenchmarkProvider.TORBOX -> R.string.debrid_torbox_title
            DebridBenchmarkProvider.EASY_DEBRID -> R.string.debrid_easydebrid_title
        }
    )
    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.debrid_config_benchmark_results_title, providerName),
        subtitle = null,
        width = 1360.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                DebridBenchmarkHeaderMetric(
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.debrid_benchmark_label_measured_at),
                    value = formatBenchmarkMeasuredAt(result.measuredAtMs)
                )
                DebridBenchmarkCompactMetricRow(
                    label = stringResource(R.string.debrid_config_benchmark_best_profile_label),
                    value = formatConfigBenchmarkBestProfile(result)
                )
                dialog.chunkGroups.chunked(2).forEach { rowGroups ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        rowGroups.forEach { group ->
                            DebridConfigBenchmarkChunkCard(
                                modifier = Modifier.weight(1f),
                                group = group
                            )
                        }
                        if (rowGroups.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.colors(
                        containerColor = NexioColors.BackgroundCard,
                        contentColor = NexioColors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}

@Composable
private fun DebridConfigBenchmarkChunkCard(
    modifier: Modifier,
    group: DebridConfigBenchmarkChunkGroupUi
) {
    Column(
        modifier = modifier
            .background(
                color = NexioColors.BackgroundCard,
                shape = RoundedCornerShape(14.dp)
            )
            .border(
                width = 1.dp,
                color = NexioColors.Border,
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.debrid_config_benchmark_chunk_group_title, group.chunkSizeMb),
            style = MaterialTheme.typography.titleSmall,
            color = NexioColors.TextPrimary
        )
        group.rows.sortedBy { it.parallelConnectionCount }.forEach { row ->
            DebridBenchmarkCompactMetricRow(
                label = stringResource(
                    R.string.debrid_config_benchmark_profile_label,
                    row.parallelConnectionCount
                ),
                value = formatConfigBenchmarkProfileValue(row)
            )
        }
    }
}

@Composable
private fun DebridBenchmarkHighlightsCard(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    profile: com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportProfile,
    unavailable: String
) {
    Column(
        modifier = modifier
            .background(
                color = NexioColors.BackgroundCard,
                shape = RoundedCornerShape(14.dp)
            )
            .border(
                width = 1.dp,
                color = NexioColors.Border,
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = NexioColors.TextPrimary
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = NexioColors.TextSecondary
            )
        }
        DebridBenchmarkCompactMetricRow(
            label = stringResource(R.string.debrid_benchmark_metric_initial_ttfb),
            value = profile.startup.initialTtfbMs?.let(::formatBenchmarkLatency) ?: unavailable
        )
        DebridBenchmarkCompactMetricRow(
            label = stringResource(R.string.debrid_benchmark_metric_safe_budget),
            value = profile.safeSustainedBudgetMbps()?.let(::formatBenchmarkThroughput) ?: unavailable
        )
        DebridBenchmarkCompactMetricRow(
            label = stringResource(R.string.debrid_benchmark_metric_average_throughput),
            value = profile.sustained.averageThroughputMbps?.let(::formatBenchmarkThroughput) ?: unavailable
        )
        DebridBenchmarkCompactMetricRow(
            label = stringResource(R.string.debrid_benchmark_metric_seek_p95),
            value = profile.seek.seekTtfbP95Ms?.let(::formatBenchmarkLatency) ?: unavailable
        )
        DebridBenchmarkCompactMetricRow(
            label = stringResource(R.string.debrid_benchmark_metric_stability),
            value = formatBenchmarkStability(profile)
        )
    }
}

@Composable
private fun DebridBenchmarkMetricRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = NexioColors.TextSecondary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = NexioColors.TextPrimary
        )
    }
}

@Composable
private fun DebridBenchmarkCompactMetricRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = NexioColors.TextSecondary
        )
        Spacer(modifier = Modifier.width(18.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = NexioColors.TextPrimary,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun DebridBenchmarkHeaderMetric(
    modifier: Modifier = Modifier,
    label: String,
    value: String
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NexioColors.TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = NexioColors.TextPrimary
        )
    }
}

@Composable
private fun DebridBenchmarkComparisonHighlights(
    comparison: com.nexio.tv.data.repository.benchmark.DebridBenchmarkComparisonSummary?,
    direct: com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportProfile,
    optimized: com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportProfile
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NexioColors.BackgroundCard,
                shape = RoundedCornerShape(14.dp)
            )
            .border(
                width = 1.dp,
                color = NexioColors.Border,
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.debrid_benchmark_comparison_title),
            style = MaterialTheme.typography.titleSmall,
            color = NexioColors.TextPrimary
        )
        DebridBenchmarkCompactMetricRow(
            label = stringResource(R.string.debrid_benchmark_metric_sustained_winner),
            value = formatBenchmarkWinner(comparison?.sustainedWinner)
        )
        DebridBenchmarkCompactMetricRow(
            label = stringResource(R.string.debrid_benchmark_metric_seek_winner),
            value = formatBenchmarkWinner(comparison?.seekWinner)
        )
        DebridBenchmarkCompactMetricRow(
            label = stringResource(R.string.debrid_benchmark_metric_stability_winner),
            value = formatBenchmarkWinner(comparison?.stabilityWinner)
        )
        DebridBenchmarkCompactMetricRow(
            label = stringResource(R.string.debrid_benchmark_metric_gain_over_direct),
            value = formatBenchmarkGainOverDirect(direct, optimized)
        )
    }
}

@Composable
private fun benchmarkRowSubtitle(benchmark: DebridProviderBenchmarkUi): String {
    return when {
        benchmark.isRunning -> formatRunningBenchmarkSummary(benchmark.activeSummary)
        benchmark.latestResult != null -> formatLatestBenchmarkSummary(benchmark.latestResult)
        benchmark.blockedByActiveRun -> stringResource(R.string.debrid_benchmark_busy)
        else -> stringResource(R.string.debrid_benchmark_description)
    }
}

@Composable
private fun configBenchmarkRowSubtitle(benchmark: DebridProviderConfigBenchmarkUi): String {
    return when {
        benchmark.isRunning -> formatRunningConfigBenchmarkSummary(
            profileLabel = benchmark.activeProfileLabel,
            summary = benchmark.activeSummary
        )
        benchmark.latestResult != null -> formatLatestConfigBenchmarkSummary(benchmark.latestResult)
        benchmark.blockedByActiveRun -> stringResource(R.string.debrid_benchmark_busy)
        else -> stringResource(R.string.debrid_config_benchmark_description)
    }
}

private fun formatRunningBenchmarkSummary(summary: DebridBenchmarkSummary?): String {
    if (summary == null) {
        return "Measuring"
    }

    val bytes = formatBenchmarkBytes(summary.transferredBytes)
    val elapsed = formatBenchmarkElapsed(summary.elapsedMs)
    val throughput = summary.sustainedThroughputMbps?.let { formatBenchmarkThroughput(it) }

    return listOfNotNull("Measuring", bytes, elapsed, throughput).joinToString(" | ")
}

private fun formatLatestBenchmarkSummary(result: DebridBenchmarkResult): String {
    val safeBudget = result.optimized?.safeSustainedBudgetMbps()?.let {
        formatBenchmarkThroughput(it) + " safe"
    }
    val throughput = result.summary.sustainedThroughputMbps?.let { formatBenchmarkThroughput(it) }
    val startup = result.summary.startupTimeMs?.let { formatBenchmarkStartup(it) }
    return listOfNotNull("Latest", safeBudget, throughput?.plus(" sustained"), startup).joinToString(" | ")
}

private fun formatLatestConfigBenchmarkSummary(result: DebridConfigBenchmarkResult): String {
    val bestProfile = result.summary.bestProfile ?: return "Latest | No successful profile"
    val throughput = bestProfile.averageThroughputMbps?.let(::formatBenchmarkThroughput) ?: "Unknown"
    return "Latest | ${bestProfile.parallelConnectionCount}x / ${bestProfile.chunkSizeMb} MB | $throughput"
}

private fun formatRunningConfigBenchmarkSummary(
    profileLabel: String?,
    summary: DebridBenchmarkSummary?
): String {
    val prefix = profileLabel ?: "Testing profile"
    if (summary == null) {
        return prefix
    }
    val elapsed = formatBenchmarkElapsed(summary.elapsedMs)
    val throughput = summary.sustainedThroughputMbps?.let(::formatBenchmarkThroughput)
    return listOfNotNull(prefix, elapsed, throughput).joinToString(" | ")
}

private fun formatConfigBenchmarkBestProfile(result: DebridConfigBenchmarkResult): String {
    val bestProfile = result.summary.bestProfile ?: return "No successful profile"
    val throughput = bestProfile.averageThroughputMbps?.let(::formatBenchmarkThroughput) ?: "Unknown"
    return "${bestProfile.parallelConnectionCount}x / ${bestProfile.chunkSizeMb} MB | $throughput"
}

private fun formatConfigBenchmarkProfileValue(
    result: DebridConfigBenchmarkProfileResult
): String {
    return when (result.status) {
        DebridConfigBenchmarkStatus.SUCCESS ->
            result.averageThroughputMbps?.let(::formatBenchmarkThroughput) ?: "Unknown"
        DebridConfigBenchmarkStatus.FAILED ->
            truncateConfigBenchmarkFailureReason(result.failureReason ?: "Failed")
        DebridConfigBenchmarkStatus.UNSUPPORTED ->
            truncateConfigBenchmarkFailureReason(result.unsupportedReason ?: "Unsupported")
    }
}

private fun truncateConfigBenchmarkFailureReason(value: String, maxLength: Int = 32): String {
    return if (value.length <= maxLength) {
        value
    } else {
        value.take(maxLength - 1).trimEnd() + "…"
    }
}

private fun formatBenchmarkThroughput(mbps: Double): String {
    return "${String.format(Locale.US, "%.1f", mbps)} Mbps"
}

private fun formatBenchmarkStartup(startupTimeMs: Long): String {
    return if (startupTimeMs >= 1_000L) {
        "${String.format(Locale.US, "%.1f", startupTimeMs / 1_000.0)}s startup"
    } else {
        "${startupTimeMs}ms startup"
    }
}

private fun formatBenchmarkLatency(durationMs: Long): String {
    return if (durationMs >= 1_000L) {
        "${String.format(Locale.US, "%.2f", durationMs / 1_000.0)} s"
    } else {
        "${durationMs} ms"
    }
}

@Composable
private fun formatBenchmarkWinner(
    mode: com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportMode?
): String {
    return stringResource(
        when (mode) {
            com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportMode.OPTIMIZED ->
                R.string.debrid_benchmark_column_optimized
            com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportMode.DIRECT,
            null -> R.string.debrid_benchmark_column_direct
        }
    )
}

private fun formatBenchmarkStability(
    profile: com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportProfile
): String {
    return when (profile.playbackStability()) {
        com.nexio.tv.data.repository.benchmark.DebridBenchmarkPlaybackStability.EXCELLENT -> "Excellent"
        com.nexio.tv.data.repository.benchmark.DebridBenchmarkPlaybackStability.STABLE -> "Stable"
        com.nexio.tv.data.repository.benchmark.DebridBenchmarkPlaybackStability.VARIABLE -> "Variable"
        com.nexio.tv.data.repository.benchmark.DebridBenchmarkPlaybackStability.UNSTABLE -> "Unstable"
    }
}

private fun formatBenchmarkGainOverDirect(
    direct: com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportProfile,
    optimized: com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportProfile
): String {
    val directBudget = direct.safeSustainedBudgetMbps()
    val optimizedBudget = optimized.safeSustainedBudgetMbps()
    if (directBudget == null || optimizedBudget == null) {
        return "Not available"
    }
    val delta = optimizedBudget - directBudget
    return if (delta >= 0.0) {
        "+${String.format(Locale.US, "%.1f", delta)} Mbps"
    } else {
        "${String.format(Locale.US, "%.1f", delta)} Mbps"
    }
}

private fun formatBenchmarkCv(value: Double): String {
    return String.format(Locale.US, "%.2f", value)
}

private fun formatBenchmarkPercent(value: Double): String {
    return String.format(Locale.US, "%.1f%%", value * 100.0)
}

private fun formatBenchmarkMeasuredAt(measuredAtMs: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .format(Date(measuredAtMs))
}

@Composable
private fun formatBenchmarkConfigSnapshot(
    snapshot: com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportConfigSnapshot
): String {
    return if (snapshot.useParallelConnections == false) {
        stringResource(R.string.debrid_benchmark_config_parallel_off)
    } else {
        stringResource(
            R.string.debrid_benchmark_config_snapshot,
            snapshot.parallelConnectionCount ?: 0,
            snapshot.parallelChunkSizeMb ?: 0
        )
    }
}

private fun formatBenchmarkElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun formatBenchmarkBytes(bytes: Long): String {
    val absoluteBytes = bytes.coerceAtLeast(0L)
    val gibibyte = 1024.0 * 1024.0 * 1024.0
    val mebibyte = 1024.0 * 1024.0
    return if (absoluteBytes >= gibibyte.toLong()) {
        "${String.format(Locale.US, "%.1f", absoluteBytes / gibibyte)} GB"
    } else {
        "${String.format(Locale.US, "%.0f", absoluteBytes / mebibyte)} MB"
    }
}

@HiltViewModel
internal class DebridSettingsViewModel @Inject internal constructor(
    private val realDebridAuthService: RealDebridAuthService,
    realDebridAuthDataStore: RealDebridAuthDataStore,
    private val premiumizeService: PremiumizeService,
    premiumizeSettingsDataStore: PremiumizeSettingsDataStore,
    private val torBoxService: TorBoxService,
    torBoxSettingsDataStore: TorBoxSettingsDataStore,
    private val easyDebridService: EasyDebridService,
    easyDebridSettingsDataStore: EasyDebridSettingsDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val collectorPublicDashboardLinkProvider: CollectorPublicDashboardLinkProvider,
    private val debridBenchmarkService: DebridBenchmarkService,
    private val debridConfigBenchmarkService: DebridConfigBenchmarkService,
    private val playbackTraceToggle: com.nexio.tv.instrumentation.PlaybackTraceToggle,
    private val playbackTraceController: com.nexio.tv.instrumentation.PlaybackTraceController,
    private val playbackTraceAdbControlToggle:
        com.nexio.tv.instrumentation.PlaybackTraceAdbControlToggle
) : ViewModel() {

    // Playback diagnostics trace plumbing. The composable section in
    // DebridSettingsContent collects [uiState] for status, then calls these
    // action methods which delegate to [PlaybackTraceController]. The
    // controller is the single source of truth shared with the optional
    // ADB receiver.
    internal val playbackTraceEnabled: kotlinx.coroutines.flow.Flow<Boolean> =
        playbackTraceController.enabledFlow

    internal fun setPlaybackTraceEnabled(value: Boolean) {
        viewModelScope.launch {
            playbackTraceController.setEnabled(value)
        }
    }

    internal fun setPlaybackTraceAdbControlEnabled(value: Boolean) {
        viewModelScope.launch {
            playbackTraceAdbControlToggle.setEnabled(value)
        }
    }

    /**
     * Build the share intent for the latest session and pass it back to the
     * Composable so it can call `context.startActivity`. Activity dispatch
     * lives in the UI layer, not the ViewModel.
     */
    internal fun exportLastSession(launchIntent: (android.content.Intent) -> Unit) {
        viewModelScope.launch {
            val intent = playbackTraceController.exportLast() ?: return@launch
            launchIntent(intent)
        }
    }

    internal fun exportAllSessions(launchIntent: (android.content.Intent) -> Unit) {
        viewModelScope.launch {
            val intent = playbackTraceController.exportAll() ?: return@launch
            launchIntent(intent)
        }
    }

    /**
     * Write the latest session bytes into the SAF-picked Uri. Caller is the
     * Composable's ActivityResult callback for `ACTION_CREATE_DOCUMENT`.
     */
    internal fun copyLastTraceToDownloads(target: android.net.Uri) {
        viewModelScope.launch {
            playbackTraceController.copyLastToDestination(target)
            playbackTraceController.refreshStatus()
        }
    }

    internal fun clearAllTraces() {
        viewModelScope.launch {
            playbackTraceController.clearAll()
        }
    }

    private val _uiState = MutableStateFlow(DebridUiState())
    private val benchmarkResultDialog = MutableStateFlow<DebridBenchmarkResultDialogUi?>(null)
    private val configBenchmarkResultDialog = MutableStateFlow<DebridConfigBenchmarkResultDialogUi?>(null)
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
        // Playback diagnostics trace — refresh the on-disk status snapshot
        // every time the master toggle flips, plus once at startup so the
        // section shows accurate data on first open. The status flow is a
        // StateFlow on the controller; the toggle flow is the canonical
        // source of `enabled`.
        viewModelScope.launch {
            playbackTraceController.refreshStatus()
            playbackTraceController.statusFlow.collect { status ->
                _uiState.update { it.copy(playbackTraceStatus = status) }
            }
        }
        viewModelScope.launch {
            playbackTraceController.enabledFlow.collect { enabled ->
                _uiState.update { it.copy(playbackTraceEnabled = enabled) }
                playbackTraceController.refreshStatus()
            }
        }
        viewModelScope.launch {
            playbackTraceAdbControlToggle.enabledFlow.collect { adbEnabled ->
                _uiState.update { it.copy(playbackTraceAdbControlEnabled = adbEnabled) }
            }
        }

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

        val benchmarkState = combine(
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.REAL_DEBRID),
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.PREMIUMIZE),
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.TORBOX),
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.EASY_DEBRID),
            debridBenchmarkService.activeState
        ) { latestRealDebridResult, latestPremiumizeResult, latestTorBoxResult, latestEasyDebridResult, activeState ->
            DebridBenchmarkSnapshot(
                latestRealDebridResult = latestRealDebridResult,
                latestPremiumizeResult = latestPremiumizeResult,
                latestTorBoxResult = latestTorBoxResult,
                latestEasyDebridResult = latestEasyDebridResult,
                activeState = activeState
            )
        }

        val deterministicAutoplayEnabled = playerSettingsDataStore.playerSettings
            .map { it.deterministicAutoplayEnabled }

        val shadowAutoplayDataCollectionEnabled = playerSettingsDataStore.playerSettings
            .map { it.shadowAutoplayDataCollectionEnabled }

        val debridBenchmarkDataCollectionEnabled = playerSettingsDataStore.playerSettings
            .map { it.debridBenchmarkDataCollectionEnabled }

        val serviceWrapEnabled = playerSettingsDataStore.playerSettings
            .map { it.serviceWrapEnabled }

        val deterministicAutoplayAvailable = combine(
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.REAL_DEBRID),
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.PREMIUMIZE),
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.TORBOX),
            debridBenchmarkService.latestResult(DebridBenchmarkProvider.EASY_DEBRID),
            serviceWrapEnabled
        ) { latestRealDebridResult, latestPremiumizeResult, latestTorBoxResult, latestEasyDebridResult, serviceWrapEnabled ->
            (
                latestRealDebridResult != null ||
                    latestPremiumizeResult != null ||
                    latestTorBoxResult != null ||
                    latestEasyDebridResult != null
                ) && serviceWrapEnabled
        }

        val configBenchmarkState = combine(
            debridConfigBenchmarkService.latestResult(DebridBenchmarkProvider.REAL_DEBRID),
            debridConfigBenchmarkService.latestResult(DebridBenchmarkProvider.PREMIUMIZE),
            debridConfigBenchmarkService.latestResult(DebridBenchmarkProvider.TORBOX),
            debridConfigBenchmarkService.latestResult(DebridBenchmarkProvider.EASY_DEBRID),
            debridConfigBenchmarkService.activeState
        ) { latestRealDebridResult, latestPremiumizeResult, latestTorBoxResult, latestEasyDebridResult, activeState ->
            DebridConfigBenchmarkSnapshot(
                latestRealDebridResult = latestRealDebridResult,
                latestPremiumizeResult = latestPremiumizeResult,
                latestTorBoxResult = latestTorBoxResult,
                latestEasyDebridResult = latestEasyDebridResult,
                activeState = activeState
            )
        }

        val dialogState = combine(
            benchmarkResultDialog,
            configBenchmarkResultDialog
        ) { benchmarkDialog, configDialog ->
            DebridDialogSnapshot(
                benchmarkResultDialog = benchmarkDialog,
                configBenchmarkResultDialog = configDialog
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
            val baseState = combine(
                connectionState,
                benchmarkState
            ) { connection, benchmark ->
                connection to benchmark
            }.combine(configBenchmarkState) { (connection, benchmark), configBenchmark ->
                DebridUiBaseSnapshot(
                    connection = connection,
                    benchmark = benchmark,
                    configBenchmark = configBenchmark
                )
            }

            val settingsState = combine(
                deterministicAutoplayEnabled,
                deterministicAutoplayAvailable,
                serviceWrapEnabled,
                shadowAutoplayDataCollectionEnabled,
                debridBenchmarkDataCollectionEnabled
            ) { deterministicEnabled, deterministicAvailable, serviceWrapEnabled, shadowCollectionEnabled, benchmarkCollectionEnabled ->
                DebridSettingsToggleSnapshot(
                    deterministicAutoplayEnabled = deterministicEnabled,
                    deterministicAutoplayAvailable = deterministicAvailable,
                    serviceWrapEnabled = serviceWrapEnabled,
                    shadowAutoplayDataCollectionEnabled = shadowCollectionEnabled,
                    debridBenchmarkDataCollectionEnabled = benchmarkCollectionEnabled
                )
            }

            combine(baseState, dialogState, settingsState, collectorDashboardState) {
                base,
                dialogs,
                settings,
                collectorDashboard
            ->
                DebridUiState(
                    realDebridMode = base.connection.realDebridMode,
                    realDebridUsername = base.connection.realDebridUsername,
                    realDebridUserCode = base.connection.realDebridUserCode,
                    realDebridVerificationUrl = base.connection.realDebridVerificationUrl,
                    realDebridBenchmark = buildDebridBenchmarkUi(
                        provider = DebridBenchmarkProvider.REAL_DEBRID,
                        isVisible = base.connection.realDebridMode == DebridConnectionMode.CONNECTED,
                        latestResult = base.benchmark.latestRealDebridResult,
                        activeState = base.benchmark.activeState,
                        configBenchmarkActive = base.configBenchmark.activeState
                    ),
                    realDebridConfigBenchmark = buildConfigBenchmarkUi(
                        provider = DebridBenchmarkProvider.REAL_DEBRID,
                        isVisible = base.connection.realDebridMode == DebridConnectionMode.CONNECTED,
                        latestResult = base.configBenchmark.latestRealDebridResult,
                        activeState = base.configBenchmark.activeState,
                        benchmarkActive = base.benchmark.activeState
                    ),
                    premiumizeConnected = base.connection.premiumizeConnected,
                    premiumizeCustomerId = base.connection.premiumizeCustomerId,
                    premiumizeBenchmark = buildDebridBenchmarkUi(
                        provider = DebridBenchmarkProvider.PREMIUMIZE,
                        isVisible = base.connection.premiumizeConnected,
                        latestResult = base.benchmark.latestPremiumizeResult,
                        activeState = base.benchmark.activeState,
                        configBenchmarkActive = base.configBenchmark.activeState
                    ),
                    premiumizeConfigBenchmark = buildConfigBenchmarkUi(
                        provider = DebridBenchmarkProvider.PREMIUMIZE,
                        isVisible = base.connection.premiumizeConnected,
                        latestResult = base.configBenchmark.latestPremiumizeResult,
                        activeState = base.configBenchmark.activeState,
                        benchmarkActive = base.benchmark.activeState
                    ),
                    torBoxBenchmark = buildDebridBenchmarkUi(
                        provider = DebridBenchmarkProvider.TORBOX,
                        isVisible = base.connection.torBoxConnected,
                        latestResult = base.benchmark.latestTorBoxResult,
                        activeState = base.benchmark.activeState,
                        configBenchmarkActive = base.configBenchmark.activeState
                    ),
                    torBoxConfigBenchmark = buildConfigBenchmarkUi(
                        provider = DebridBenchmarkProvider.TORBOX,
                        isVisible = base.connection.torBoxConnected,
                        latestResult = base.configBenchmark.latestTorBoxResult,
                        activeState = base.configBenchmark.activeState,
                        benchmarkActive = base.benchmark.activeState
                    ),
                    easyDebridBenchmark = buildDebridBenchmarkUi(
                        provider = DebridBenchmarkProvider.EASY_DEBRID,
                        isVisible = base.connection.easyDebridConnected,
                        latestResult = base.benchmark.latestEasyDebridResult,
                        activeState = base.benchmark.activeState,
                        configBenchmarkActive = base.configBenchmark.activeState
                    ),
                    easyDebridConfigBenchmark = buildConfigBenchmarkUi(
                        provider = DebridBenchmarkProvider.EASY_DEBRID,
                        isVisible = base.connection.easyDebridConnected,
                        latestResult = base.configBenchmark.latestEasyDebridResult,
                        activeState = base.configBenchmark.activeState,
                        benchmarkActive = base.benchmark.activeState
                    ),
                    serviceWrapEnabled = settings.serviceWrapEnabled,
                    serviceWrapAvailable = base.connection.realDebridMode == DebridConnectionMode.CONNECTED ||
                        base.connection.premiumizeConnected ||
                        base.connection.torBoxConnected ||
                        base.connection.easyDebridConnected,
                    shadowAutoplayDataCollectionEnabled = settings.shadowAutoplayDataCollectionEnabled,
                    debridBenchmarkDataCollectionEnabled = settings.debridBenchmarkDataCollectionEnabled,
                    collectorDashboardUrl = collectorDashboard.collectorDashboardUrl,
                    collectorDashboardUnavailableReason = collectorDashboard.collectorDashboardUnavailableReason,
                    collectorDashboardAvailable = collectorDashboard.collectorDashboardAvailable,
                    deterministicAutoplayEnabled = settings.deterministicAutoplayEnabled,
                    deterministicAutoplayAvailable = settings.deterministicAutoplayAvailable,
                    benchmarkResultDialog = dialogs.benchmarkResultDialog,
                    configBenchmarkResultDialog = dialogs.configBenchmarkResultDialog,
                    torBoxConnected = base.connection.torBoxConnected,
                    torBoxEmail = base.connection.torBoxEmail,
                    torBoxPlan = base.connection.torBoxPlan,
                    easyDebridConnected = base.connection.easyDebridConnected,
                    easyDebridUserId = base.connection.easyDebridUserId,
                    easyDebridPaidUntil = base.connection.easyDebridPaidUntil
                )
            }.collect { _uiState.value = it }
        }

        viewModelScope.launch {
            premiumizeService.refreshAccountState()
            torBoxService.refreshAccountState()
            easyDebridService.refreshAccountState()
        }

        viewModelScope.launch {
            debridBenchmarkService.outcomes.collect { outcome ->
                when (outcome.terminationReason) {
                    DebridBenchmarkTerminationReason.COMPLETED -> {
                        outcome.result?.let { result ->
                            setBenchmarkResultDialog(
                                DebridBenchmarkResultDialogUi(result = result)
                            )
                        }
                    }
                    DebridBenchmarkTerminationReason.NO_LARGE_DOWNLOAD ->
                        messages.tryEmit(
                            "No large ${outcome.provider.displayName()} download found for benchmarking"
                        )
                    DebridBenchmarkTerminationReason.NO_PLAYABLE_LIBRARY_ITEM ->
                        messages.tryEmit(
                            "No playable ${outcome.provider.displayName()} library item available for benchmarking"
                        )
                    DebridBenchmarkTerminationReason.CANCELED ->
                        messages.tryEmit("${outcome.provider.displayName()} benchmark canceled")
                    DebridBenchmarkTerminationReason.TIMEOUT ->
                        messages.tryEmit("${outcome.provider.displayName()} benchmark timed out")
                    DebridBenchmarkTerminationReason.FAILED ->
                        messages.tryEmit("${outcome.provider.displayName()} benchmark failed")
                }
            }
        }

        refreshPublicCollectorDashboardLink()

        viewModelScope.launch {
            debridConfigBenchmarkService.outcomes.collect { outcome ->
                when (outcome.terminationReason) {
                    DebridBenchmarkTerminationReason.COMPLETED -> {
                        outcome.result?.let { result ->
                            setConfigBenchmarkResultDialog(
                                DebridConfigBenchmarkResultDialogUi(
                                    result = result,
                                    chunkGroups = result.profiles
                                        .groupBy { it.chunkSizeMb }
                                        .toSortedMap()
                                        .map { (chunkSizeMb, rows) ->
                                            DebridConfigBenchmarkChunkGroupUi(chunkSizeMb, rows)
                                        }
                                )
                            )
                        }
                    }
                    DebridBenchmarkTerminationReason.NO_LARGE_DOWNLOAD ->
                        messages.tryEmit(
                            "No large ${outcome.provider.displayName()} download found for config benchmarking"
                        )
                    DebridBenchmarkTerminationReason.NO_PLAYABLE_LIBRARY_ITEM ->
                        messages.tryEmit(
                            "No playable ${outcome.provider.displayName()} library item available for config benchmarking"
                        )
                    DebridBenchmarkTerminationReason.CANCELED ->
                        messages.tryEmit("${outcome.provider.displayName()} config benchmark canceled")
                    else ->
                        messages.tryEmit("${outcome.provider.displayName()} config benchmark failed")
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

    fun startBenchmark(provider: DebridBenchmarkProvider) {
        viewModelScope.launch {
            val providerUi = when (provider) {
                DebridBenchmarkProvider.REAL_DEBRID -> uiState.value.realDebridBenchmark
                DebridBenchmarkProvider.PREMIUMIZE -> uiState.value.premiumizeBenchmark
                DebridBenchmarkProvider.TORBOX -> uiState.value.torBoxBenchmark
                DebridBenchmarkProvider.EASY_DEBRID -> uiState.value.easyDebridBenchmark
            }
            if (!providerUi.canRun) {
                return@launch
            }

            val started = debridBenchmarkService.start(provider)
            if (!started) {
                messages.tryEmit("A provider benchmark is already running")
            }
        }
    }

    fun cancelBenchmark() {
        viewModelScope.launch {
            debridBenchmarkService.cancel()
        }
    }

    fun startConfigBenchmark(provider: DebridBenchmarkProvider) {
        viewModelScope.launch {
            val providerUi = when (provider) {
                DebridBenchmarkProvider.REAL_DEBRID -> uiState.value.realDebridConfigBenchmark
                DebridBenchmarkProvider.PREMIUMIZE -> uiState.value.premiumizeConfigBenchmark
                DebridBenchmarkProvider.TORBOX -> uiState.value.torBoxConfigBenchmark
                DebridBenchmarkProvider.EASY_DEBRID -> uiState.value.easyDebridConfigBenchmark
            }
            if (!providerUi.canRun) return@launch
            val started = debridConfigBenchmarkService.start(provider)
            if (!started) {
                messages.tryEmit("A provider benchmark is already running")
            }
        }
    }

    fun cancelConfigBenchmark() {
        viewModelScope.launch {
            debridConfigBenchmarkService.cancel()
        }
    }

    fun openLatestBenchmarkResult(provider: DebridBenchmarkProvider) {
        val latestResult = when (provider) {
            DebridBenchmarkProvider.REAL_DEBRID -> uiState.value.realDebridBenchmark.latestResult
            DebridBenchmarkProvider.PREMIUMIZE -> uiState.value.premiumizeBenchmark.latestResult
            DebridBenchmarkProvider.TORBOX -> uiState.value.torBoxBenchmark.latestResult
            DebridBenchmarkProvider.EASY_DEBRID -> uiState.value.easyDebridBenchmark.latestResult
        } ?: return
        setBenchmarkResultDialog(
            DebridBenchmarkResultDialogUi(result = latestResult)
        )
    }

    fun dismissBenchmarkResultDialog() {
        setBenchmarkResultDialog(null)
    }

    fun openLatestConfigBenchmarkResult(provider: DebridBenchmarkProvider) {
        val latestResult = when (provider) {
            DebridBenchmarkProvider.REAL_DEBRID -> uiState.value.realDebridConfigBenchmark.latestResult
            DebridBenchmarkProvider.PREMIUMIZE -> uiState.value.premiumizeConfigBenchmark.latestResult
            DebridBenchmarkProvider.TORBOX -> uiState.value.torBoxConfigBenchmark.latestResult
            DebridBenchmarkProvider.EASY_DEBRID -> uiState.value.easyDebridConfigBenchmark.latestResult
        } ?: return
        setConfigBenchmarkResultDialog(
            DebridConfigBenchmarkResultDialogUi(
                result = latestResult,
                chunkGroups = latestResult.profiles
                    .groupBy { it.chunkSizeMb }
                    .toSortedMap()
                    .map { (chunkSizeMb, rows) -> DebridConfigBenchmarkChunkGroupUi(chunkSizeMb, rows) }
            )
        )
    }

    fun dismissConfigBenchmarkResultDialog() {
        setConfigBenchmarkResultDialog(null)
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

    fun setDebridBenchmarkDataCollectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            playerSettingsDataStore.setDebridBenchmarkDataCollectionEnabled(enabled)
        }
    }

    fun setServiceWrapEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !uiState.value.serviceWrapAvailable) return@launch
            playerSettingsDataStore.setServiceWrapEnabled(enabled)
            if (!enabled && uiState.value.deterministicAutoplayEnabled) {
                playerSettingsDataStore.setDeterministicAutoplayEnabled(false)
            }
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

    private fun buildDebridBenchmarkUi(
        provider: DebridBenchmarkProvider,
        isVisible: Boolean,
        latestResult: DebridBenchmarkResult?,
        activeState: DebridBenchmarkRuntimeState,
        configBenchmarkActive: DebridConfigBenchmarkRuntimeState
    ): DebridProviderBenchmarkUi {
        val activeRun = activeState as? DebridBenchmarkRuntimeState.Running
        val isRunning = activeRun?.provider == provider
        val configActiveRun = configBenchmarkActive as? DebridConfigBenchmarkRuntimeState.Running
        val blockedByActiveRun = (activeRun != null && activeRun.provider != provider) || configActiveRun != null
        return DebridProviderBenchmarkUi(
            isVisible = isVisible,
            canRun = isVisible && !isRunning && !blockedByActiveRun,
            isRunning = isRunning,
            blockedByActiveRun = blockedByActiveRun,
            latestResult = latestResult,
            activeSummary = if (isRunning) activeRun.summary else null
        )
    }

    private fun buildConfigBenchmarkUi(
        provider: DebridBenchmarkProvider,
        isVisible: Boolean,
        latestResult: DebridConfigBenchmarkResult?,
        activeState: DebridConfigBenchmarkRuntimeState,
        benchmarkActive: DebridBenchmarkRuntimeState
    ): DebridProviderConfigBenchmarkUi {
        val activeRun = activeState as? DebridConfigBenchmarkRuntimeState.Running
        val isRunning = activeRun?.provider == provider
        val transportBenchmarkActive = benchmarkActive as? DebridBenchmarkRuntimeState.Running
        val blockedByActiveRun =
            (activeRun != null && activeRun.provider != provider) || transportBenchmarkActive != null
        return DebridProviderConfigBenchmarkUi(
            isVisible = isVisible,
            canRun = isVisible && !isRunning && !blockedByActiveRun,
            isRunning = isRunning,
            blockedByActiveRun = blockedByActiveRun,
            latestResult = latestResult,
            activeProfileLabel = activeRun?.currentProfile?.let { profile ->
                "Testing ${profile.parallelConnectionCount}x / ${profile.chunkSizeMb} MB (${activeRun.completedProfiles + 1} of ${activeRun.totalProfiles})"
            },
            activeSummary = activeRun?.summary
        )
    }

    private fun DebridBenchmarkProvider.displayName(): String {
        return when (this) {
            DebridBenchmarkProvider.REAL_DEBRID -> "Real-Debrid"
            DebridBenchmarkProvider.PREMIUMIZE -> "Premiumize"
            DebridBenchmarkProvider.TORBOX -> "TorBox"
            DebridBenchmarkProvider.EASY_DEBRID -> "EasyDebrid"
        }
    }

    private fun setBenchmarkResultDialog(dialog: DebridBenchmarkResultDialogUi?) {
        benchmarkResultDialog.value = dialog
        _uiState.value = _uiState.value.copy(benchmarkResultDialog = dialog)
    }

    private fun setConfigBenchmarkResultDialog(dialog: DebridConfigBenchmarkResultDialogUi?) {
        configBenchmarkResultDialog.value = dialog
        _uiState.value = _uiState.value.copy(configBenchmarkResultDialog = dialog)
    }
}
