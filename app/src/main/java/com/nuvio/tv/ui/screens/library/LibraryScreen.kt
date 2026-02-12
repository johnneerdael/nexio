package com.nuvio.tv.ui.screens.library

import android.view.KeyEvent as AndroidKeyEvent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.TraktListPrivacy
import com.nuvio.tv.ui.components.ContentCard
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (uiState.sourceMode == LibrarySourceMode.TRAKT) {
            item {
                PrimaryListTabs(
                    tabs = uiState.listTabs,
                    selectedKey = uiState.selectedListKey,
                    onTabSelected = viewModel::onSelectListTab
                )
            }
        }

        item {
            TypeTabs(
                selectedTab = uiState.selectedTypeTab,
                onTabSelected = viewModel::onSelectTypeTab
            )
        }

        if (uiState.sourceMode == LibrarySourceMode.TRAKT) {
            item {
                LibraryActionsRow(
                    onManageLists = viewModel::onOpenManageLists,
                    onRefresh = viewModel::onRefresh
                )
            }
        }

        item {
            if (uiState.visibleItems.isEmpty()) {
                val title = when (uiState.sourceMode) {
                    LibrarySourceMode.LOCAL -> "No ${uiState.selectedTypeTab.label.lowercase()} yet"
                    LibrarySourceMode.TRAKT -> "No ${uiState.selectedTypeTab.label.lowercase()} in this list"
                }
                val subtitle = when (uiState.sourceMode) {
                    LibrarySourceMode.LOCAL -> "Start saving your favorites to see them here"
                    LibrarySourceMode.TRAKT -> "Use + in details to add items to watchlist or lists"
                }
                EmptyScreenState(
                    title = title,
                    subtitle = subtitle,
                    icon = Icons.Default.BookmarkBorder
                )
            }
        }

        if (uiState.visibleItems.isNotEmpty()) {
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(uiState.visibleItems, key = { "${it.type}:${it.id}" }) { item ->
                        ContentCard(
                            item = item.toMetaPreview(),
                            onClick = {
                                onNavigateToDetail(item.id, item.type, item.addonBaseUrl)
                            }
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }

    if (uiState.showManageDialog && uiState.sourceMode == LibrarySourceMode.TRAKT) {
        ManageListsDialog(
            tabs = uiState.listTabs,
            selectedKey = uiState.manageSelectedListKey,
            errorMessage = uiState.errorMessage,
            pending = uiState.pendingOperation,
            onSelect = viewModel::onSelectManageList,
            onCreate = viewModel::onStartCreateList,
            onEdit = viewModel::onStartEditList,
            onMoveUp = viewModel::onMoveSelectedListUp,
            onMoveDown = viewModel::onMoveSelectedListDown,
            onDelete = { showDeleteConfirm = true },
            onDismiss = viewModel::onCloseManageLists
        )
    }

    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            pending = uiState.pendingOperation,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.onDeleteSelectedList()
            },
            onCancel = { showDeleteConfirm = false }
        )
    }

    val listEditor = uiState.listEditorState
    if (listEditor != null && uiState.showManageDialog) {
        ListEditorDialog(
            state = listEditor,
            pending = uiState.pendingOperation,
            onNameChanged = viewModel::onUpdateEditorName,
            onDescriptionChanged = viewModel::onUpdateEditorDescription,
            onPrivacyChanged = viewModel::onUpdateEditorPrivacy,
            onSave = viewModel::onSubmitEditor,
            onCancel = viewModel::onCancelEditor
        )
    }

    val transientMessage = uiState.transientMessage
    if (!transientMessage.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter
        ) {
            Text(
                text = transientMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextPrimary,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .background(NuvioColors.BackgroundElevated, RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PrimaryListTabs(
    tabs: List<LibraryListTab>,
    selectedKey: String?,
    onTabSelected: (String) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(tabs, key = { it.key }) { tab ->
            ListTabPill(
                label = tab.title,
                selected = tab.key == selectedKey,
                onClick = { onTabSelected(tab.key) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TypeTabs(
    selectedTab: LibraryTypeTab,
    onTabSelected: (LibraryTypeTab) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(LibraryTypeTab.entries.toList(), key = { it.name }) { tab ->
            ListTabPill(
                label = tab.label,
                selected = tab == selectedTab,
                onClick = { onTabSelected(tab) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ListTabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = RoundedCornerShape(20.dp)),
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioColors.SurfaceVariant else NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.Secondary
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(20.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.0f),
        modifier = Modifier.onFocusChanged { isFocused = it.isFocused }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                isFocused -> NuvioColors.OnPrimary
                selected -> NuvioColors.TextPrimary
                else -> NuvioTheme.extendedColors.textSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryActionsRow(
    onManageLists: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onManageLists,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text("Manage Lists")
        }
        Button(
            onClick = onRefresh,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            )
        ) {
            Text("Sync")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManageListsDialog(
    tabs: List<LibraryListTab>,
    selectedKey: String?,
    errorMessage: String?,
    pending: Boolean,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val personalTabs = remember(tabs) { tabs.filter { it.type == LibraryListTab.Type.PERSONAL } }
    val firstFocusRequester = remember { FocusRequester() }
    var suppressNextKeyUp by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        firstFocusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(620.dp)
                .background(NuvioColors.BackgroundElevated, RoundedCornerShape(16.dp))
                .padding(24.dp)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (suppressNextKeyUp && native.action == AndroidKeyEvent.ACTION_UP) {
                        val isSelectOrMenu = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                            native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                            native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
                            native.keyCode == AndroidKeyEvent.KEYCODE_MENU
                        if (isSelectOrMenu) {
                            suppressNextKeyUp = false
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Manage Trakt Lists",
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioColors.TextPrimary
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFB6B6)
                    )
                }

                if (personalTabs.isEmpty()) {
                    Text(
                        text = "No personal lists yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.extendedColors.textSecondary
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(personalTabs, key = { it.key }) { tab ->
                            val selected = tab.key == selectedKey
                            Button(
                                onClick = { onSelect(tab.key) },
                                enabled = !pending,
                                modifier = if (tab.key == personalTabs.firstOrNull()?.key) {
                                    Modifier
                                        .fillMaxWidth()
                                        .focusRequester(firstFocusRequester)
                                } else {
                                    Modifier.fillMaxWidth()
                                },
                                colors = ButtonDefaults.colors(
                                    containerColor = if (selected) NuvioColors.FocusBackground else NuvioColors.BackgroundCard,
                                    contentColor = NuvioColors.TextPrimary
                                )
                            ) {
                                Text(
                                    text = tab.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onCreate,
                        enabled = !pending,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) { Text("Create") }
                    Button(
                        onClick = onEdit,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) { Text("Edit") }
                    Button(
                        onClick = onMoveUp,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) { Text("Move Up") }
                    Button(
                        onClick = onMoveDown,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) { Text("Move Down") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onDelete,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFF4A2323),
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) { Text("Delete") }
                    Button(
                        onClick = onDismiss,
                        enabled = !pending,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) { Text("Close") }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ListEditorDialog(
    state: LibraryListEditorState,
    pending: Boolean,
    onNameChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onPrivacyChanged: (TraktListPrivacy) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Box(
            modifier = Modifier
                .width(560.dp)
                .background(NuvioColors.BackgroundElevated, RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (state.mode == LibraryListEditorState.Mode.CREATE) "Create List" else "Edit List",
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioColors.TextPrimary
                )

                androidx.compose.material3.OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !pending,
                    label = { androidx.compose.material3.Text("Name") }
                )

                androidx.compose.material3.OutlinedTextField(
                    value = state.description,
                    onValueChange = onDescriptionChanged,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !pending,
                    minLines = 3,
                    maxLines = 5,
                    label = { androidx.compose.material3.Text("Description") }
                )

                Text(
                    text = "Privacy",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(TraktListPrivacy.entries.toList(), key = { it.name }) { privacy ->
                        val selected = privacy == state.privacy
                        Button(
                            onClick = { onPrivacyChanged(privacy) },
                            enabled = !pending,
                            colors = ButtonDefaults.colors(
                                containerColor = if (selected) NuvioColors.FocusBackground else NuvioColors.BackgroundCard,
                                contentColor = NuvioColors.TextPrimary
                            )
                        ) {
                            Text(privacy.apiValue.replaceFirstChar { it.uppercase() })
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onSave,
                        enabled = !pending,
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        )
                    ) {
                        Text(if (pending) "Saving..." else "Save")
                    }
                    Button(
                        onClick = onCancel,
                        enabled = !pending,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConfirmDeleteDialog(
    pending: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Box(
            modifier = Modifier
                .width(420.dp)
                .background(NuvioColors.BackgroundElevated, RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Delete this list?",
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioColors.TextPrimary
                )
                Text(
                    text = "This removes the list and all list items from Trakt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onConfirm,
                        enabled = !pending,
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFF4A2323),
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) {
                        Text("Delete")
                    }
                    Button(
                        onClick = onCancel,
                        enabled = !pending,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
