@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.addon

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.CardDefaults as TvCardDefaults
import androidx.tv.material3.Card as TvCard
import com.nexio.tv.ui.components.LoadingIndicator
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors
import androidx.compose.ui.res.stringResource
import com.nexio.tv.R
import kotlinx.coroutines.launch

@Composable
fun CatalogOrderScreen(
    viewModel: CatalogOrderViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showAndroidTvDialog by remember { mutableStateOf(false) }

    BackHandler { onBackPress() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NexioColors.Background)
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.catalog_order_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = NexioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.catalog_order_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexioColors.TextSecondary
                )
            }

            item(key = "android_tv_launcher_feeds") {
                AndroidTvLauncherCard(
                    enabled = uiState.androidTvChannelsEnabled,
                    selectedCount = uiState.androidTvSelectedFeedKeys.size,
                    onClick = { showAndroidTvDialog = true }
                )
            }

            when {
                uiState.isLoading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator()
                        }
                    }
                }

                uiState.items.isEmpty() -> {
                    item {
                        Text(
                            text = stringResource(R.string.catalog_order_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NexioColors.TextSecondary
                        )
                    }
                }

                else -> {
                    itemsIndexed(
                        items = uiState.items,
                        key = { _, item -> item.key }
                    ) { index, item ->
                        CatalogOrderCard(
                            item = item,
                            onMoveUp = {
                                viewModel.moveUp(item.key)
                                scope.launch {
                                    listState.animateScrollToItem((index - 1).coerceAtLeast(0))
                                }
                            },
                            onMoveDown = {
                                viewModel.moveDown(item.key)
                                scope.launch {
                                    listState.animateScrollToItem(
                                        (index + 1).coerceAtMost(uiState.items.lastIndex)
                                    )
                                }
                            },
                            onToggleEnabled = { viewModel.toggleCatalogEnabled(item.disableKey) }
                        )
                    }
                }
            }
        }
    }

    if (showAndroidTvDialog) {
        AndroidTvLauncherDialog(
            enabled = uiState.androidTvChannelsEnabled,
            selectedFeedKeys = uiState.androidTvSelectedFeedKeys.toSet(),
            feedOptions = uiState.androidTvFeedOptions,
            onDismiss = { showAndroidTvDialog = false },
            onEnabledChange = viewModel::setAndroidTvChannelsEnabled,
            onToggleFeed = viewModel::toggleAndroidTvFeed
        )
    }
}

@Composable
private fun CatalogOrderCard(
    item: CatalogOrderItem,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleEnabled: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NexioColors.BackgroundCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${item.catalogName} - ${item.typeLabel.toDisplayTypeLabel()}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (item.isDisabled) NexioColors.TextSecondary else NexioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.addonName,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexioColors.TextSecondary
                )
                if (item.isToggleable && item.isDisabled) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.catalog_order_disabled_on_home),
                        style = MaterialTheme.typography.bodySmall,
                        color = NexioColors.Error
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onMoveUp,
                    enabled = item.canMoveUp,
                    colors = ButtonDefaults.colors(
                        containerColor = NexioColors.BackgroundCard,
                        contentColor = NexioColors.TextSecondary,
                        focusedContainerColor = NexioColors.FocusBackground,
                        focusedContentColor = NexioColors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NexioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Move up"
                    )
                }

                Button(
                    onClick = onMoveDown,
                    enabled = item.canMoveDown,
                    colors = ButtonDefaults.colors(
                        containerColor = NexioColors.BackgroundCard,
                        contentColor = NexioColors.TextSecondary,
                        focusedContainerColor = NexioColors.FocusBackground,
                        focusedContentColor = NexioColors.Primary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, NexioColors.FocusRing),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Move down"
                    )
                }

                if (item.isToggleable) {
                    Button(
                        onClick = onToggleEnabled,
                        colors = ButtonDefaults.colors(
                            containerColor = NexioColors.BackgroundCard,
                            contentColor = if (item.isDisabled) NexioColors.Success else NexioColors.TextSecondary,
                            focusedContainerColor = NexioColors.FocusBackground,
                            focusedContentColor = if (item.isDisabled) NexioColors.Success else NexioColors.Error
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, NexioColors.FocusRing),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                    ) {
                        Text(text = if (item.isDisabled) stringResource(R.string.catalog_order_enable) else stringResource(R.string.catalog_order_disable))
                    }
                }
            }
        }
    }
}

private fun String.toDisplayTypeLabel(): String {
    return replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
    }
}

