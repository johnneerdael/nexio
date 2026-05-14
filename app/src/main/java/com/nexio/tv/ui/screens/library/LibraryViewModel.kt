package com.nexio.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.repository.UnifiedWatchlistResolvedDisplayProjector
import com.nexio.tv.data.repository.UnifiedWatchlistSurfacePublisher
import com.nexio.tv.data.repository.TorBoxDirectPlayHandler
import com.nexio.tv.data.repository.TorBoxResolvedPlayback
import com.nexio.tv.data.repository.TraktLibraryService
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryEmptyReason
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.LibraryListManagementMode
import com.nexio.tv.domain.model.LibraryProviderOption
import com.nexio.tv.domain.model.LibraryProviderSelection
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.TraktListPrivacy
import com.nexio.tv.domain.model.UnifiedWatchlistRowItem
import com.nexio.tv.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class LibraryTypeTab(
    val key: String,
    val label: String
) {
    companion object {
        const val ALL_KEY = "__all__"
        val All = LibraryTypeTab(key = ALL_KEY, label = "All")
    }
}

enum class LibrarySortOption(
    val key: String,
    val label: String
) {
    DEFAULT("default", "Trakt Order"),
    ADDED_DESC("added_desc", "Added \u2193"),
    ADDED_ASC("added_asc", "Added \u2191"),
    TITLE_ASC("title_asc", "Title A-Z"),
    TITLE_DESC("title_desc", "Title Z-A");

    companion object {
        val TraktOptions = listOf(DEFAULT, ADDED_DESC, ADDED_ASC, TITLE_ASC, TITLE_DESC)
        val LocalOptions = listOf(ADDED_DESC, ADDED_ASC, TITLE_ASC, TITLE_DESC)
    }
}

data class LibraryListEditorState(
    val mode: Mode,
    val listId: String? = null,
    val name: String = "",
    val description: String = "",
    val privacy: TraktListPrivacy = TraktListPrivacy.PRIVATE
) {
    enum class Mode {
        CREATE,
        EDIT
    }
}

data class LibraryUiState(
    val selectedProvider: LibraryProviderSelection = LibraryProviderSelection.UNIFIED,
    val availableProviders: List<LibraryProviderOption> = listOf(LibraryProviderOption(LibraryProviderSelection.UNIFIED)),
    val sourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val allItems: List<LibraryEntry> = emptyList(),
    val visibleItems: List<LibraryEntry> = emptyList(),
    val listTabs: List<LibraryListTab> = emptyList(),
    val listSelectorLabel: String = "N/A",
    val supportsLists: Boolean = false,
    val supportsListManagement: Boolean = false,
    val listManagementMode: LibraryListManagementMode = LibraryListManagementMode.NONE,
    val emptyReason: LibraryEmptyReason = LibraryEmptyReason.NONE,
    val availableTypeTabs: List<LibraryTypeTab> = emptyList(),
    val availableSortOptions: List<LibrarySortOption> = emptyList(),
    val selectedListKey: String? = null,
    val selectedTypeTab: LibraryTypeTab? = null,
    val selectedSortOption: LibrarySortOption = LibrarySortOption.DEFAULT,
    val sortSelectionVersion: Long = 0L,
    val posterCardWidthDp: Int = 126,
    val posterCardCornerRadiusDp: Int = 12,
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
    val transientMessage: String? = null,
    val showManageDialog: Boolean = false,
    val manageSelectedListKey: String? = null,
    val listEditorState: LibraryListEditorState? = null,
    val pendingOperation: Boolean = false
)

internal sealed interface DirectPlayCommand {
    data class Resolving(val fileName: String) : DirectPlayCommand
    data class Navigate(
        val url: String,
        val torBoxTorrentId: Int,
        val torBoxFileId: Int,
        val fileName: String,
        val resumePositionMs: Long,
        val deterministicAutoplay: Boolean = true,
    ) : DirectPlayCommand
    data class Failed(val message: String) : DirectPlayCommand
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val torBoxDirectPlayHandler: TorBoxDirectPlayHandler,
    private val unifiedWatchlistResolvedDisplayProjector: UnifiedWatchlistResolvedDisplayProjector,
    private val unifiedWatchlistSurfacePublisher: UnifiedWatchlistSurfacePublisher,
    private val profileManager: ProfileManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _directPlayCommands = MutableSharedFlow<DirectPlayCommand>(extraBufferCapacity = 4)
    internal val directPlayCommands: SharedFlow<DirectPlayCommand> = _directPlayCommands.asSharedFlow()

