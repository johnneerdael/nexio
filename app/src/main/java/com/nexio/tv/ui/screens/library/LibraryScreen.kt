package com.nexio.tv.ui.screens.library

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.TraktListPrivacy
import com.nexio.tv.ui.components.EmptyScreenState
import com.nexio.tv.ui.components.GridContentCard
import com.nexio.tv.ui.components.PosterCardDefaults
import com.nexio.tv.ui.components.LoadingIndicator
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors
import com.nexio.tv.ui.theme.NexioTheme
import com.nexio.tv.ui.util.formatAddonTypeLabel
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.nexio.tv.R
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryEmptyReason
import com.nexio.tv.domain.model.LibraryListManagementMode
import com.nexio.tv.domain.model.LibraryProviderOption
import com.nexio.tv.domain.model.LibraryProviderSelection
import java.util.Locale

private const val KEY_REPEAT_THROTTLE_MS = 80L

/** Args carried from a TorBox library card click out to the navigator. */
data class TorBoxDirectPlayArgs(
    val url: String,
    val torrentId: Int,
    val fileId: Int,
    val fileName: String,
    val resumePositionMs: Long,
    val deterministicAutoplay: Boolean,
)

@Composable
private fun localizedTypeLabel(key: String): String = when (key.lowercase()) {
    LibraryTypeTab.ALL_KEY -> stringResource(R.string.library_type_all)
    "movie" -> stringResource(R.string.type_movie)
    "series" -> stringResource(R.string.type_series)
    else -> formatAddonTypeLabel(key)
}

private fun buildLibraryTypeTabs(items: List<LibraryEntry>): List<LibraryTypeTab> {
    val byKey = linkedMapOf<String, LibraryTypeTab>()
    for (i in items.indices) {
        val key = items[i].type.trim().ifBlank { "unknown" }.lowercase(Locale.ROOT)
        if (byKey.containsKey(key)) continue
        byKey[key] = LibraryTypeTab(key = key, label = prettifyLibraryTypeLabel(key))
    }
    return listOf(LibraryTypeTab.All) + byKey.values
}

private fun prettifyLibraryTypeLabel(key: String): String {
    return key
        .replace('_', ' ')
        .replace('-', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
            }
        }
        .ifBlank { "Unknown" }
}