@Composable
private fun AndroidTvLauncherCard(
    enabled: Boolean,
    selectedCount: Int,
    onClick: () -> Unit
) {
    TvCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = TvCardDefaults.colors(
            containerColor = NexioColors.BackgroundCard,
            focusedContainerColor = NexioColors.BackgroundCard
        ),
        border = TvCardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NexioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        shape = TvCardDefaults.shape(RoundedCornerShape(12.dp)),
        scale = TvCardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Cast,
                    contentDescription = null,
                    tint = NexioColors.Primary
                )
                Column {
                    Text(
                        text = stringResource(R.string.android_tv_channels_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = NexioColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.android_tv_channels_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = NexioColors.TextSecondary
                    )
                }
            }

            Text(
                text = if (enabled) {
                    stringResource(R.string.android_tv_channels_value_count, selectedCount)
                } else {
                    stringResource(R.string.android_tv_channels_value_off)
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) NexioColors.Success else NexioColors.TextSecondary
            )
        }
    }
}

@Composable
private fun AndroidTvLauncherDialog(
    enabled: Boolean,
    selectedFeedKeys: Set<String>,
    feedOptions: List<com.nexio.tv.core.recommendations.AndroidTvFeedOption>,
    onDismiss: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onToggleFeed: (String) -> Unit
) {
    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.android_tv_channels_dialog_title),
        subtitle = stringResource(R.string.android_tv_channels_dialog_subtitle),
        width = 760.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (enabled) {
                        stringResource(R.string.android_tv_channels_enabled)
                    } else {
                        stringResource(R.string.android_tv_channels_disabled)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = NexioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (enabled) {
                        stringResource(R.string.android_tv_channels_enabled_body)
                    } else {
                        stringResource(R.string.android_tv_channels_disabled_body)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NexioColors.TextSecondary
                )
            }

            Button(
                onClick = { onEnabledChange(!enabled) },
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = if (enabled) NexioColors.Error else NexioColors.Success,
                    focusedContainerColor = NexioColors.FocusBackground,
                    focusedContentColor = if (enabled) NexioColors.Error else NexioColors.Success
                ),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NexioColors.FocusRing),
                        shape = RoundedCornerShape(12.dp)
                    )
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
            ) {
                Text(
                    text = if (enabled) {
                        stringResource(R.string.android_tv_channels_turn_off)
                    } else {
                        stringResource(R.string.android_tv_channels_turn_on)
                    }
                )
            }
        }

        if (!enabled) {
            Text(
                text = stringResource(R.string.android_tv_channels_disabled_note),
                style = MaterialTheme.typography.bodyMedium,
                color = NexioColors.TextSecondary
            )
            return@NexioDialog
        }

        Text(
            text = if (selectedFeedKeys.isEmpty()) {
                stringResource(R.string.android_tv_channels_empty_selection)
            } else {
                stringResource(R.string.android_tv_channels_value_count, selectedFeedKeys.size)
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (selectedFeedKeys.isEmpty()) NexioColors.TextSecondary else NexioColors.Success
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = feedOptions,
                key = { item -> item.key }
            ) { option ->
                val selected = option.key in selectedFeedKeys
                AndroidTvFeedOptionCard(
                    title = option.title,
                    subtitle = option.subtitle,
                    selected = selected,
                    isDisabledOnHome = option.isDisabledOnHome,
                    onClick = { onToggleFeed(option.key) }
                )
            }
        }
    }
}

@Composable
private fun AndroidTvFeedOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    isDisabledOnHome: Boolean,
    onClick: () -> Unit
) {
    TvCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = TvCardDefaults.colors(
            containerColor = if (selected) NexioColors.BackgroundCard else NexioColors.Background,
            focusedContainerColor = NexioColors.BackgroundCard
        ),
        border = TvCardDefaults.border(
            border = if (selected) {
                Border(
                    border = BorderStroke(1.dp, NexioColors.Success),
                    shape = RoundedCornerShape(14.dp)
                )
            } else {
                Border.None
            },
            focusedBorder = Border(
                border = BorderStroke(2.dp, NexioColors.FocusRing),
                shape = RoundedCornerShape(14.dp)
            )
        ),
        shape = TvCardDefaults.shape(RoundedCornerShape(14.dp)),
        scale = TvCardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = NexioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexioColors.TextSecondary
                )
                if (isDisabledOnHome) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.android_tv_channels_hidden_on_home),
                        style = MaterialTheme.typography.labelSmall,
                        color = NexioColors.Secondary
                    )
                }
            }

            Text(
                text = if (selected) {
                    stringResource(R.string.android_tv_channels_selected)
                } else {
                    stringResource(R.string.android_tv_channels_not_selected)
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) NexioColors.Success else NexioColors.TextSecondary
            )
        }
    }
}