    private val _torBoxRefreshing = MutableStateFlow(false)
    internal val torBoxRefreshing: StateFlow<Boolean> = _torBoxRefreshing.asStateFlow()

    private val _unifiedWatchlistRows = MutableStateFlow<List<UnifiedWatchlistRowItem>>(emptyList())
    val unifiedWatchlistRows: StateFlow<List<UnifiedWatchlistRowItem>> = _unifiedWatchlistRows.asStateFlow()

    private val selectedProviderState = MutableStateFlow(LibraryProviderSelection.UNIFIED)
    private val selectedListKeyState = MutableStateFlow<String?>(null)

    private var messageClearJob: Job? = null
    private var initialTraktSyncRequested = false

    internal fun onTorBoxItemClick(entry: LibraryEntry) {
        val match = Regex("""^tb:torrent:(\d+):file:(\d+)$""").matchEntire(entry.id) ?: return
        val torrentId = match.groupValues[1].toInt()
        val fileId = match.groupValues[2].toInt()
        val fileName = entry.playbackFilename ?: entry.name
        viewModelScope.launch {
            _directPlayCommands.tryEmit(DirectPlayCommand.Resolving(fileName))
            when (val result = torBoxDirectPlayHandler.resolve(torrentId, fileId, fileName)) {
                is TorBoxResolvedPlayback.Resolved -> _directPlayCommands.tryEmit(
                    DirectPlayCommand.Navigate(
                        url = result.url,
                        torBoxTorrentId = result.torrentId,
                        torBoxFileId = result.fileId,
                        fileName = result.fileName,
                        resumePositionMs = result.resumePositionMs,
                    )
                )
                is TorBoxResolvedPlayback.Failed -> _directPlayCommands.tryEmit(
                    DirectPlayCommand.Failed(result.message)
                )
            }
        }
    }

    internal fun refreshTorBoxLibraryNow() {
        viewModelScope.launch {
            _torBoxRefreshing.value = true
            try {
                libraryRepository.refreshTorBoxNow()
            } finally {
                _torBoxRefreshing.value = false
            }
        }
    }

    init {
        observeLayoutPreferences()
        observeLibraryData()
        observeUnifiedWatchlistRows()
        observeDebridBootstrap()
        observeTraktBootstrap()
    }

    fun onSelectProvider(provider: LibraryProviderSelection) {
        selectedProviderState.value = provider
        selectedListKeyState.value = null
        _uiState.update { current ->
            if (current.selectedProvider == provider) {
                current
            } else {
                current.copy(
                    selectedProvider = provider,
                    selectedListKey = null,
                    manageSelectedListKey = null,
                    listEditorState = null,
                    showManageDialog = false
                )
            }
        }
    }

    fun onSelectTypeTab(tab: LibraryTypeTab) {
        _uiState.update { current ->
            val updated = current.copy(selectedTypeTab = tab)
            updated.withVisibleItems()
        }
    }

    fun onSelectListTab(listKey: String) {
        selectedListKeyState.value = listKey
        _uiState.update { current ->
            val updated = current.copy(selectedListKey = listKey)
            updated.withVisibleItems()
        }
    }

    fun onSelectSortOption(option: LibrarySortOption) {
        _uiState.update { current ->
            val nextVersion = if (current.selectedSortOption != option) {
                current.sortSelectionVersion + 1L
            } else {
                current.sortSelectionVersion
            }
            val updated = current.copy(
                selectedSortOption = option,
                sortSelectionVersion = nextVersion
            )
            updated.withVisibleItems()
        }
    }