private fun List<LibraryEntry>.visibleLibraryItems(
    selectedTypeTab: LibraryTypeTab,
    selectedSortOption: LibrarySortOption,
    selectedListType: LibraryListTab.Type?
): List<LibraryEntry> {
    val selectedTypeKey = selectedTypeTab.key
    val typeFiltered = filter { entry ->
        selectedTypeKey == LibraryTypeTab.ALL_KEY ||
            entry.type.trim().lowercase(Locale.ROOT) == selectedTypeKey
    }
    return when (selectedSortOption) {
        LibrarySortOption.DEFAULT -> if (
            selectedListType == LibraryListTab.Type.WATCHLIST ||
            selectedListType == LibraryListTab.Type.PERSONAL
        ) {
            typeFiltered.sortedWith(
                compareBy<LibraryEntry> { it.traktRank ?: Int.MAX_VALUE }
                    .thenByDescending { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
        } else {
            typeFiltered
        }
        LibrarySortOption.ADDED_DESC -> typeFiltered.sortedWith(
            compareByDescending<LibraryEntry> { it.listedAt }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                .thenBy { it.id }
        )
        LibrarySortOption.ADDED_ASC -> typeFiltered.sortedWith(
            compareBy<LibraryEntry> { it.listedAt }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                .thenBy { it.id }
        )
        LibrarySortOption.TITLE_ASC -> typeFiltered.sortedWith(
            compareBy<LibraryEntry> { it.name.ifBlank { it.id }.lowercase(Locale.ROOT) }
                .thenBy { it.id }
        )
        LibrarySortOption.TITLE_DESC -> typeFiltered.sortedWith(
            compareByDescending<LibraryEntry> { it.name.ifBlank { it.id }.lowercase(Locale.ROOT) }
                .thenBy { it.id }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onOpenEntry: (com.nexio.tv.domain.model.LibraryEntry) -> Unit,
    onOpenTorBoxFile: (TorBoxDirectPlayArgs) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val torBoxRefreshing by viewModel.torBoxRefreshing.collectAsState()
    val unifiedWatchlistRows by viewModel.unifiedWatchlistRows.collectAsState()
    val context = LocalContext.current

    val isUnifiedProvider = uiState.selectedProvider == LibraryProviderSelection.UNIFIED
    val isTorBoxProvider = uiState.selectedProvider == LibraryProviderSelection.TORBOX
    LaunchedEffect(uiState.selectedProvider) {
        if (isTorBoxProvider) viewModel.refreshTorBoxLibraryNow()
    }
    LaunchedEffect(viewModel) {
        viewModel.directPlayCommands.collect { command ->
            when (command) {
                is DirectPlayCommand.Resolving ->
                    android.widget.Toast.makeText(context, "Opening ${command.fileName}…", android.widget.Toast.LENGTH_SHORT).show()
                is DirectPlayCommand.Failed ->
                    android.widget.Toast.makeText(context, command.message, android.widget.Toast.LENGTH_LONG).show()
                is DirectPlayCommand.Navigate -> onOpenTorBoxFile(
                    TorBoxDirectPlayArgs(
                        url = command.url,
                        torrentId = command.torBoxTorrentId,
                        fileId = command.torBoxFileId,
                        fileName = command.fileName,
                        resumePositionMs = command.resumePositionMs,
                        deterministicAutoplay = command.deterministicAutoplay,
                    )
                )
            }
        }
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var expandedPicker by remember { mutableStateOf<String?>(null) }
    val primaryFocusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    var pendingPrimaryFocus by remember { mutableStateOf(true) }
    var lastFocusedPosterKey by rememberSaveable { mutableStateOf<String?>(null) }
    val unifiedWatchlistItems = remember(unifiedWatchlistRows) {
        unifiedWatchlistRows.map { row -> UnifiedWatchlistLibraryRailItem.entryFromRow(row) }
    }
    val effectiveTypeTabs = remember(isUnifiedProvider, unifiedWatchlistItems, uiState.availableTypeTabs) {
        if (isUnifiedProvider) buildLibraryTypeTabs(unifiedWatchlistItems) else uiState.availableTypeTabs
    }
    val effectiveSortOptions = remember(isUnifiedProvider, uiState.availableSortOptions) {
        if (isUnifiedProvider) LibrarySortOption.TraktOptions else uiState.availableSortOptions
    }
    val effectiveSelectedTypeTab = remember(effectiveTypeTabs, uiState.selectedTypeTab) {
        uiState.selectedTypeTab?.takeIf { selected ->
            effectiveTypeTabs.any { it.key == selected.key }
        } ?: LibraryTypeTab.All
    }
    val effectiveSelectedSortOption = remember(effectiveSortOptions, uiState.selectedSortOption) {
        uiState.selectedSortOption.takeIf { it in effectiveSortOptions }
            ?: LibrarySortOption.DEFAULT
    }
    val visibleItems = remember(
        isUnifiedProvider,
        unifiedWatchlistItems,
        uiState.visibleItems,
        effectiveSelectedTypeTab,
        effectiveSelectedSortOption
    ) {
        if (isUnifiedProvider) {
            unifiedWatchlistItems.visibleLibraryItems(
                selectedTypeTab = effectiveSelectedTypeTab,
                selectedSortOption = effectiveSelectedSortOption,
                selectedListType = LibraryListTab.Type.WATCHLIST
            )
        } else {
            uiState.visibleItems
        }
    }
    val gridItemKeys = remember(visibleItems) {
        visibleItems.map { "${it.type}:${it.id}" }
    }
    val visibleItemIndexByKey = remember(gridItemKeys) {
        gridItemKeys.withIndex().associate { (index, key) -> key to index }
    }
    val posterFocusRequesters = remember(gridItemKeys) {
        gridItemKeys.associateWith { FocusRequester() }
    }
    val firstVisiblePosterKey = gridItemKeys.firstOrNull()
    val posterCardStyle = PosterCardDefaults.Style
    val useReadableDebridListLayout = remember(uiState.selectedProvider) {
        usesReadableDebridProviderLayout(uiState.selectedProvider)
    }

    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) {
            pendingPrimaryFocus = true
        }
    }

    LaunchedEffect(uiState.isLoading, uiState.sourceMode, uiState.listTabs.size, uiState.selectedProvider) {
        if (!uiState.isLoading && pendingPrimaryFocus) {
            val restoreKey = lastFocusedPosterKey
            val restoreIndex = restoreKey?.let { visibleItemIndexByKey[it] }
            val restoreRequester = restoreKey?.let { posterFocusRequesters[it] }

            var focused = false
            if (restoreIndex != null && restoreRequester != null) {
                runCatching { gridState.scrollToItem(restoreIndex) }
                focused = runCatching { restoreRequester.requestFocus() }.isSuccess
                if (!focused) {
                    delay(16)
                    focused = runCatching { restoreRequester.requestFocus() }.isSuccess
                }
            }

            if (!focused) {
                focused = runCatching { primaryFocusRequester.requestFocus() }.isSuccess
            }
            if (!focused) {
                delay(16)
                runCatching { primaryFocusRequester.requestFocus() }
            }
            pendingPrimaryFocus = false
        }
    }

    LaunchedEffect(uiState.sortSelectionVersion, firstVisiblePosterKey) {
        if (uiState.sortSelectionVersion <= 0L) return@LaunchedEffect
        val targetKey = firstVisiblePosterKey ?: return@LaunchedEffect
        runCatching { gridState.scrollToItem(0) }
        var focused = false
        repeat(6) {
            focused = posterFocusRequesters[targetKey]
                ?.let { requester -> runCatching { requester.requestFocus() }.isSuccess }
                ?: false
            if (focused) return@LaunchedEffect
            delay(24)
        }
    }

    if (uiState.isLoading) {
        val loadingFocusRequester = remember { FocusRequester() }
        LaunchedEffect(uiState.isLoading) {
            loadingFocusRequester.requestFocus()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NexioColors.Background),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(loadingFocusRequester)
                    .focusable()
            )
            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LoadingIndicator()
                Text(
                    text = stringResource(R.string.library_syncing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexioColors.TextSecondary
                )
            }
        }
        return
    }

    val lastKeyRepeatTime = remember { longArrayOf(0L) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = posterCardStyle.width),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .background(NexioColors.Background)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount > 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastKeyRepeatTime[0] < KEY_REPEAT_THROTTLE_MS) {
                        return@onPreviewKeyEvent true
                    }
                    lastKeyRepeatTime[0] = now
                }
                false
            },
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.library_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (showBuiltInHeader) NexioColors.TextPrimary else Color.Transparent,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = uiState.selectedProvider.label.uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (showBuiltInHeader) NexioColors.TextTertiary else Color.Transparent,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LibrarySelectorsRow(
                providerOptions = uiState.availableProviders,
                selectedProvider = uiState.selectedProvider,
                listTabs = uiState.listTabs,
                typeTabs = effectiveTypeTabs,
                sortOptions = effectiveSortOptions,
                selectedListKey = uiState.selectedListKey,
                listSelectorLabel = uiState.listSelectorLabel,
                supportsLists = uiState.supportsLists,
                selectedTypeTab = effectiveSelectedTypeTab,
                selectedSortOption = effectiveSelectedSortOption,
                primaryFocusRequester = primaryFocusRequester,
                expandedPicker = expandedPicker,
                onExpandedChange = { picker, shouldExpand ->
                    expandedPicker = if (shouldExpand) picker else null
                },
                onSelectProvider = { provider ->
                    viewModel.onSelectProvider(provider)
                    expandedPicker = null
                },
                onSelectList = { key ->
                    viewModel.onSelectListTab(key)
                    expandedPicker = null
                },
                onSelectType = { type ->
                    viewModel.onSelectTypeTab(type)
                    expandedPicker = null
                },
                onSelectSort = { sort ->
                    viewModel.onSelectSortOption(sort)
                    expandedPicker = null
                }
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LibraryActionsRow(
                pending = uiState.pendingOperation,
                isSyncing = uiState.isSyncing,
                canManageLists = uiState.supportsListManagement &&
                    uiState.listTabs.any { it.type == LibraryListTab.Type.PERSONAL || it.isMutableStaticList },
                onManageLists = viewModel::onOpenManageLists,
                onRefresh = viewModel::onRefresh
            )
        }

        if (visibleItems.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val selectedTypeLabel = if (effectiveSelectedTypeTab.key == LibraryTypeTab.ALL_KEY) {
                    stringResource(R.string.library_type_items)
                } else {
                    localizedTypeLabel(effectiveSelectedTypeTab.key).lowercase()
                }
                val title = if (uiState.emptyReason == LibraryEmptyReason.UNIFIED_NEEDS_TRACKER_AUTH) {
                    "Connect a watchlist provider"
                } else {
                    when (uiState.sourceMode) {
                        LibrarySourceMode.LOCAL -> if (isUnifiedProvider) {
                            "No $selectedTypeLabel in watchlist"
                        } else {
                            stringResource(R.string.library_empty_local_title, selectedTypeLabel)
                        }
                        LibrarySourceMode.TRAKT -> stringResource(R.string.library_empty_trakt_title, selectedTypeLabel)
                        LibrarySourceMode.SIMKL -> stringResource(R.string.library_empty_simkl_title, selectedTypeLabel)
                        LibrarySourceMode.DEBRID -> stringResource(R.string.library_empty_debrid_title, selectedTypeLabel)
                    }
                }
                val subtitle = if (uiState.emptyReason == LibraryEmptyReason.UNIFIED_NEEDS_TRACKER_AUTH) {
                    "Authenticate Trakt, SIMKL, and/or MDBList to build your Unified Watchlist."
                } else {
                    when (uiState.sourceMode) {
                        LibrarySourceMode.LOCAL -> if (isUnifiedProvider) {
                            "Items from connected watchlist sources will appear here."
                        } else {
                            stringResource(R.string.library_empty_local_subtitle)
                        }
                        LibrarySourceMode.TRAKT -> stringResource(R.string.library_empty_trakt_subtitle)
                        LibrarySourceMode.SIMKL -> stringResource(R.string.library_empty_simkl_subtitle)
                        LibrarySourceMode.DEBRID -> stringResource(R.string.library_empty_debrid_subtitle)
                    }
                }
                EmptyScreenState(
                    title = title,
                    subtitle = subtitle,
                    icon = Icons.Default.BookmarkBorder
                )
            }
        }

        if (useReadableDebridListLayout) {
            items(
                items = visibleItems,
                key = { "${it.type}:${it.id}" },
                span = { GridItemSpan(maxLineSpan) }
            ) { item ->
                val focusKey = "${item.type}:${item.id}"
                DebridLibraryListRow(
                    item = item,
                    focusRequester = posterFocusRequesters[focusKey],
                    onFocused = {
                        lastFocusedPosterKey = focusKey
                    },
                    onClick = {
                        lastFocusedPosterKey = focusKey
                        if (isTorBoxProvider) viewModel.onTorBoxItemClick(item) else onOpenEntry(item)
                    }
                )
            }
        } else {
            items(visibleItems, key = { "${it.type}:${it.id}" }) { item ->
                val focusKey = "${item.type}:${item.id}"
                val cardData = remember(item) { LibraryRailItem.fromEntry(item) }
                GridContentCard(
                    item = cardData,
                    posterCardStyle = posterCardStyle,
                    focusRequester = posterFocusRequesters[focusKey],
                    showLabel = true,
                    onFocused = {
                        lastFocusedPosterKey = focusKey
                    },
                    onClick = {
                        lastFocusedPosterKey = focusKey
                        if (isTorBoxProvider) viewModel.onTorBoxItemClick(item) else onOpenEntry(item)
                    }
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(modifier = Modifier.height(8.dp)) }
    }

    if (uiState.showManageDialog && uiState.listTabs.any { tab ->
            when (uiState.listManagementMode) {
                LibraryListManagementMode.TRAKT_PERSONAL -> tab.type == LibraryListTab.Type.PERSONAL
                LibraryListManagementMode.MDBLIST_STATIC -> tab.isMutableStaticList
                LibraryListManagementMode.SIMKL_STATUS,
                LibraryListManagementMode.NONE -> false
            }
        }
    ) {
        ManageListsDialog(
            tabs = uiState.listTabs,
            managementMode = uiState.listManagementMode,
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
        val transientMessageBg = NexioColors.BackgroundElevated
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter
        ) {
            Text(
                text = transientMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = NexioColors.TextPrimary,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .drawWithCache {
                        val radius = 10.dp.toPx()
                        onDrawBehind {
                            drawRoundRect(
                                color = transientMessageBg,
                                cornerRadius = CornerRadius(radius, radius)
                            )
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibrarySelectorsRow(
    providerOptions: List<LibraryProviderOption>,
    selectedProvider: LibraryProviderSelection,
    listTabs: List<LibraryListTab>,
    typeTabs: List<LibraryTypeTab>,
    sortOptions: List<LibrarySortOption>,
    selectedListKey: String?,
    listSelectorLabel: String,
    supportsLists: Boolean,
    selectedTypeTab: LibraryTypeTab?,
    selectedSortOption: LibrarySortOption,
    primaryFocusRequester: FocusRequester,
    expandedPicker: String?,
    onExpandedChange: (String, Boolean) -> Unit,
    onSelectProvider: (LibraryProviderSelection) -> Unit,
    onSelectList: (String) -> Unit,
    onSelectType: (LibraryTypeTab) -> Unit,
    onSelectSort: (LibrarySortOption) -> Unit
) {
    val selectedTypeLabel = selectedTypeTab?.let { localizedTypeLabel(it.key) } ?: stringResource(R.string.library_type_all)
    val selectedSortLabel = selectedSortOption.label
    val listFocusRequester = remember { FocusRequester() }
    val typeFocusRequester = remember { FocusRequester() }
    val sortFocusRequester = remember { FocusRequester() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LibraryDropdownPicker(
            modifier = Modifier
                .weight(1f)
                .focusRequester(primaryFocusRequester),
            title = "Provider",
            value = selectedProvider.label,
            selectedValue = selectedProvider.name,
            expanded = expandedPicker == "provider",
            options = providerOptions.map { LibraryOption(it.label, it.provider.name) },
            onExpandedChange = { onExpandedChange("provider", it) },
            onSelect = { option ->
                LibraryProviderSelection.valueOf(option.value).let(onSelectProvider)
            }
        )

        LibraryDropdownPicker(
            modifier = Modifier
                .weight(1f)
                .focusRequester(listFocusRequester),
            title = stringResource(R.string.library_filter_list),
            value = listSelectorLabel,
            selectedValue = selectedListKey ?: "__na__",
            expanded = supportsLists && expandedPicker == "list",
            options = if (supportsLists) {
                listTabs.map { LibraryOption(it.title, it.key) }
            } else {
                listOf(LibraryOption("N/A", "__na__"))
            },
            onExpandedChange = { shouldExpand -> onExpandedChange("list", shouldExpand && supportsLists) },
            onSelect = { option ->
                if (supportsLists) onSelectList(option.value)
            },
            onMoveLeft = { primaryFocusRequester.requestFocus() }
        )

        LibraryDropdownPicker(
            modifier = Modifier
                .weight(1f)
                .focusRequester(typeFocusRequester),
            title = stringResource(R.string.library_filter_type),
            value = selectedTypeLabel,
            selectedValue = selectedTypeTab?.key,
            expanded = expandedPicker == "type",
            options = typeTabs.map { LibraryOption(localizedTypeLabel(it.key), it.key) },
            onExpandedChange = { onExpandedChange("type", it) },
            onSelect = { option ->
                typeTabs.firstOrNull { it.key == option.value }?.let(onSelectType)
            },
            onMoveLeft = { listFocusRequester.requestFocus() }
        )

        if (sortOptions.isNotEmpty()) {
            LibraryDropdownPicker(
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(sortFocusRequester),
                title = stringResource(R.string.library_filter_sort),
                value = selectedSortLabel,
                selectedValue = selectedSortOption.key,
                expanded = expandedPicker == "sort",
                options = sortOptions.map { LibraryOption(it.label, it.key) },
                onExpandedChange = { onExpandedChange("sort", it) },
                onSelect = { option ->
                    sortOptions.firstOrNull { it.key == option.value }?.let(onSelectSort)
                },
                onMoveLeft = { typeFocusRequester.requestFocus() }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryDropdownPicker(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    selectedValue: String?,
    expanded: Boolean,
    options: List<LibraryOption>,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (LibraryOption) -> Unit,
    onMoveLeft: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    var focusedOptionValue by remember(expanded) { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier.onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            val moveLeft = onMoveLeft
            if (
                native.action == AndroidKeyEvent.ACTION_DOWN &&
                native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT &&
                moveLeft != null
            ) {
                moveLeft.invoke()
                return@onPreviewKeyEvent true
            }
            false
        }
    ) {
        Card(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { anchorSize = it }
                .onFocusChanged { isFocused = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
            colors = CardDefaults.colors(
                containerColor = NexioColors.BackgroundCard,
                focusedContainerColor = NexioColors.FocusBackground
            ),
            border = CardDefaults.border(
                border = androidx.tv.material3.Border(
                    border = BorderStroke(1.dp, NexioColors.Border),
                    shape = RoundedCornerShape(14.dp)
                ),
                focusedBorder = androidx.tv.material3.Border(
                    border = BorderStroke(2.dp, NexioColors.FocusRing),
                    shape = RoundedCornerShape(14.dp)
                )
            ),
            scale = CardDefaults.scale(
                focusedScale = 1.0f,
                pressedScale = 1.0f
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = NexioColors.TextTertiary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = NexioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                        tint = if (isFocused) NexioColors.FocusRing else NexioColors.TextSecondary
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                focusedOptionValue = null
                onExpandedChange(false)
            },
            modifier = Modifier
                .width(with(LocalDensity.current) { anchorSize.width.toDp() })
                .heightIn(max = 320.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = NexioColors.BackgroundCard,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, NexioColors.Border)
        ) {
            options.forEach { option ->
                val isSelected = option.value == selectedValue
                val isOptionFocused = option.value == focusedOptionValue
                val itemTextColor = when {
                    isOptionFocused -> NexioColors.OnSecondary
                    else -> NexioColors.TextPrimary
                }
                val itemBackgroundColor = when {
                    isOptionFocused -> NexioColors.Secondary
                    isSelected -> NexioColors.FocusBackground
                    else -> Color.Transparent
                }

                DropdownMenuItem(
                    modifier = Modifier
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .background(
                            color = itemBackgroundColor,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .onFocusChanged { state ->
                            val hasFocus = state.isFocused || state.hasFocus
                            focusedOptionValue = when {
                                hasFocus -> option.value
                                focusedOptionValue == option.value -> null
                                else -> focusedOptionValue
                            }
                        },
                    text = {
                        Text(
                            text = option.label,
                            color = itemTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = { onSelect(option) },
                    colors = MenuDefaults.itemColors(
                        textColor = itemTextColor,
                        disabledTextColor = NexioColors.TextDisabled
                    )
                )
            }
        }
    }
}

private data class LibraryOption(
    val label: String,
    val value: String
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DebridLibraryListRow(
    item: com.nexio.tv.domain.model.LibraryEntry,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val presentation = debridRowPresentation(item)

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { state ->
                if (state.isFocused) onFocused()
            },
        shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
        colors = CardDefaults.colors(
            containerColor = NexioColors.BackgroundCard,
            focusedContainerColor = NexioColors.FocusBackground
        ),
        border = CardDefaults.border(
            border = androidx.tv.material3.Border(
                border = BorderStroke(1.dp, NexioColors.Border),
                shape = RoundedCornerShape(14.dp)
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(2.dp, NexioColors.FocusRing),
                shape = RoundedCornerShape(14.dp)
            )
        ),
        scale = CardDefaults.scale(
            focusedScale = 1.02f,
            pressedScale = 1.0f
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = presentation.title,
                style = MaterialTheme.typography.bodyMedium,
                color = NexioColors.TextSecondary,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryActionsRow(
    pending: Boolean,
    isSyncing: Boolean,
    canManageLists: Boolean,
    onManageLists: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (canManageLists) {
            Button(
                onClick = onManageLists,
                enabled = !pending && !isSyncing,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.library_manage_lists))
            }
        }
        Button(
            onClick = onRefresh,
            enabled = !pending && !isSyncing,
            colors = ButtonDefaults.colors(
                containerColor = NexioColors.BackgroundCard,
                contentColor = NexioColors.TextPrimary
            )
        ) {
            Text(if (isSyncing) "Syncing..." else "Sync")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManageListsDialog(
    tabs: List<LibraryListTab>,
    managementMode: LibraryListManagementMode,
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
    val manageableTabs = remember(tabs, managementMode) {
        tabs.filter { tab ->
            when (managementMode) {
                LibraryListManagementMode.TRAKT_PERSONAL -> tab.type == LibraryListTab.Type.PERSONAL
                LibraryListManagementMode.MDBLIST_STATIC -> tab.isMutableStaticList
                LibraryListManagementMode.SIMKL_STATUS,
                LibraryListManagementMode.NONE -> false
            }
        }
    }
    val canReorder = managementMode == LibraryListManagementMode.TRAKT_PERSONAL
    val firstFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(manageableTabs.size) {
        val target = if (manageableTabs.isNotEmpty()) firstFocusRequester else closeFocusRequester
        val focused = runCatching { target.requestFocus() }.isSuccess
        if (!focused) {
            delay(16)
            runCatching { target.requestFocus() }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        val manageDialogBg = NexioColors.BackgroundElevated
        Box(
            modifier = Modifier
                .width(620.dp)
                .drawWithCache {
                    val radius = 16.dp.toPx()
                    onDrawBehind {
                        drawRoundRect(
                            color = manageDialogBg,
                            cornerRadius = CornerRadius(radius, radius)
                        )
                    }
                }
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = when (managementMode) {
                        LibraryListManagementMode.MDBLIST_STATIC -> "Manage MDBList Lists"
                        else -> stringResource(R.string.library_manage_trakt_lists)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = NexioColors.TextPrimary
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFB6B6)
                    )
                }

                if (manageableTabs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.library_no_lists),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexioTheme.extendedColors.textSecondary
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(manageableTabs, key = { it.key }) { tab ->
                            val selected = tab.key == selectedKey
                            Button(
                                onClick = { onSelect(tab.key) },
                                enabled = !pending,
                                modifier = if (tab.key == manageableTabs.firstOrNull()?.key) {
                                    Modifier
                                        .fillMaxWidth()
                                        .focusRequester(firstFocusRequester)
                                } else {
                                    Modifier.fillMaxWidth()
                                },
                                colors = ButtonDefaults.colors(
                                    containerColor = if (selected) NexioColors.FocusBackground else NexioColors.BackgroundCard,
                                    contentColor = NexioColors.TextPrimary
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
                            containerColor = NexioColors.BackgroundCard,
                            contentColor = NexioColors.TextPrimary
                        )
                    ) { Text(stringResource(R.string.library_list_create)) }
                    Button(
                        onClick = onEdit,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = NexioColors.BackgroundCard,
                            contentColor = NexioColors.TextPrimary
                        )
                    ) { Text(stringResource(R.string.library_list_edit)) }
                    Button(
                        onClick = onMoveUp,
                        enabled = !pending && selectedKey != null && canReorder,
                        colors = ButtonDefaults.colors(
                            containerColor = NexioColors.BackgroundCard,
                            contentColor = NexioColors.TextPrimary
                        )
                    ) { Text(stringResource(R.string.library_list_move_up)) }
                    Button(
                        onClick = onMoveDown,
                        enabled = !pending && selectedKey != null && canReorder,
                        colors = ButtonDefaults.colors(
                            containerColor = NexioColors.BackgroundCard,
                            contentColor = NexioColors.TextPrimary
                        )
                    ) { Text(stringResource(R.string.library_list_move_down)) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onDelete,
                        enabled = !pending && selectedKey != null,
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFF4A2323),
                            contentColor = NexioColors.TextPrimary
                        )
                    ) { Text(stringResource(R.string.library_list_delete)) }
                    Button(
                        onClick = onDismiss,
                        enabled = !pending,
                        modifier = Modifier.focusRequester(closeFocusRequester),
                        colors = ButtonDefaults.colors(
                            containerColor = NexioColors.BackgroundCard,
                            contentColor = NexioColors.TextPrimary
                        )
                    ) { Text(stringResource(R.string.library_list_close)) }
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
    val nameFocusRequester = remember { FocusRequester() }
    val descriptionFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var nameEditing by remember { mutableStateOf(false) }
    var descriptionEditing by remember { mutableStateOf(false) }

    fun isSelectKey(keyCode: Int): Boolean {
        return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
    }

    LaunchedEffect(Unit) {
        nameFocusRequester.requestFocus()
    }

    NexioDialog(
        onDismiss = onCancel,
        title = if (state.mode == LibraryListEditorState.Mode.CREATE) "Create List" else "Edit List",
        width = 560.dp
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = state.name,
            onValueChange = onNameChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(nameFocusRequester)
                .onFocusChanged {
                    if (!it.isFocused) {
                        nameEditing = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && isSelectKey(native.keyCode)) {
                        nameEditing = true
                        descriptionEditing = false
                        keyboardController?.show()
                    }
                    false
                },
            enabled = !pending,
            readOnly = !nameEditing,
            singleLine = true,
            maxLines = 1,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    nameEditing = false
                    keyboardController?.hide()
                }
            ),
            label = { androidx.compose.material3.Text(stringResource(R.string.library_list_name_label)) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = NexioColors.TextPrimary,
                unfocusedTextColor = NexioColors.TextPrimary,
                focusedContainerColor = NexioColors.BackgroundCard,
                unfocusedContainerColor = NexioColors.BackgroundCard,
                focusedBorderColor = NexioColors.FocusRing,
                unfocusedBorderColor = NexioColors.Border,
                focusedLabelColor = NexioColors.TextSecondary,
                unfocusedLabelColor = NexioColors.TextTertiary,
                cursorColor = NexioColors.FocusRing
            )
        )

        androidx.compose.material3.OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(descriptionFocusRequester)
                .onFocusChanged {
                    if (!it.isFocused) {
                        descriptionEditing = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && isSelectKey(native.keyCode)) {
                        descriptionEditing = true
                        nameEditing = false
                        keyboardController?.show()
                    }
                    false
                },
            enabled = !pending,
            readOnly = !descriptionEditing,
            minLines = 3,
            maxLines = 5,
            label = { androidx.compose.material3.Text(stringResource(R.string.library_list_description_label)) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = NexioColors.TextPrimary,
                unfocusedTextColor = NexioColors.TextPrimary,
                focusedContainerColor = NexioColors.BackgroundCard,
                unfocusedContainerColor = NexioColors.BackgroundCard,
                focusedBorderColor = NexioColors.FocusRing,
                unfocusedBorderColor = NexioColors.Border,
                focusedLabelColor = NexioColors.TextSecondary,
                unfocusedLabelColor = NexioColors.TextTertiary,
                cursorColor = NexioColors.FocusRing
            )
        )

        Text(
            text = stringResource(R.string.library_list_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = NexioTheme.extendedColors.textSecondary
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(TraktListPrivacy.entries.toList(), key = { it.name }) { privacy ->
                val selected = privacy == state.privacy
                Button(
                    onClick = { onPrivacyChanged(privacy) },
                    enabled = !pending,
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NexioColors.FocusBackground else NexioColors.BackgroundCard,
                        contentColor = NexioColors.TextPrimary
                    )
                ) {
                    Text(privacy.apiValue.replaceFirstChar { it.uppercase() })
                }
            }
        }

        Button(
            onClick = onSave,
            enabled = !pending,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NexioColors.BackgroundCard,
                contentColor = NexioColors.TextPrimary
            )
        ) {
            Text(if (pending) "Saving..." else "Save")
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
    NexioDialog(
        onDismiss = onCancel,
        title = stringResource(R.string.library_delete_title),
        subtitle = stringResource(R.string.library_delete_subtitle),
        width = 420.dp
    ) {
        Button(
            onClick = onConfirm,
            enabled = !pending,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = Color(0xFF4A2323),
                contentColor = NexioColors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.library_list_delete))
        }
    }
}
