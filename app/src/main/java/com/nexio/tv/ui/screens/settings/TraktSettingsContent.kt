@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import com.nexio.tv.R
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors

@Composable
fun TraktSettingsContent(
    viewModel: TraktViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var showCatalogDialog by remember { mutableStateOf(false) }

    val statusValue = when (uiState.mode) {
        TraktConnectionMode.CONNECTED -> {
            stringResource(R.string.trakt_connected_as, uiState.username ?: "Trakt user")
        }
        TraktConnectionMode.AWAITING_APPROVAL -> stringResource(R.string.trakt_waiting_approval)
        TraktConnectionMode.DISCONNECTED -> stringResource(R.string.trakt_auth_status_disconnected)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.mdblist_trakt_title),
            subtitle = stringResource(R.string.trakt_description)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "trakt_status") {
                    SettingsActionRow(
                        title = stringResource(R.string.trakt_auth_status_title),
                        subtitle = stringResource(R.string.trakt_auth_status_subtitle),
                        value = statusValue,
                        enabled = false,
                        onClick = {}
                    )
                }

                when (uiState.mode) {
                    TraktConnectionMode.CONNECTED -> {
                        item(key = "trakt_disconnect") {
                            SettingsActionRow(
                                title = stringResource(R.string.trakt_disconnect),
                                subtitle = stringResource(R.string.trakt_disconnect_subtitle),
                                onClick = { showDisconnectConfirm = true },
                                modifier = if (initialFocusRequester != null) {
                                    androidx.compose.ui.Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    androidx.compose.ui.Modifier
                                }
                            )
                        }
                        item(key = "trakt_sync_now") {
                            SettingsActionRow(
                                title = stringResource(R.string.action_sync_now),
                                subtitle = stringResource(R.string.trakt_sync_now_subtitle),
                                onClick = { viewModel.onSyncNow() }
                            )
                        }
                        item(key = "trakt_catalogs") {
                            SettingsActionRow(
                                title = stringResource(R.string.trakt_catalogs_title),
                                subtitle = stringResource(R.string.trakt_catalogs_subtitle),
                                value = stringResource(
                                    R.string.trakt_catalogs_enabled_count,
                                    uiState.catalogPreferences.enabledCatalogs.size
                                ),
                                onClick = {
                                    viewModel.onCatalogManagementOpened()
                                    showCatalogDialog = true
                                }
                            )
                        }
                    }

                    TraktConnectionMode.AWAITING_APPROVAL -> {
                        item(key = "trakt_activation_code") {
                            SettingsActionRow(
                                title = stringResource(R.string.trakt_awaiting_instruction),
                                subtitle = uiState.verificationUrl ?: "https://trakt.tv/activate",
                                value = uiState.deviceUserCode ?: "-",
                                onClick = {},
                                enabled = false,
                                modifier = if (initialFocusRequester != null) {
                                    androidx.compose.ui.Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    androidx.compose.ui.Modifier
                                }
                            )
                        }
                        item(key = "trakt_retry_polling") {
                            SettingsActionRow(
                                title = stringResource(R.string.trakt_retry),
                                subtitle = stringResource(R.string.trakt_waiting_approval),
                                onClick = { viewModel.onRetryPolling() }
                            )
                        }
                        item(key = "trakt_cancel_flow") {
                            SettingsActionRow(
                                title = stringResource(R.string.action_cancel),
                                subtitle = stringResource(R.string.trakt_waiting_approval),
                                onClick = { viewModel.onCancelDeviceFlow() }
                            )
                        }
                    }

                    TraktConnectionMode.DISCONNECTED -> {
                        item(key = "trakt_connect") {
                            SettingsActionRow(
                                title = stringResource(R.string.trakt_login),
                                subtitle = stringResource(R.string.trakt_login_instruction),
                                onClick = { viewModel.onConnectClick() },
                                modifier = if (initialFocusRequester != null) {
                                    androidx.compose.ui.Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    androidx.compose.ui.Modifier
                                }
                            )
                        }
                    }
                }

                uiState.errorMessage?.let { error ->
                    item(key = "trakt_error_text") {
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

    if (showCatalogDialog && uiState.mode == TraktConnectionMode.CONNECTED) {
        var popularListSearch by remember { mutableStateOf("") }
        val keyboardController = LocalSoftwareKeyboardController.current
        val filteredPopularLists = remember(uiState.popularLists, popularListSearch) {
            filterTraktPopularLists(uiState.popularLists, popularListSearch)
        }

        NexioDialog(
            onDismiss = { showCatalogDialog = false },
            title = stringResource(R.string.trakt_catalogs_title),
            subtitle = stringResource(R.string.trakt_catalogs_dialog_subtitle),
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
                            title = traktCatalogTitle(catalogId),
                            subtitle = traktCatalogSubtitle(catalogId),
                            checked = enabled,
                            onToggle = {
                                viewModel.onCatalogEnabledChanged(catalogId, !enabled)
                            }
                        )
                    }

                    item(key = "popular_lists_header") {
                        Spacer(modifier = androidx.compose.ui.Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.trakt_popular_lists_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = NexioColors.TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.trakt_popular_lists_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = NexioColors.TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SettingsCatalogSearchField(
                            value = popularListSearch,
                            onValueChange = { popularListSearch = it },
                            placeholderTextRes = R.string.trakt_popular_lists_search_hint,
                            keyboardController = keyboardController
                        )
                    }

                    items(
                        items = filteredPopularLists,
                        key = { it.key }
                    ) { option ->
                        val selected = option.key in uiState.catalogPreferences.selectedPopularListKeys
                        SettingsToggleRow(
                            title = option.title,
                            subtitle = stringResource(R.string.mdblist_list_item_count_subtitle, option.itemCount),
                            checked = selected,
                            onToggle = { viewModel.onPopularListSelected(option.key, !selected) }
                        )
                    }

                    if (filteredPopularLists.isEmpty()) {
                        item(key = "popular_lists_empty") {
                            Text(
                                text = if (uiState.popularLists.isEmpty()) {
                                    stringResource(R.string.trakt_popular_lists_empty)
                                } else {
                                    stringResource(R.string.trakt_popular_lists_no_search_results)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = NexioColors.TextSecondary
                            )
                        }
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

    if (showDisconnectConfirm) {
        NexioDialog(
            onDismiss = { showDisconnectConfirm = false },
            title = stringResource(R.string.trakt_disconnect_title),
            subtitle = stringResource(R.string.trakt_disconnect_subtitle),
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
                ) { Text(stringResource(R.string.trakt_disconnect)) }
                Button(
                    onClick = { showDisconnectConfirm = false },
                    colors = ButtonDefaults.colors(
                        containerColor = NexioColors.BackgroundCard,
                        contentColor = NexioColors.TextPrimary
                    )
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

@Composable
private fun traktCatalogTitle(catalogId: String): String {
    return when (catalogId) {
        TraktCatalogIds.UP_NEXT -> stringResource(R.string.trakt_catalog_up_next)
        TraktCatalogIds.TRENDING_MOVIES -> stringResource(R.string.trakt_catalog_trending_movies)
        TraktCatalogIds.TRENDING_SHOWS -> stringResource(R.string.trakt_catalog_trending_shows)
        TraktCatalogIds.POPULAR_MOVIES -> stringResource(R.string.trakt_catalog_popular_movies)
        TraktCatalogIds.POPULAR_SHOWS -> stringResource(R.string.trakt_catalog_popular_shows)
        TraktCatalogIds.RECOMMENDED_MOVIES -> stringResource(R.string.trakt_catalog_recommended_movies)
        TraktCatalogIds.RECOMMENDED_SHOWS -> stringResource(R.string.trakt_catalog_recommended_shows)
        TraktCatalogIds.CALENDAR -> stringResource(R.string.trakt_catalog_calendar)
        else -> catalogId
    }
}

@Composable
private fun traktCatalogSubtitle(catalogId: String): String {
    return when (catalogId) {
        TraktCatalogIds.UP_NEXT -> stringResource(R.string.trakt_catalog_up_next_subtitle)
        TraktCatalogIds.TRENDING_MOVIES -> stringResource(R.string.trakt_catalog_trending_movies_subtitle)
        TraktCatalogIds.TRENDING_SHOWS -> stringResource(R.string.trakt_catalog_trending_shows_subtitle)
        TraktCatalogIds.POPULAR_MOVIES -> stringResource(R.string.trakt_catalog_popular_movies_subtitle)
        TraktCatalogIds.POPULAR_SHOWS -> stringResource(R.string.trakt_catalog_popular_shows_subtitle)
        TraktCatalogIds.RECOMMENDED_MOVIES -> stringResource(R.string.trakt_catalog_recommended_movies_subtitle)
        TraktCatalogIds.RECOMMENDED_SHOWS -> stringResource(R.string.trakt_catalog_recommended_shows_subtitle)
        TraktCatalogIds.CALENDAR -> stringResource(R.string.trakt_catalog_calendar_subtitle)
        else -> ""
    }
}
