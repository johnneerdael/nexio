@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.R
import com.nexio.tv.data.local.SimklCatalogIds
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors

@Composable
fun SimklSettingsContent(
    viewModel: SimklViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var showCatalogDialog by remember { mutableStateOf(false) }

    val statusValue = when (uiState.mode) {
        SimklConnectionMode.CONNECTED -> uiState.username?.let { stringResource(R.string.simkl_connected_as, it) }
            ?: stringResource(R.string.simkl_auth_status_authenticated)
        SimklConnectionMode.AWAITING_APPROVAL -> stringResource(R.string.simkl_waiting_approval)
        SimklConnectionMode.DISCONNECTED -> stringResource(R.string.simkl_auth_status_disconnected)
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.simkl_title),
            subtitle = stringResource(R.string.simkl_description)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "simkl_status") {
                    SettingsActionRow(
                        title = stringResource(R.string.simkl_auth_status_title),
                        subtitle = stringResource(R.string.simkl_auth_status_subtitle),
                        value = statusValue,
                        enabled = false,
                        onClick = {}
                    )
                }

                item(key = "simkl_catalogs") {
                    SettingsActionRow(
                        title = stringResource(R.string.simkl_catalogs_title),
                        subtitle = stringResource(R.string.simkl_catalogs_subtitle),
                        value = stringResource(
                            R.string.simkl_catalogs_enabled_count,
                            uiState.catalogPreferences.enabledCatalogs.size
                        ),
                        onClick = { showCatalogDialog = true }
                    )
                }

                when (uiState.mode) {
                    SimklConnectionMode.CONNECTED -> {
                        item(key = "simkl_disconnect") {
                            SettingsActionRow(
                                title = stringResource(R.string.simkl_disconnect),
                                subtitle = stringResource(R.string.simkl_disconnect_subtitle),
                                onClick = { showDisconnectConfirm = true },
                                modifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
                            )
                        }
                        item(key = "simkl_sync_now") {
                            SettingsActionRow(
                                title = stringResource(R.string.action_sync_now),
                                subtitle = stringResource(R.string.simkl_sync_now_subtitle),
                                onClick = { viewModel.onSyncNow() }
                            )
                        }
                    }
                    SimklConnectionMode.AWAITING_APPROVAL -> {
                        item(key = "simkl_activation_code") {
                            SettingsActionRow(
                                title = stringResource(R.string.simkl_awaiting_instruction),
                                subtitle = uiState.verificationUrl ?: "https://simkl.com/pin/",
                                value = uiState.deviceUserCode ?: "-",
                                onClick = {},
                                enabled = false,
                                modifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
                            )
                        }
                        item(key = "simkl_retry_polling") {
                            SettingsActionRow(
                                title = stringResource(R.string.simkl_retry),
                                subtitle = stringResource(R.string.simkl_waiting_approval),
                                onClick = { viewModel.onRetryPolling() }
                            )
                        }
                        item(key = "simkl_cancel_flow") {
                            SettingsActionRow(
                                title = stringResource(R.string.action_cancel),
                                subtitle = stringResource(R.string.simkl_waiting_approval),
                                onClick = { viewModel.onCancelDeviceFlow() }
                            )
                        }
                    }
                    SimklConnectionMode.DISCONNECTED -> {
                        item(key = "simkl_connect") {
                            SettingsActionRow(
                                title = stringResource(R.string.simkl_login),
                                subtitle = stringResource(R.string.simkl_login_instruction),
                                onClick = { viewModel.onConnectClick() },
                                modifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
                            )
                        }
                    }
                }

                uiState.errorMessage?.let { error ->
                    item(key = "simkl_error_text") {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = NexioColors.Error
                        )
                    }
                }
            }
        }
    }

    if (showDisconnectConfirm) {
        NexioDialog(
            onDismiss = { showDisconnectConfirm = false },
            title = stringResource(R.string.simkl_disconnect),
            subtitle = stringResource(R.string.simkl_disconnect_subtitle),
            width = 520.dp,
            suppressFirstKeyUp = false
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        showDisconnectConfirm = false
                        viewModel.onDisconnectClick()
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = NexioColors.BackgroundCard,
                        contentColor = NexioColors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.simkl_disconnect))
                }
                Button(
                    onClick = { showDisconnectConfirm = false },
                    colors = ButtonDefaults.colors(
                        containerColor = NexioColors.BackgroundCard,
                        contentColor = NexioColors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }

    if (showCatalogDialog) {
        NexioDialog(
            onDismiss = { showCatalogDialog = false },
            title = stringResource(R.string.simkl_catalogs_title),
            subtitle = stringResource(R.string.simkl_catalogs_dialog_subtitle),
            width = 900.dp,
            suppressFirstKeyUp = false
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = uiState.catalogPreferences.catalogOrder,
                        key = { it }
                    ) { catalogId ->
                        val enabled = catalogId in uiState.catalogPreferences.enabledCatalogs
                        SettingsToggleRow(
                            title = simklCatalogTitle(catalogId),
                            subtitle = simklCatalogSubtitle(catalogId),
                            checked = enabled,
                            onToggle = { viewModel.onCatalogEnabledChanged(catalogId, !enabled) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { showCatalogDialog = false },
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
}

@Composable
private fun simklCatalogTitle(catalogId: String): String {
    return when (catalogId) {
        com.nexio.tv.data.local.SimklCatalogIds.TV_TRENDING_TODAY -> stringResource(R.string.simkl_catalog_tv_trending_today)
        com.nexio.tv.data.local.SimklCatalogIds.TV_TRENDING_WEEK -> stringResource(R.string.simkl_catalog_tv_trending_week)
        com.nexio.tv.data.local.SimklCatalogIds.TV_TRENDING_MONTH -> stringResource(R.string.simkl_catalog_tv_trending_month)
        com.nexio.tv.data.local.SimklCatalogIds.ANIME_TRENDING_TODAY -> stringResource(R.string.simkl_catalog_anime_trending_today)
        com.nexio.tv.data.local.SimklCatalogIds.ANIME_TRENDING_WEEK -> stringResource(R.string.simkl_catalog_anime_trending_week)
        com.nexio.tv.data.local.SimklCatalogIds.ANIME_TRENDING_MONTH -> stringResource(R.string.simkl_catalog_anime_trending_month)
        com.nexio.tv.data.local.SimklCatalogIds.MOVIE_TRENDING_TODAY -> stringResource(R.string.simkl_catalog_movie_trending_today)
        com.nexio.tv.data.local.SimklCatalogIds.MOVIE_TRENDING_WEEK -> stringResource(R.string.simkl_catalog_movie_trending_week)
        com.nexio.tv.data.local.SimklCatalogIds.MOVIE_TRENDING_MONTH -> stringResource(R.string.simkl_catalog_movie_trending_month)
        com.nexio.tv.data.local.SimklCatalogIds.DVD_RELEASES -> stringResource(R.string.simkl_catalog_dvd_releases)
        else -> catalogId
    }
}

@Composable
private fun simklCatalogSubtitle(catalogId: String): String {
    return when (catalogId) {
        com.nexio.tv.data.local.SimklCatalogIds.TV_TRENDING_TODAY -> stringResource(R.string.simkl_catalog_tv_trending_today_subtitle)
        com.nexio.tv.data.local.SimklCatalogIds.TV_TRENDING_WEEK -> stringResource(R.string.simkl_catalog_tv_trending_week_subtitle)
        com.nexio.tv.data.local.SimklCatalogIds.TV_TRENDING_MONTH -> stringResource(R.string.simkl_catalog_tv_trending_month_subtitle)
        com.nexio.tv.data.local.SimklCatalogIds.ANIME_TRENDING_TODAY -> stringResource(R.string.simkl_catalog_anime_trending_today_subtitle)
        com.nexio.tv.data.local.SimklCatalogIds.ANIME_TRENDING_WEEK -> stringResource(R.string.simkl_catalog_anime_trending_week_subtitle)
        com.nexio.tv.data.local.SimklCatalogIds.ANIME_TRENDING_MONTH -> stringResource(R.string.simkl_catalog_anime_trending_month_subtitle)
        com.nexio.tv.data.local.SimklCatalogIds.MOVIE_TRENDING_TODAY -> stringResource(R.string.simkl_catalog_movie_trending_today_subtitle)
        com.nexio.tv.data.local.SimklCatalogIds.MOVIE_TRENDING_WEEK -> stringResource(R.string.simkl_catalog_movie_trending_week_subtitle)
        com.nexio.tv.data.local.SimklCatalogIds.MOVIE_TRENDING_MONTH -> stringResource(R.string.simkl_catalog_movie_trending_month_subtitle)
        com.nexio.tv.data.local.SimklCatalogIds.DVD_RELEASES -> stringResource(R.string.simkl_catalog_dvd_releases_subtitle)
        else -> ""
    }
}