    fun onRefresh() {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            val state = _uiState.value
            val provider = state.selectedProvider
            val startMessage = "Syncing ${provider.label} library..."
            val successMessage = "${provider.label} library synced"

            setTransientMessage(startMessage)
            runCatching {
                libraryRepository.refreshProviderNow(provider, state.selectedListKey)
                setTransientMessage(successMessage)
            }.onFailure { error ->
                setError(error.message ?: "Failed to refresh library")
            }
        }
    }

    fun onOpenManageLists() {
        _uiState.update { current ->
            if (current.listTabs.none { it.type == LibraryListTab.Type.PERSONAL }) {
                return@update current
            }
            current.copy(
                showManageDialog = true,
                manageSelectedListKey = current.manageSelectedListKey
                    ?: current.listTabs.firstOrNull { it.type == LibraryListTab.Type.PERSONAL }?.key
            )
        }
    }

    fun onCloseManageLists() {
        _uiState.update { current ->
            current.copy(
                showManageDialog = false,
                listEditorState = null,
                errorMessage = null
            )
        }
    }

    fun onSelectManageList(listKey: String) {
        _uiState.update { it.copy(manageSelectedListKey = listKey) }
    }

    fun onStartCreateList() {
        _uiState.update {
            it.copy(
                listEditorState = LibraryListEditorState(mode = LibraryListEditorState.Mode.CREATE),
                errorMessage = null
            )
        }
    }

    fun onStartEditList() {
        val selected = selectedManagePersonalList() ?: return
        _uiState.update {
            it.copy(
                listEditorState = LibraryListEditorState(
                    mode = LibraryListEditorState.Mode.EDIT,
                    listId = selected.traktListId?.toString(),
                    name = selected.title,
                    description = selected.description.orEmpty(),
                    privacy = selected.privacy ?: TraktListPrivacy.PRIVATE
                ),
                errorMessage = null
            )
        }
    }

    fun onUpdateEditorName(value: String) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(name = value))
        }
    }

    fun onUpdateEditorDescription(value: String) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(description = value))
        }
    }

    fun onUpdateEditorPrivacy(value: TraktListPrivacy) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(privacy = value))
        }
    }

    fun onCancelEditor() {
        _uiState.update { it.copy(listEditorState = null, errorMessage = null) }
    }

    fun onSubmitEditor() {
        val editor = _uiState.value.listEditorState ?: return
        val name = editor.name.trim()
        if (name.isBlank()) {
            setError("List name is required")
            return
        }
        if (_uiState.value.pendingOperation) return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                when (editor.mode) {
                    LibraryListEditorState.Mode.CREATE -> {
                        libraryRepository.createPersonalList(
                            name = name,
                            description = editor.description.trim().ifBlank { null },
                            privacy = editor.privacy
                        )
                        setTransientMessage("List created")
                    }
                    LibraryListEditorState.Mode.EDIT -> {
                        val listId = editor.listId
                            ?: throw IllegalStateException("Invalid list")
                        libraryRepository.updatePersonalList(
                            listId = listId,
                            name = name,
                            description = editor.description.trim().ifBlank { null },
                            privacy = editor.privacy
                        )
                        setTransientMessage("List updated")
                    }
                }
            }.onSuccess {
                _uiState.update { it.copy(listEditorState = null, pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Failed to save list")
            }
        }
    }

    fun onDeleteSelectedList() {
        val selected = selectedManagePersonalList() ?: return
        val listId = selected.traktListId?.toString() ?: return
        if (_uiState.value.pendingOperation) return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                libraryRepository.deletePersonalList(listId)
                setTransientMessage("List deleted")
            }.onSuccess {
                _uiState.update { it.copy(pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Failed to delete list")
            }
        }
    }

    fun onMoveSelectedListUp() {
        reorderSelectedList(moveUp = true)
    }

    fun onMoveSelectedListDown() {
        reorderSelectedList(moveUp = false)
    }

    fun onClearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    private fun observeLibraryData() {
        viewModelScope.launch {
            libraryRepository.availableProviders.collectLatest { providers ->
                val normalized = providers.ifEmpty {
                    listOf(LibraryProviderOption(LibraryProviderSelection.UNIFIED))
                }
                _uiState.update { current ->
                    val selectedAvailable = normalized.any { it.provider == current.selectedProvider }
                    current.copy(
                        availableProviders = normalized,
                        selectedProvider = if (selectedAvailable) current.selectedProvider else LibraryProviderSelection.UNIFIED
                    )
                }
                if (normalized.none { it.provider == selectedProviderState.value }) {
                    selectedProviderState.value = LibraryProviderSelection.UNIFIED
                    selectedListKeyState.value = null
                }
            }
        }
        viewModelScope.launch {
            combine(
                selectedProviderState,
                selectedListKeyState
            ) { provider, selectedListKey ->
                provider to selectedListKey
            }.distinctUntilChanged().collectLatest { (provider, selectedListKey) ->
                combine(
                    libraryRepository.observeProviderSnapshot(provider, selectedListKey),
                    libraryRepository.isSyncing,
                    libraryRepository.hasProviderCache
                ) { snapshot, isSyncing, hasProviderCache ->
                    DataBundle(
                        provider = snapshot.provider,
                        sourceMode = snapshot.sourceMode,
                        isSyncing = isSyncing,
                        hasProviderCache = hasProviderCache,
                        items = snapshot.items,
                        listTabs = snapshot.listTabs,
                        selectedListKey = snapshot.selectedListKey,
                        supportsLists = snapshot.supportsLists,
                        supportsListManagement = snapshot.supportsListManagement,
                        listManagementMode = snapshot.listManagementMode,
                        emptyReason = snapshot.emptyReason,
                        listSelectorLabel = snapshot.listSelectorLabel
                    )
                }.collectLatest { bundle ->
                    applyDataBundle(bundle)
                }
            }
        }
    }

    private fun applyDataBundle(bundle: DataBundle) {
        _uiState.update { current ->
            val listTabs = bundle.listTabs
            val items = bundle.items
            val nextSelectedList = bundle.selectedListKey?.takeIf { key -> listTabs.any { it.key == key } }

            if (selectedListKeyState.value != nextSelectedList) {
                selectedListKeyState.value = nextSelectedList
            }

            val nextManageSelected = current.manageSelectedListKey
                ?.takeIf { key ->
                    listTabs.any { tab ->
                        tab.key == key && tab.type == LibraryListTab.Type.PERSONAL
                    }
                }
                ?: listTabs.firstOrNull { it.type == LibraryListTab.Type.PERSONAL }?.key

            val selectedListTab = listTabs.firstOrNull { it.key == nextSelectedList }
            val itemsForTypeTabs = if (bundle.supportsLists && !nextSelectedList.isNullOrBlank()) {
                items.filter { it.listKeys.contains(nextSelectedList) }
            } else {
                items
            }
            val typeTabs = buildTypeTabs(itemsForTypeTabs)
            val nextSelectedType = current.selectedTypeTab
                ?.takeIf { selected -> typeTabs.any { it.key == selected.key } }
                ?: LibraryTypeTab.All
            val sortOptions = if (
                selectedListTab?.type == LibraryListTab.Type.WATCHLIST ||
                selectedListTab?.type == LibraryListTab.Type.PERSONAL
            ) {
                LibrarySortOption.TraktOptions
            } else {
                LibrarySortOption.LocalOptions
            }
            val nextSelectedSort = current.selectedSortOption
                .takeIf { it in sortOptions }
                ?: if (
                    selectedListTab?.type == LibraryListTab.Type.WATCHLIST ||
                    selectedListTab?.type == LibraryListTab.Type.PERSONAL
                ) {
                    LibrarySortOption.DEFAULT
                } else {
                    LibrarySortOption.ADDED_DESC
                }

            val updated = current.copy(
                selectedProvider = bundle.provider,
                sourceMode = bundle.sourceMode,
                allItems = items,
                listTabs = listTabs,
                listSelectorLabel = bundle.listSelectorLabel,
                supportsLists = bundle.supportsLists,
                supportsListManagement = bundle.supportsListManagement,
                listManagementMode = bundle.listManagementMode,
                emptyReason = bundle.emptyReason,
                availableTypeTabs = typeTabs,
                availableSortOptions = sortOptions,
                selectedTypeTab = nextSelectedType,
                selectedListKey = nextSelectedList,
                selectedSortOption = nextSelectedSort,
                manageSelectedListKey = nextManageSelected,
                isSyncing = bundle.sourceMode != LibrarySourceMode.LOCAL && bundle.isSyncing,
                isLoading = (bundle.sourceMode == LibrarySourceMode.TRAKT || bundle.sourceMode == LibrarySourceMode.SIMKL) &&
                    !bundle.hasProviderCache &&
                    current.errorMessage == null
            )
            updated.withVisibleItems()
        }
    }

    private fun observeUnifiedWatchlistRows() {
        viewModelScope.launch {
            profileManager.activeProfileSession.collectLatest { profileSession ->
                libraryRepository.unifiedWatchlistMemberships.collectLatest { memberships ->
                    unifiedWatchlistSurfacePublisher.publish(
                        profileSession = profileSession,
                        memberships = memberships
                    )
                }
            }
        }
        viewModelScope.launch {
            profileManager.activeProfileId.collectLatest { profileId ->
                unifiedWatchlistResolvedDisplayProjector
                    .observeRows(profileId, libraryRepository.unifiedWatchlistMemberships)
                    .collectLatest { rows ->
                        _unifiedWatchlistRows.value = rows
                    }
            }
        }
    }

    private fun observeLayoutPreferences() {
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp
            ) { widthDp, cornerRadiusDp ->
                widthDp to cornerRadiusDp
            }.collectLatest { (widthDp, cornerRadiusDp) ->
                _uiState.update { current ->
                    if (current.posterCardWidthDp == widthDp &&
                        current.posterCardCornerRadiusDp == cornerRadiusDp
                    ) {
                        current
                    } else {
                        current.copy(
                            posterCardWidthDp = widthDp,
                            posterCardCornerRadiusDp = cornerRadiusDp
                        )
                    }
                }
            }
        }
    }

    private data class DataBundle(
        val provider: LibraryProviderSelection = LibraryProviderSelection.UNIFIED,
        val sourceMode: LibrarySourceMode,
        val isSyncing: Boolean,
        val hasProviderCache: Boolean,
        val items: List<LibraryEntry>,
        val listTabs: List<LibraryListTab>,
        val selectedListKey: String? = null,
        val supportsLists: Boolean = false,
        val supportsListManagement: Boolean = false,
        val listManagementMode: LibraryListManagementMode = LibraryListManagementMode.NONE,
        val emptyReason: LibraryEmptyReason = LibraryEmptyReason.NONE,
        val listSelectorLabel: String = "N/A"
    )

    private fun observeDebridBootstrap() {
        viewModelScope.launch {
            runCatching {
                libraryRepository.refreshDebridNow()
            }.onFailure { error ->
                setError(error.message ?: providerSyncFailureMessage(LibrarySourceMode.DEBRID))
            }
        }
    }

    private fun observeTraktBootstrap() {
        viewModelScope.launch {
            combine(
                libraryRepository.sourceMode,
                libraryRepository.hasProviderCache
            ) { sourceMode, hasProviderCache ->
                sourceMode to hasProviderCache
            }.collectLatest { (sourceMode, hasProviderCache) ->
                when {
                    sourceMode != LibrarySourceMode.TRAKT && sourceMode != LibrarySourceMode.SIMKL -> {
                        initialTraktSyncRequested = false
                    }

                    hasProviderCache -> {
                        initialTraktSyncRequested = true
                    }

                    !initialTraktSyncRequested -> {
                        initialTraktSyncRequested = true
                        runCatching {
                            if (sourceMode == LibrarySourceMode.TRAKT || sourceMode == LibrarySourceMode.SIMKL) {
                                libraryRepository.refreshProviderNow()
                            } else {
                                libraryRepository.refreshNow()
                            }
                        }.onFailure { error ->
                            setError(error.message ?: providerSyncFailureMessage(sourceMode))
                        }
                    }
                }
            }
        }
    }

    private fun providerSyncFailureMessage(sourceMode: LibrarySourceMode): String {
        return when (sourceMode) {
            LibrarySourceMode.TRAKT -> "Failed to sync Trakt library"
            LibrarySourceMode.SIMKL -> "Failed to sync SIMKL library"
            LibrarySourceMode.DEBRID -> "Failed to sync debrid libraries"
            LibrarySourceMode.LOCAL -> "Failed to sync library"
        }
    }

    private fun reorderSelectedList(moveUp: Boolean) {
        val state = _uiState.value
        if (state.pendingOperation) return

        val personalTabs = state.listTabs.filter { it.type == LibraryListTab.Type.PERSONAL }
        val selectedKey = state.manageSelectedListKey ?: return
        val selectedIndex = personalTabs.indexOfFirst { it.key == selectedKey }
        if (selectedIndex < 0) return

        val targetIndex = if (moveUp) selectedIndex - 1 else selectedIndex + 1
        if (targetIndex !in personalTabs.indices) return

        val reordered = personalTabs.toMutableList().apply {
            add(targetIndex, removeAt(selectedIndex))
        }
        val orderedIds = reordered.mapNotNull { tab ->
            tab.traktListId?.toString() ?: tab.key.removePrefix(TraktLibraryService.PERSONAL_KEY_PREFIX)
        }

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                libraryRepository.reorderPersonalLists(orderedIds)
                setTransientMessage("List order updated")
            }.onSuccess {
                _uiState.update { it.copy(pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Failed to reorder lists")
            }
        }
    }

    private fun selectedManagePersonalList(): LibraryListTab? {
        val state = _uiState.value
        val selectedKey = state.manageSelectedListKey ?: return null
        return state.listTabs.firstOrNull { it.key == selectedKey && it.type == LibraryListTab.Type.PERSONAL }
    }

    private fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message, transientMessage = message, isLoading = false) }
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            delay(2800)
            _uiState.update { it.copy(transientMessage = null) }
        }
    }

    private fun setTransientMessage(message: String) {
        _uiState.update { it.copy(transientMessage = message, errorMessage = null) }
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            delay(2200)
            _uiState.update { it.copy(transientMessage = null) }
        }
    }

    private fun buildTypeTabs(items: List<LibraryEntry>): List<LibraryTypeTab> {
        val byKey = linkedMapOf<String, LibraryTypeTab>()
        items.forEach { entry ->
            val key = entry.type.trim().ifBlank { "unknown" }.lowercase(Locale.ROOT)
            if (byKey.containsKey(key)) return@forEach
            byKey[key] = LibraryTypeTab(
                key = key,
                label = prettifyTypeLabel(key)
            )
        }
        return listOf(LibraryTypeTab.All) + byKey.values
    }

    private fun prettifyTypeLabel(key: String): String {
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

    private fun LibraryUiState.withVisibleItems(): LibraryUiState {
        val selectedTypeKey = selectedTypeTab?.key
        val typeFiltered = allItems.filter { entry ->
            selectedTypeKey == null ||
                selectedTypeKey == LibraryTypeTab.ALL_KEY ||
                entry.type.trim().lowercase(Locale.ROOT) == selectedTypeKey
        }

        val listFiltered = if (!selectedListKey.isNullOrBlank()) {
            typeFiltered.filter { entry -> entry.listKeys.contains(selectedListKey) }
        } else {
            typeFiltered
        }

        val sorted = when (selectedSortOption) {
            LibrarySortOption.DEFAULT -> if (
                listTabs.firstOrNull { it.key == selectedListKey }?.type == LibraryListTab.Type.WATCHLIST ||
                listTabs.firstOrNull { it.key == selectedListKey }?.type == LibraryListTab.Type.PERSONAL
            ) {
                listFiltered.sortedWith(
                    compareBy<LibraryEntry> { it.traktRank ?: Int.MAX_VALUE }
                        .thenByDescending { it.listedAt }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                        .thenBy { it.id }
                )
            } else {
                listFiltered
            }
            LibrarySortOption.ADDED_DESC -> listFiltered.sortedWith(
                compareByDescending<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.ADDED_ASC -> listFiltered.sortedWith(
                compareBy<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.TITLE_ASC -> listFiltered.sortedWith(
                compareBy<LibraryEntry> { it.name.ifBlank { it.id }.lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
            LibrarySortOption.TITLE_DESC -> listFiltered.sortedWith(
                compareByDescending<LibraryEntry> { it.name.ifBlank { it.id }.lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
        }

        return copy(visibleItems = sorted)
    }
}
