package com.nexio.tv.ui.screens.addon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.sync.CatalogPriorityHydrationNotifier
import com.nexio.tv.core.recommendations.AndroidTvFeedCatalogService
import com.nexio.tv.core.recommendations.AndroidTvFeedOption
import com.nexio.tv.core.sync.addonCatalogDisableKey
import com.nexio.tv.core.sync.isAddonCatalogDisabled
import com.nexio.tv.data.local.AndroidTvRecommendationsDataStore
import com.nexio.tv.data.local.KitsuCatalogIds
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.local.KitsuCatalogSettingsDataStore
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.SimklCatalogIds
import com.nexio.tv.data.local.SimklCatalogPreferences
import com.nexio.tv.data.local.SimklSettingsDataStore
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.repository.MDBListDiscoveryService
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.data.repository.TraktDiscoveryService
import com.nexio.tv.data.repository.kitsuCatalogTitle
import com.nexio.tv.data.repository.tmdbCatalogTitle
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
    private val simklSettingsDataStore: SimklSettingsDataStore,
    private val mdbListDiscoveryService: MDBListDiscoveryService,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val kitsuCatalogSettingsDataStore: KitsuCatalogSettingsDataStore,
    private val tmdbCatalogSettingsDataStore: TmdbCatalogSettingsDataStore,
    private val androidTvRecommendationsDataStore: AndroidTvRecommendationsDataStore,
    private val androidTvFeedCatalogService: AndroidTvFeedCatalogService,
    private val catalogPriorityHydrationNotifier: CatalogPriorityHydrationNotifier
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogOrderUiState())
    val uiState: StateFlow<CatalogOrderUiState> = _uiState.asStateFlow()
    private var disabledKeysCache: Set<String> = emptySet()
    private var savedOrderKeysCache: List<String> = emptyList()
    private var kitsuCatalogKeysCache: Set<String> = emptySet()
    private var disabledKitsuCatalogKeysCache: Set<String> = emptySet()
    private var tmdbCatalogKeysCache: Set<String> = emptySet()
    private var disabledTmdbCatalogKeysCache: Set<String> = emptySet()

    init {
        observeCatalogs()
        observeAndroidTvRecommendations()
    }

    fun moveUp(key: String) {
        moveCatalog(key, -1)
    }

    fun moveDown(key: String) {
        moveCatalog(key, 1)
    }

    fun toggleCatalogEnabled(disableKey: String?) {
        if (disableKey.isNullOrBlank()) return
        if (disableKey in kitsuCatalogKeysCache) {
            val currentlyEnabled = disableKey !in disabledKitsuCatalogKeysCache
            viewModelScope.launch {
                kitsuCatalogSettingsDataStore.setCatalogEnabled(disableKey, !currentlyEnabled)
                if (!currentlyEnabled) {
                    catalogPriorityHydrationNotifier.notifyPriorityHydrationRequired()
                }
            }
            return
        }
        if (disableKey in tmdbCatalogKeysCache) {
            val currentlyEnabled = disableKey !in disabledTmdbCatalogKeysCache
            viewModelScope.launch {
                tmdbCatalogSettingsDataStore.setCatalogEnabled(disableKey, !currentlyEnabled)
                if (!currentlyEnabled) {
                    catalogPriorityHydrationNotifier.notifyPriorityHydrationRequired()
                }
            }
            return
        }
        val wasDisabled = disableKey in disabledKeysCache
        val updatedDisabled = disabledKeysCache.toMutableSet().apply {
            if (disableKey in this) remove(disableKey) else add(disableKey)
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(updatedDisabled.toList())
            if (wasDisabled) {
                catalogPriorityHydrationNotifier.notifyPriorityHydrationRequired()
            }
        }
    }

    private fun moveCatalog(key: String, direction: Int) {
        val visibleKeys = _uiState.value.items.map { it.key }
        val visibleKeySet = visibleKeys.toSet()
        val currentKeys = buildEffectiveVisibleOrder(
            savedOrderKeys = savedOrderKeysCache,
            availableKeys = visibleKeys
        )
        val currentIndex = currentKeys.indexOf(key)
        if (currentIndex == -1) return

        val newIndex = currentIndex + direction
        if (newIndex !in currentKeys.indices) return

        val reordered = currentKeys.toMutableList().apply {
            val item = removeAt(currentIndex)
            add(newIndex, item)
        }

        viewModelScope.launch {
            val hiddenKeys = savedOrderKeysCache
                .asSequence()
                .mapNotNull { rawKey -> resolveOrderedKey(rawKey, visibleKeySet) }
                .distinct()
                .filter { it !in reordered }
                .toList()
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(reordered + hiddenKeys)
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
                    traktPrefs = traktPrefs,
                    simklPrefs = SimklCatalogPreferences()
                )
            }

            combine(
                baseInputsFlow,
                simklSettingsDataStore.catalogPreferences,
                mdbListDiscoveryService.observeSnapshot(),
                mdbListSettingsDataStore.catalogPreferences,
                kitsuCatalogSettingsDataStore.catalogPreferences
            ) { base, simklPrefs, mdbListSnapshot, mdbListPrefs, kitsuPrefs ->
                ExtendedCatalogOrderInputs(
                    base = base,
                    simklPrefs = simklPrefs,
                    mdbListSnapshot = mdbListSnapshot,
                    mdbListPrefs = mdbListPrefs,
                    kitsuPrefs = kitsuPrefs
                )
            }.combine(tmdbCatalogSettingsDataStore.catalogPreferences) { inputs, tmdbPrefs ->
                inputs.base.savedOrderKeys to buildOrderedCatalogItems(
                    addons = inputs.base.addons,
                    savedOrderKeys = inputs.base.savedOrderKeys,
                    disabledKeys = inputs.base.disabledKeys,
                    traktSnapshot = inputs.base.traktSnapshot,
                    traktPrefs = inputs.base.traktPrefs,
                    simklPrefs = inputs.simklPrefs,
                    mdbListSnapshot = inputs.mdbListSnapshot,
                    mdbListPrefs = inputs.mdbListPrefs,
                    kitsuPrefs = inputs.kitsuPrefs,
                    tmdbPrefs = tmdbPrefs
                )
            }.collectLatest { (savedOrderKeys, orderedItems) ->
                savedOrderKeysCache = savedOrderKeys
                disabledKeysCache = orderedItems
                    .filter { it.isDisabled && it.key !in kitsuCatalogKeysCache && it.key !in tmdbCatalogKeysCache }
                    .map { it.disableKey }
                    .toSet()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = orderedItems
                    )
                }
            }
        }
    }

    private fun observeAndroidTvRecommendations() {
        viewModelScope.launch {
            combine(
                androidTvRecommendationsDataStore.preferences,
                androidTvFeedCatalogService.observeFeedOptions()
            ) { prefs, options ->
                prefs to options
            }.collectLatest { (prefs, options) ->
                _uiState.update {
                    it.copy(
                        androidTvChannelsEnabled = prefs.enabled,
                        androidTvSelectedFeedKeys = prefs.selectedFeedKeys,
                        androidTvFeedOptions = options
                    )
                }
            }
        }
    }

    fun setAndroidTvChannelsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            androidTvRecommendationsDataStore.setEnabled(enabled)
        }
    }

    fun toggleAndroidTvFeed(key: String) {
        viewModelScope.launch {
            androidTvRecommendationsDataStore.toggleSelectedFeedKey(key)
        }
    }

    private fun buildOrderedCatalogItems(
        addons: List<Addon>,
        savedOrderKeys: List<String>,
        disabledKeys: Set<String>,
        traktSnapshot: TraktDiscoverySnapshot,
        traktPrefs: TraktCatalogPreferences,
        simklPrefs: SimklCatalogPreferences,
        mdbListSnapshot: MDBListDiscoverySnapshot,
        mdbListPrefs: MDBListCatalogPreferences,
        kitsuPrefs: KitsuCatalogPreferences,
        tmdbPrefs: TmdbCatalogPreferences
    ): List<CatalogOrderItem> {
        val kitsuEntries = buildAllKitsuCatalogEntries(kitsuPrefs)
        kitsuCatalogKeysCache = kitsuEntries.map { it.key }.toSet()
        disabledKitsuCatalogKeysCache = kitsuEntries.filter { it.isDisabled }.map { it.key }.toSet()
        val tmdbEntries = buildAllTmdbCatalogEntries(tmdbPrefs)
        tmdbCatalogKeysCache = tmdbEntries.map { it.key }.toSet()
        disabledTmdbCatalogKeysCache = tmdbEntries.filter { it.isDisabled }.map { it.key }.toSet()
        val defaultEntries = buildDefaultCatalogEntries(addons, disabledKeys)
            .plus(buildActiveTraktCatalogEntries(traktSnapshot, traktPrefs, disabledKeys))
            .plus(buildActiveSimklCatalogEntries(simklPrefs, disabledKeys))
            .plus(buildActiveMdbListCatalogEntries(mdbListSnapshot, mdbListPrefs, disabledKeys))
            .plus(kitsuEntries)
            .plus(tmdbEntries)
        val availableMap = defaultEntries.associateBy { it.key }
        val defaultOrderKeys = defaultEntries.map { it.key }
        val savedValid = savedOrderKeys
            .asSequence()
            .mapNotNull { rawKey -> resolveOrderedKey(rawKey, availableMap.keys) }
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
                isDisabled = entry.isDisabled || entry.disableKey in disabledKeys || entry.key in disabledKeys,
                canMoveUp = index > 0,
                canMoveDown = index < effectiveOrder.lastIndex
            )
        }
    }

    private fun buildDefaultCatalogEntries(
        addons: List<Addon>,
        disabledKeys: Set<String>
    ): List<CatalogOrderEntry> {
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
                                isToggleable = true,
                                isDisabled = isAddonCatalogDisabled(
                                    disabledKeys = disabledKeys,
                                    addonBaseUrl = addon.baseUrl,
                                    addonId = addon.id,
                                    type = catalog.apiType,
                                    catalogId = catalog.id,
                                    catalogName = catalog.name
                                )
                            )
                        )
                    }
                }
        }

        return entries
    }

    private fun buildActiveTraktCatalogEntries(
        snapshot: TraktDiscoverySnapshot,
        prefs: TraktCatalogPreferences,
        disabledKeys: Set<String>
    ): List<CatalogOrderEntry> {
        val entries = mutableListOf<CatalogOrderEntry>()

        fun addBuiltIn(catalogId: String, catalogName: String, typeLabel: String) {
            if (catalogId !in prefs.enabledCatalogs) return
            entries += CatalogOrderEntry(
                key = catalogId,
                disableKey = catalogId,
                catalogName = catalogName,
                addonName = "Trakt",
                typeLabel = typeLabel,
                isToggleable = true,
                isDisabled = catalogId in disabledKeys
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

        val popularByKey = snapshot.popularLists.associateBy { it.key }
        val customNameByKey = snapshot.customListCatalogs
            .groupBy { it.key }
            .mapValues { (_, catalogs) ->
                catalogs.firstOrNull()?.catalogName
                    ?.removeSuffix(" (Movies)")
                    ?.removeSuffix(" (Shows)")
                    ?: catalogs.firstOrNull()?.key.orEmpty()
            }

        prefs.selectedPopularListKeys.forEach { key ->
            val list = popularByKey[key]
            entries += CatalogOrderEntry(
                key = key,
                disableKey = key,
                catalogName = list?.title ?: customNameByKey[key] ?: key,
                addonName = "Trakt",
                typeLabel = "custom list",
                isToggleable = true,
                isDisabled = key in disabledKeys
            )
        }
        return entries
    }

    private fun buildActiveMdbListCatalogEntries(
        snapshot: MDBListDiscoverySnapshot,
        prefs: MDBListCatalogPreferences,
        disabledKeys: Set<String>
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
            addAll(
                snapshot.customListCatalogs
                    .filter { it.key in prefs.selectedTopListKeys }
                    .map { it.key }
            )
        }
        if (availableKeys.isEmpty()) return emptyList()

        val orderedKeys = if (prefs.catalogOrder.isEmpty()) {
            availableKeys.toList()
        } else {
            prefs.catalogOrder.filter { it in availableKeys } + availableKeys.filterNot { it in prefs.catalogOrder }
        }
        val customNameByKey = snapshot.customListCatalogs
            .groupBy { it.key }
            .mapValues { (_, catalogs) ->
                catalogs.firstOrNull()?.catalogName
                    ?.removeSuffix(" (Movies)")
                    ?.removeSuffix(" (Shows)")
                    ?: catalogs.firstOrNull()?.key.orEmpty()
            }
        val listsByKey = (snapshot.personalLists + snapshot.topLists).associateBy { it.key }

        return orderedKeys.mapNotNull { key ->
            val option = listsByKey[key]
            CatalogOrderEntry(
                key = option?.key ?: key,
                disableKey = option?.key ?: key,
                catalogName = option?.title ?: customNameByKey[key] ?: key,
                addonName = "MDBList",
                typeLabel = if (option?.isPersonal == true) "personal list" else "top list",
                isToggleable = true,
                isDisabled = key in disabledKeys
            )
        }
    }

    private fun buildAllTmdbCatalogEntries(
        prefs: TmdbCatalogPreferences
    ): List<CatalogOrderEntry> {
        val sanitized = prefs.sanitized()
        val orderedIds = sanitized.catalogOrder
            .filter { it in TmdbCatalogIds.BUILT_IN_ORDER } +
            TmdbCatalogIds.BUILT_IN_ORDER.filterNot { it in sanitized.catalogOrder }
        return orderedIds.distinct().map { catalogId ->
            val isMovieCatalog = catalogId.endsWith("_movies")
            CatalogOrderEntry(
                key = catalogId,
                disableKey = catalogId,
                catalogName = tmdbCatalogTitle(catalogId) ?: catalogId,
                addonName = "TMDB",
                typeLabel = if (isMovieCatalog) "movie" else "series",
                isToggleable = true,
                isDisabled = catalogId !in sanitized.enabledCatalogs
            )
        }
    }

    private fun buildAllKitsuCatalogEntries(
        prefs: KitsuCatalogPreferences
    ): List<CatalogOrderEntry> {
        val sanitized = prefs.sanitized()
        val orderedIds = sanitized.catalogOrder
            .filter { it in KitsuCatalogIds.BUILT_IN_ORDER } +
            KitsuCatalogIds.BUILT_IN_ORDER.filterNot { it in sanitized.catalogOrder }
        return orderedIds.distinct().map { catalogId ->
            CatalogOrderEntry(
                key = catalogId,
                disableKey = catalogId,
                catalogName = kitsuCatalogTitle(catalogId) ?: catalogId,
                addonName = "Kitsu",
                typeLabel = "anime",
                isToggleable = true,
                isDisabled = catalogId !in sanitized.enabledCatalogs
            )
        }
    }

    private fun buildActiveSimklCatalogEntries(
        prefs: SimklCatalogPreferences,
        disabledKeys: Set<String>
    ): List<CatalogOrderEntry> {
        fun labelFor(catalogId: String): Pair<String, String> = when (catalogId) {
            SimklCatalogIds.TV_TRENDING_TODAY -> "SIMKL Trending TV (Today)" to "series"
            SimklCatalogIds.TV_TRENDING_WEEK -> "SIMKL Trending TV (Week)" to "series"
            SimklCatalogIds.TV_TRENDING_MONTH -> "SIMKL Trending TV (Month)" to "series"
            SimklCatalogIds.ANIME_TRENDING_TODAY -> "SIMKL Trending Anime (Today)" to "series"
            SimklCatalogIds.ANIME_TRENDING_WEEK -> "SIMKL Trending Anime (Week)" to "series"
            SimklCatalogIds.ANIME_TRENDING_MONTH -> "SIMKL Trending Anime (Month)" to "series"
            SimklCatalogIds.MOVIE_TRENDING_TODAY -> "SIMKL Trending Movies (Today)" to "movie"
            SimklCatalogIds.MOVIE_TRENDING_WEEK -> "SIMKL Trending Movies (Week)" to "movie"
            SimklCatalogIds.MOVIE_TRENDING_MONTH -> "SIMKL Trending Movies (Month)" to "movie"
            SimklCatalogIds.DVD_RELEASES -> "SIMKL Popular DVD Releases" to "movie"
            else -> catalogId to "catalog"
        }

        val orderedKeys = prefs.catalogOrder.filter { it in prefs.enabledCatalogs } +
            prefs.enabledCatalogs.filterNot { it in prefs.catalogOrder }

        return orderedKeys.distinct().map { catalogId ->
            val (catalogName, typeLabel) = labelFor(catalogId)
            CatalogOrderEntry(
                key = catalogId,
                disableKey = catalogId,
                catalogName = catalogName,
                addonName = "SIMKL",
                typeLabel = typeLabel,
                isToggleable = true,
                isDisabled = catalogId in disabledKeys
            )
        }
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return "${addonId}_${type}_${catalogId}"
    }

    private fun buildEffectiveVisibleOrder(
        savedOrderKeys: List<String>,
        availableKeys: List<String>
    ): List<String> {
        val availableSet = availableKeys.toSet()
        val savedVisible = savedOrderKeys
            .asSequence()
            .mapNotNull { rawKey -> resolveOrderedKey(rawKey, availableSet) }
            .distinct()
            .toList()
        return savedVisible + availableKeys.filterNot { it in savedVisible }
    }

    private fun resolveOrderedKey(rawKey: String, availableKeys: Set<String>): String? {
        if (rawKey in availableKeys) {
            return rawKey
        }

        val canonical = canonicalSyntheticCatalogKey(rawKey)
        if (canonical.isBlank()) {
            return null
        }

        return availableKeys.firstOrNull { canonicalSyntheticCatalogKey(it) == canonical }
    }

    private fun canonicalSyntheticCatalogKey(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""
        return when {
            trimmed.startsWith("personal:", ignoreCase = true) ||
                trimmed.startsWith("top:", ignoreCase = true) -> {
                val prefix = trimmed.substringBefore(':').lowercase()
                val payload = trimmed.substringAfter(':', "")
                val listId = payload.substringAfterLast('/').trim().lowercase()
                if (listId.isBlank()) trimmed.lowercase() else "$prefix:$listId"
            }

            else -> trimmed
        }
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
    val items: List<CatalogOrderItem> = emptyList(),
    val androidTvChannelsEnabled: Boolean = false,
    val androidTvSelectedFeedKeys: List<String> = emptyList(),
    val androidTvFeedOptions: List<AndroidTvFeedOption> = emptyList()
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
    val isToggleable: Boolean,
    val isDisabled: Boolean
)

private data class BaseCatalogOrderInputs(
    val addons: List<Addon>,
    val savedOrderKeys: List<String>,
    val disabledKeys: Set<String>,
    val traktSnapshot: TraktDiscoverySnapshot,
    val traktPrefs: TraktCatalogPreferences,
    val simklPrefs: SimklCatalogPreferences
)

private data class ExtendedCatalogOrderInputs(
    val base: BaseCatalogOrderInputs,
    val simklPrefs: SimklCatalogPreferences,
    val mdbListSnapshot: MDBListDiscoverySnapshot,
    val mdbListPrefs: MDBListCatalogPreferences,
    val kitsuPrefs: KitsuCatalogPreferences
)
