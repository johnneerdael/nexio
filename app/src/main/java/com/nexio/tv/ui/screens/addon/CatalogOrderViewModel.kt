package com.nexio.tv.ui.screens.addon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.sync.addonCatalogDisableKey
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.repository.MDBListDiscoveryService
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.TraktDiscoveryService
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CatalogOrderViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val traktDiscoveryService: TraktDiscoveryService,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val mdbListDiscoveryService: MDBListDiscoveryService,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogOrderUiState())
    val uiState: StateFlow<CatalogOrderUiState> = _uiState.asStateFlow()
    private var disabledKeysCache: Set<String> = emptySet()

    init {
        observeCatalogs()
    }

    fun moveUp(key: String) {
        moveCatalog(key, -1)
    }

    fun moveDown(key: String) {
        moveCatalog(key, 1)
    }

    fun toggleCatalogEnabled(disableKey: String?) {
        if (disableKey.isNullOrBlank()) return
        val updatedDisabled = disabledKeysCache.toMutableSet().apply {
            if (disableKey in this) remove(disableKey) else add(disableKey)
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(updatedDisabled.toList())
        }
    }

    private fun moveCatalog(key: String, direction: Int) {
        val currentKeys = _uiState.value.items.map { it.key }
        val currentIndex = currentKeys.indexOf(key)
        if (currentIndex == -1) return

        val newIndex = currentIndex + direction
        if (newIndex !in currentKeys.indices) return

        val reordered = currentKeys.toMutableList().apply {
            val item = removeAt(currentIndex)
            add(newIndex, item)
        }

        viewModelScope.launch {
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(reordered)
        }
    }

    private fun observeCatalogs() {
        viewModelScope.launch {
            val baseInputsFlow = combine(
                addonRepository.getInstalledAddons(),
                layoutPreferenceDataStore.homeCatalogOrderKeys,
                layoutPreferenceDataStore.disabledHomeCatalogKeys,
                traktDiscoveryService.observeSnapshot(),
                traktSettingsDataStore.catalogPreferences
            ) { addons, savedOrderKeys, disabledKeys, traktSnapshot, traktPrefs ->
                BaseCatalogOrderInputs(
                    addons = addons,
                    savedOrderKeys = savedOrderKeys,
                    disabledKeys = disabledKeys.toSet(),
                    traktSnapshot = traktSnapshot,
                    traktPrefs = traktPrefs
                )
            }

            combine(
                baseInputsFlow,
                mdbListDiscoveryService.observeSnapshot(),
                mdbListSettingsDataStore.catalogPreferences
            ) { base, mdbListSnapshot, mdbListPrefs ->
                buildOrderedCatalogItems(
                    addons = base.addons,
                    savedOrderKeys = base.savedOrderKeys,
                    disabledKeys = base.disabledKeys,
                    traktSnapshot = base.traktSnapshot,
                    traktPrefs = base.traktPrefs,
                    mdbListSnapshot = mdbListSnapshot,
                    mdbListPrefs = mdbListPrefs
                )
            }.collectLatest { orderedItems ->
                disabledKeysCache = orderedItems.filter { it.isDisabled }.map { it.disableKey }.toSet()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = orderedItems
                    )
                }
            }
        }
    }

    private fun buildOrderedCatalogItems(
        addons: List<Addon>,
        savedOrderKeys: List<String>,
        disabledKeys: Set<String>,
        traktSnapshot: TraktDiscoverySnapshot,
        traktPrefs: TraktCatalogPreferences,
        mdbListSnapshot: MDBListDiscoverySnapshot,
        mdbListPrefs: MDBListCatalogPreferences
    ): List<CatalogOrderItem> {
        val defaultEntries = buildDefaultCatalogEntries(addons)
            .plus(buildActiveTraktCatalogEntries(traktSnapshot, traktPrefs))
            .plus(buildActiveMdbListCatalogEntries(mdbListSnapshot, mdbListPrefs))
        val availableMap = defaultEntries.associateBy { it.key }
        val defaultOrderKeys = defaultEntries.map { it.key }

        val savedValid = savedOrderKeys
            .asSequence()
            .filter { it in availableMap }
            .distinct()
            .toList()

        val savedKeySet = savedValid.toSet()
        val missing = defaultOrderKeys.filterNot { it in savedKeySet }
        val effectiveOrder = savedValid + missing

        return effectiveOrder.mapIndexedNotNull { index, key ->
            val entry = availableMap[key] ?: return@mapIndexedNotNull null
            CatalogOrderItem(
                key = entry.key,
                disableKey = entry.disableKey,
                catalogName = entry.catalogName,
                addonName = entry.addonName,
                typeLabel = entry.typeLabel,
                isToggleable = entry.isToggleable,
                isDisabled = entry.disableKey in disabledKeys || entry.key in disabledKeys,
                canMoveUp = index > 0,
                canMoveDown = index < effectiveOrder.lastIndex
            )
        }
    }

    private fun buildDefaultCatalogEntries(addons: List<Addon>): List<CatalogOrderEntry> {
        val entries = mutableListOf<CatalogOrderEntry>()
        val seenKeys = mutableSetOf<String>()

        addons.forEach { addon ->
            addon.catalogs
                .filterNot { it.isSearchOnlyCatalog() }
                .forEach { catalog ->
                    val key = catalogKey(
                        addonId = addon.id,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    if (seenKeys.add(key)) {
                        entries.add(
                            CatalogOrderEntry(
                                key = key,
                                disableKey = disableKey(
                                    addonBaseUrl = addon.baseUrl,
                                    type = catalog.apiType,
                                    catalogId = catalog.id,
                                    catalogName = catalog.name
                                ),
                                catalogName = catalog.name,
                                addonName = addon.displayName,
                                typeLabel = catalog.apiType,
                                isToggleable = true
                            )
                        )
                    }
                }
        }

        return entries
    }

    private fun buildActiveTraktCatalogEntries(
        snapshot: TraktDiscoverySnapshot,
        prefs: TraktCatalogPreferences
    ): List<CatalogOrderEntry> {
        val entries = mutableListOf<CatalogOrderEntry>()

        fun addBuiltIn(catalogId: String, catalogName: String, typeLabel: String) {
            if (catalogId !in prefs.enabledCatalogs) return
            entries += CatalogOrderEntry(
                key = catalogId,
                disableKey = "",
                catalogName = catalogName,
                addonName = "Trakt",
                typeLabel = typeLabel,
                isToggleable = false
            )
        }

        addBuiltIn(TraktCatalogIds.UP_NEXT, "Trakt Up Next", "series")
        addBuiltIn(TraktCatalogIds.TRENDING_MOVIES, "Trakt Trending Movies", "movie")
        addBuiltIn(TraktCatalogIds.TRENDING_SHOWS, "Trakt Trending Shows", "series")
        addBuiltIn(TraktCatalogIds.POPULAR_MOVIES, "Trakt Popular Movies", "movie")
        addBuiltIn(TraktCatalogIds.POPULAR_SHOWS, "Trakt Popular Shows", "series")
        addBuiltIn(TraktCatalogIds.RECOMMENDED_MOVIES, "Trakt Recommended Movies", "movie")
        addBuiltIn(TraktCatalogIds.RECOMMENDED_SHOWS, "Trakt Recommended Shows", "series")
        addBuiltIn(TraktCatalogIds.CALENDAR, "Trakt Calendar (Next 7 Days)", "series")

        snapshot.popularLists
            .filter { it.key in prefs.selectedPopularListKeys }
            .forEach { list ->
            entries += CatalogOrderEntry(
                key = list.key,
                disableKey = "",
                catalogName = list.title,
                addonName = "Trakt",
                typeLabel = "custom list",
                isToggleable = false
            )
        }
        return entries
    }

    private fun buildActiveMdbListCatalogEntries(
        snapshot: MDBListDiscoverySnapshot,
        prefs: MDBListCatalogPreferences
    ): List<CatalogOrderEntry> {
        val availableKeys = buildSet {
            addAll(
                snapshot.personalLists
                    .filter { prefs.isPersonalListEnabled(it.key) }
                    .map { it.key }
            )
            addAll(
                snapshot.topLists
                    .filter { prefs.isTopListSelected(it.key) }
                    .map { it.key }
            )
        }
        if (availableKeys.isEmpty()) return emptyList()

        val orderedKeys = if (prefs.catalogOrder.isEmpty()) {
            availableKeys.toList()
        } else {
            prefs.catalogOrder.filter { it in availableKeys } + availableKeys.filterNot { it in prefs.catalogOrder }
        }
        val listsByKey = (snapshot.personalLists + snapshot.topLists).associateBy { it.key }

        return orderedKeys.mapNotNull { key ->
            val option = listsByKey[key] ?: return@mapNotNull null
            CatalogOrderEntry(
                key = option.key,
                disableKey = "",
                catalogName = option.title,
                addonName = "MDBList",
                typeLabel = if (option.isPersonal) "personal list" else "top list",
                isToggleable = false
            )
        }
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return "${addonId}_${type}_${catalogId}"
    }

    private fun disableKey(
        addonBaseUrl: String,
        type: String,
        catalogId: String,
        catalogName: String
    ): String {
        return addonCatalogDisableKey(addonBaseUrl, type, catalogId, catalogName)
    }

    private fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
        return extra.any { extra -> extra.name.equals("search", ignoreCase = true) && extra.isRequired }
    }
}

data class CatalogOrderUiState(
    val isLoading: Boolean = true,
    val items: List<CatalogOrderItem> = emptyList()
)

data class CatalogOrderItem(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String,
    val isToggleable: Boolean,
    val isDisabled: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean
)

private data class CatalogOrderEntry(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String,
    val isToggleable: Boolean
)

private data class BaseCatalogOrderInputs(
    val addons: List<Addon>,
    val savedOrderKeys: List<String>,
    val disabledKeys: Set<String>,
    val traktSnapshot: TraktDiscoverySnapshot,
    val traktPrefs: TraktCatalogPreferences
)
