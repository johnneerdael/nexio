package com.nexio.tv.core.sync

import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.data.local.AnimeSkipSettingsDataStore
import com.nexio.tv.data.local.KitsuCatalogSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.OmdbSettingsDataStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.local.SimklSettingsDataStore
import com.nexio.tv.data.local.SubtitleTranslationSettingsDataStore
import com.nexio.tv.data.local.TheIntroDbSettingsDataStore
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.local.normalizeSubtitleTranslationSettings
import com.nexio.tv.data.remote.supabase.AccountAddonPayload
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.CatalogSyncSettings
import com.nexio.tv.data.remote.supabase.HomeCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.IntegrationSettings
import com.nexio.tv.data.remote.supabase.MDBListCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.FormatterSyncSettings
import com.nexio.tv.data.remote.supabase.PlaybackConfigSyncSettings
import com.nexio.tv.data.remote.supabase.PosterRatingsSyncSettings
import com.nexio.tv.data.remote.supabase.SimklCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.StreamSelectionConfigSyncSettings
import com.nexio.tv.data.remote.supabase.SubtitleTranslationSyncSettings
import com.nexio.tv.data.remote.supabase.TraktCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.TraktPinnedListOptionSync
import com.nexio.tv.data.remote.supabase.MDBListPinnedListOptionSync
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkTypeKey
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.ui.screens.home.order.HomeRailKey
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStore
import com.nexio.tv.ui.screens.home.order.RailFamily
import com.nexio.tv.ui.screens.home.order.RailOrderMutationSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 9

internal fun observeAccountConfigSyncChanges(
    heroCatalogSelections: Flow<Unit>,
    homeCatalogOrderKeys: Flow<Unit>,
    disabledHomeCatalogKeys: Flow<Unit>,
    tmdbSettings: Flow<Unit>,
    tvdbSettings: Flow<Unit>,
    mdbListSettings: Flow<Unit>,
    mdbListCatalogPreferences: Flow<Unit>,
    omdbSettings: Flow<Unit>,
    theIntroDbSettings: Flow<Unit>,
    animeSkipEnabled: Flow<Unit>,
    subtitleTranslationSettings: Flow<Unit>,
    wyzieSettings: Flow<Unit>,
    posterRatingsSettings: Flow<Unit>,
    premiumizeSettings: Flow<Unit>,
    premiumizeAccountState: Flow<Unit>,
    torBoxSettings: Flow<Unit>,
    torBoxAccountState: Flow<Unit>,
    easyDebridSettings: Flow<Unit>,
    easyDebridAccountState: Flow<Unit>,
    realDebridState: Flow<Unit>,
    kitsuAuthState: Flow<Unit>,
    traktAuthState: Flow<Unit>,
    traktCatalogPreferences: Flow<Unit>,
    simklCatalogPreferences: Flow<Unit>,
    simklAuthState: Flow<Unit>,
    playerSettings: Flow<Unit>
): Flow<Unit> {
    return merge(
        heroCatalogSelections,
        homeCatalogOrderKeys,
        disabledHomeCatalogKeys,
        tmdbSettings,
        tvdbSettings,
        mdbListSettings,
        mdbListCatalogPreferences,
        omdbSettings,
        theIntroDbSettings,
        animeSkipEnabled,
        subtitleTranslationSettings,
        wyzieSettings,
        posterRatingsSettings,
        premiumizeSettings,
        premiumizeAccountState,
        torBoxSettings,
        torBoxAccountState,
        easyDebridSettings,
        easyDebridAccountState,
        realDebridState,
        kitsuAuthState,
        traktAuthState,
        traktCatalogPreferences,
        simklCatalogPreferences,
        simklAuthState,
        playerSettings
    )
}

internal fun observeAccountConfigSyncChangedPaths(
    heroCatalogSelections: Flow<Unit>,
    homeCatalogOrderKeys: Flow<Unit>,
    disabledHomeCatalogKeys: Flow<Unit>,
    tmdbSettings: Flow<Unit>,
    tvdbSettings: Flow<Unit>,
    mdbListSettings: Flow<Unit>,
    mdbListCatalogPreferences: Flow<Unit>,
    omdbSettings: Flow<Unit>,
    theIntroDbSettings: Flow<Unit>,
    animeSkipEnabled: Flow<Unit>,
    subtitleTranslationSettings: Flow<Unit>,
    wyzieSettings: Flow<Unit>,
    posterRatingsSettings: Flow<Unit>,
    premiumizeSettings: Flow<Unit>,
    premiumizeAccountState: Flow<Unit>,
    torBoxSettings: Flow<Unit>,
    torBoxAccountState: Flow<Unit>,
    easyDebridSettings: Flow<Unit>,
    easyDebridAccountState: Flow<Unit>,
    realDebridState: Flow<Unit>,
    kitsuAuthState: Flow<Unit>,
    traktAuthState: Flow<Unit>,
    traktCatalogPreferences: Flow<Unit>,
    simklCatalogPreferences: Flow<Unit>,
    simklAuthState: Flow<Unit>,
    playerSettings: Flow<Unit>
): Flow<String> {
    return merge(
        heroCatalogSelections.map { "catalogs.home.heroCatalogKeys" },
        homeCatalogOrderKeys.map { "catalogs.home.homeCatalogOrderKeys" },
        disabledHomeCatalogKeys.map { "catalogs.home.disabledHomeCatalogKeys" },
        tmdbSettings.map { "integrations.tmdb" },
        tvdbSettings.map { "integrations.tvdb" },
        mdbListSettings.map { "integrations.mdblist" },
        mdbListCatalogPreferences.map { "catalogs.mdblist" },
        omdbSettings.map { "integrations.omdb.enabled" },
        theIntroDbSettings.map { "integrations.theIntroDb" },
        animeSkipEnabled.map { "integrations.animeSkip.enabled" },
        subtitleTranslationSettings.map { "integrations.subtitleTranslation" },
        wyzieSettings.map { "integrations.wyzie" },
        posterRatingsSettings.map { "integrations.posterRatings" },
        premiumizeSettings.map { "integrations.debrid.premiumize" },
        premiumizeAccountState.map { "integrations.debrid.premiumize" },
        torBoxSettings.map { "integrations.debrid.torBox" },
        torBoxAccountState.map { "integrations.debrid.torBox" },
        easyDebridSettings.map { "integrations.debrid.easyDebrid" },
        easyDebridAccountState.map { "integrations.debrid.easyDebrid" },
        realDebridState.map { "integrations.debrid.realDebrid" },
        kitsuAuthState.map { "integrations.kitsuAuth" },
        traktAuthState.map { "integrations.traktAuth" },
        traktCatalogPreferences.map { "catalogs.trakt" },
        simklCatalogPreferences.map { "catalogs.simkl" },
        simklAuthState.map { "integrations.simklAuth" },
        playerSettings.transform {
            emit("playback.streamSelection.trackingProvider")
            emit("formatter")
        }
    )
}

internal class AccountConfigStartupPushGate {
    private val lock = Any()
    private var sessionUserId: String? = null
    private var remotePulledUserId: String? = null

    fun onSessionUserChanged(userId: String?): Boolean = synchronized(lock) {
        val changed = sessionUserId != userId
        if (changed) {
            sessionUserId = userId
            remotePulledUserId = null
        }
        changed
    }

    fun markRemotePullSucceeded(userId: String) {
        synchronized(lock) {
            sessionUserId = userId
            remotePulledUserId = userId
        }
    }

    fun canPush(userId: String?): Boolean = synchronized(lock) {
        userId != null && userId == sessionUserId && userId == remotePulledUserId
    }
}

internal fun buildAccountConfigSyncPayload(
    integrations: IntegrationSettings,
    heroCatalogKeys: List<String>,
    homeCatalogOrderKeys: List<String>,
    disabledHomeCatalogKeys: List<String>,
    traktCatalogEnabledSet: List<String>,
    traktCatalogOrder: List<String>,
    traktSelectedPopularListKeys: List<String>,
    traktPinnedListOptions: List<TraktPinnedListOptionSync> = emptyList(),
    simklCatalogEnabledSet: List<String>,
    simklCatalogOrder: List<String>,
    mdbListHiddenPersonalListKeys: List<String>,
    mdbListSelectedTopListKeys: List<String>,
    mdbListPinnedTopListOptions: List<MDBListPinnedListOptionSync> = emptyList(),
    mdbListCatalogOrder: List<String>,
    trackingProvider: TrackingProvider,
    formatter: FormatterSyncSettings
): AccountConfigSyncPayload {
    return AccountConfigSyncPayload(
        schemaVersion = ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION,
        integrations = integrations.copy(
            gemini = integrations.gemini.copy(
                enabled = integrations.subtitleTranslation.enabled &&
                    integrations.subtitleTranslation.provider.equals("GEMINI", ignoreCase = true)
            )
        ),
        catalogs = CatalogSyncSettings(
            home = HomeCatalogSyncSettings(
                heroCatalogKeys = heroCatalogKeys,
                homeCatalogOrderKeys = homeCatalogOrderKeys,
                disabledHomeCatalogKeys = disabledHomeCatalogKeys
            ),
            trakt = TraktCatalogSyncSettings(
                catalogEnabledSet = traktCatalogEnabledSet,
                catalogOrder = traktCatalogOrder,
                selectedPopularListKeys = traktSelectedPopularListKeys,
                pinnedListOptions = traktPinnedListOptions
            ),
            simkl = SimklCatalogSyncSettings(
                catalogEnabledSet = simklCatalogEnabledSet,
                catalogOrder = simklCatalogOrder
            ),
            mdblist = MDBListCatalogSyncSettings(
                hiddenPersonalListKeys = mdbListHiddenPersonalListKeys,
                selectedTopListKeys = mdbListSelectedTopListKeys,
                pinnedTopListOptions = mdbListPinnedTopListOptions,
                catalogOrder = mdbListCatalogOrder
            )
        ),
        playback = PlaybackConfigSyncSettings(
            streamSelection = StreamSelectionConfigSyncSettings(
                trackingProvider = trackingProvider.name
            )
        ),
        formatter = formatter
    )
}

internal fun buildAccountConfigSyncPushParams(payload: AccountConfigSyncPayload): JsonObject {
    return buildJsonObject {
        put(
            "p_settings_payload",
            Json.encodeToJsonElement(AccountConfigSyncPayload.serializer(), payload)
        )
        put("p_source", "app")
        put("p_contract_version", ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION)
    }
}

internal fun buildAccountConfigSyncPushParamsV7(
    payload: AccountConfigSyncPayload,
    baseRevision: Long,
    changedPaths: List<String>
): JsonObject {
    return buildJsonObject {
        put(
            "p_settings_payload",
            Json.encodeToJsonElement(AccountConfigSyncPayload.serializer(), payload)
        )
        put("p_base_revision", baseRevision)
        put(
            "p_changed_paths",
            Json.encodeToJsonElement(
                ListSerializer(String.serializer()),
                changedPaths.distinct().filter(String::isNotBlank)
            )
        )
        put("p_source", "app")
    }
}

internal fun buildAccountConfigSyncPullParams(): JsonObject {
    return buildJsonObject {
        put("p_contract_version", ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION)
    }
}

internal suspend fun buildRemoteAddonInstallConfigs(
    addons: List<AccountAddonPayload>,
    resolveAddonUrl: suspend (AccountAddonPayload) -> Result<String>
): List<AddonPreferences.AddonInstallConfig> {
    return addons
        .sortedBy { it.sortOrder }
        .filter { it.enabled }
        .mapNotNull { addon ->
            resolveAddonUrl(addon).getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { url ->
                    AddonPreferences.AddonInstallConfig(
                        url = url,
                        parserPreset = runCatching {
                            enumValueOf<AddonParserPreset>(addon.parserPreset.trim().uppercase())
                        }.getOrDefault(AddonParserPreset.GENERIC),
                        isAnime = addon.isAnime
                    )
                }
        }
}

internal fun SubtitleTranslationSyncSettings.toDomainSettings(apiKey: String = ""): SubtitleTranslationSettings {
    return normalizeSubtitleTranslationSettings(
        enabled = enabled,
        providerName = provider,
        apiKey = apiKey,
        model = model,
        baseUrl = baseUrl
    )
}

internal suspend fun applyAccountConfigSyncSettings(
    settings: AccountConfigSyncPayload,
    layoutPreferenceDataStore: LayoutPreferenceDataStore,
    tmdbSettingsDataStore: TmdbSettingsDataStore,
    mdbListSettingsDataStore: MDBListSettingsDataStore,
    omdbSettingsDataStore: OmdbSettingsDataStore,
    theIntroDbSettingsDataStore: TheIntroDbSettingsDataStore,
    animeSkipSettingsDataStore: AnimeSkipSettingsDataStore,
    subtitleTranslationSettingsDataStore: SubtitleTranslationSettingsDataStore,
    posterRatingsSettingsDataStore: PosterRatingsSettingsDataStore,
    traktSettingsDataStore: TraktSettingsDataStore,
    simklSettingsDataStore: SimklSettingsDataStore,
    tmdbCatalogSettingsDataStore: TmdbCatalogSettingsDataStore,
    kitsuCatalogSettingsDataStore: KitsuCatalogSettingsDataStore,
    homeRailOrderStore: HomeRailOrderStore,
    playerSettingsDataStore: PlayerSettingsDataStore
) {
    // Null catalog sections / null inner fields = absent in payload, leave target unchanged.
    // Empty list ([]) = present and intentionally empty, apply as cleared.
    applyCatalogsSection(
        payload = settings,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        traktSettingsDataStore = traktSettingsDataStore,
        simklSettingsDataStore = simklSettingsDataStore,
        mdbListSettingsDataStore = mdbListSettingsDataStore,
        tmdbCatalogSettingsDataStore = tmdbCatalogSettingsDataStore,
        kitsuCatalogSettingsDataStore = kitsuCatalogSettingsDataStore,
        homeRailOrderStore = homeRailOrderStore
    )

    tmdbSettingsDataStore.setEnabled(true)
    tmdbSettingsDataStore.setUseArtwork(settings.integrations.tmdb.useArtwork)
    tmdbSettingsDataStore.setUseBasicInfo(settings.integrations.tmdb.useBasicInfo)
    tmdbSettingsDataStore.setUseDetails(settings.integrations.tmdb.useDetails)
    tmdbSettingsDataStore.setUseCredits(settings.integrations.tmdb.useCredits)
    tmdbSettingsDataStore.setUseProductions(settings.integrations.tmdb.useProductions)
    tmdbSettingsDataStore.setUseNetworks(settings.integrations.tmdb.useNetworks)
    tmdbSettingsDataStore.setUseEpisodes(settings.integrations.tmdb.useEpisodes)
    tmdbSettingsDataStore.setUseMoreLikeThis(settings.integrations.tmdb.useMoreLikeThis)
    tmdbSettingsDataStore.setUseCollections(settings.integrations.tmdb.useCollections)

    mdbListSettingsDataStore.setEnabled(settings.integrations.mdblist.enabled)
    mdbListSettingsDataStore.setShowTrakt(settings.integrations.mdblist.showTrakt)
    mdbListSettingsDataStore.setShowImdb(settings.integrations.mdblist.showImdb)
    mdbListSettingsDataStore.setShowTmdb(settings.integrations.mdblist.showTmdb)
    mdbListSettingsDataStore.setShowLetterboxd(settings.integrations.mdblist.showLetterboxd)
    mdbListSettingsDataStore.setShowTomatoes(settings.integrations.mdblist.showTomatoes)
    mdbListSettingsDataStore.setShowAudience(settings.integrations.mdblist.showAudience)
    mdbListSettingsDataStore.setShowMetacritic(settings.integrations.mdblist.showMetacritic)

    omdbSettingsDataStore.setEnabled(settings.integrations.omdb.enabled)

    theIntroDbSettingsDataStore.setEnabled(true)
    theIntroDbSettingsDataStore.setShowIntroButton(settings.integrations.theIntroDb.showIntroButton)
    theIntroDbSettingsDataStore.setShowRecapButton(settings.integrations.theIntroDb.showRecapButton)
    theIntroDbSettingsDataStore.setShowCreditsButton(settings.integrations.theIntroDb.showCreditsButton)
    theIntroDbSettingsDataStore.setShowPreviewButton(settings.integrations.theIntroDb.showPreviewButton)

    animeSkipSettingsDataStore.setEnabled(settings.integrations.animeSkip.enabled)

    val remoteTranslation = settings.integrations.subtitleTranslation
    subtitleTranslationSettingsDataStore.saveSyncedPublicSettings(
        enabled = remoteTranslation.enabled,
        provider = remoteTranslation.toDomainSettings().provider,
        model = remoteTranslation.model,
        baseUrl = remoteTranslation.baseUrl
    )

    applyPosterRatingsProviderSelection(
        settings = settings.integrations.posterRatings,
        posterRatingsSettingsDataStore = posterRatingsSettingsDataStore
    )

    playerSettingsDataStore.setTrackingProvider(
        runCatching { TrackingProvider.valueOf(settings.playback.streamSelection.trackingProvider) }
            .getOrDefault(TrackingProvider.TRAKT)
    )

    playerSettingsDataStore.setSyncedFormatterEnabled(settings.formatter.enabled)
    playerSettingsDataStore.setSyncedFormatterSelectedTemplateId(settings.formatter.selectedTemplateId)
    playerSettingsDataStore.setSyncedFormatterCustomTemplate(
        label = settings.formatter.customTemplate?.label,
        nameTemplate = settings.formatter.customTemplate?.nameTemplate,
        descriptionTemplate = settings.formatter.customTemplate?.descriptionTemplate,
        badgeRowTemplate = settings.formatter.customTemplate?.badgeRowTemplate
    )
}

internal suspend fun applyPosterRatingsProviderSelection(
    settings: PosterRatingsSyncSettings,
    posterRatingsSettingsDataStore: PosterRatingsSettingsDataStore
) {
    val provider = when {
        settings.rpdbEnabled -> ArtworkProviderChoiceKey.RPDB
        settings.topPostersEnabled -> ArtworkProviderChoiceKey.TOP_POSTERS
        else -> ArtworkProviderChoiceKey.DEFAULT
    }

    posterRatingsSettingsDataStore.setProviderSelection(ArtworkTypeKey.POSTER, provider)
}

/**
 * Apply the catalog-section subtree of an [AccountConfigSyncPayload] to the
 * relevant on-device DataStores. Presence semantics:
 *
 *  - Null sub-section (e.g. `payload.catalogs.home == null`) = absent in
 *    payload, leave target state unchanged.
 *  - Null inner field (e.g. `home.homeCatalogOrderKeys == null`) = absent in
 *    payload, leave target state unchanged.
 *  - Empty list (e.g. `home.homeCatalogOrderKeys == emptyList()`) = present
 *    and intentionally cleared, apply as a write of the empty value.
 *
 * Multi-field setters (`Trakt.setCatalogPreferences`,
 * `Simkl.setCatalogPreferences`, `MDBList.setCatalogPreferences`) are atomic;
 * we only invoke them when every inner field that the setter consumes is
 * present (non-null). If any of the bundled inner fields is null, the whole
 * write is skipped — null-vs-empty fidelity is preserved per call rather than
 * per field.
 */
internal suspend fun applyCatalogsSection(
    payload: AccountConfigSyncPayload,
    layoutPreferenceDataStore: LayoutPreferenceDataStore,
    traktSettingsDataStore: TraktSettingsDataStore,
    simklSettingsDataStore: SimklSettingsDataStore,
    mdbListSettingsDataStore: MDBListSettingsDataStore,
    tmdbCatalogSettingsDataStore: TmdbCatalogSettingsDataStore,
    kitsuCatalogSettingsDataStore: KitsuCatalogSettingsDataStore,
    homeRailOrderStore: HomeRailOrderStore
) {
    val catalogs = payload.catalogs

    catalogs.home?.let { home ->
        home.heroCatalogKeys?.let { layoutPreferenceDataStore.setHeroCatalogKeys(it) }
        home.homeCatalogOrderKeys?.let { layoutPreferenceDataStore.setHomeCatalogOrderKeys(it) }
        home.disabledHomeCatalogKeys?.let { layoutPreferenceDataStore.setDisabledHomeCatalogKeys(it) }
    }

    catalogs.trakt?.let { trakt ->
        val enabled = trakt.catalogEnabledSet
        val order = trakt.catalogOrder
        val popular = trakt.selectedPopularListKeys
        if (enabled != null && order != null && popular != null) {
            traktSettingsDataStore.setCatalogPreferences(
                enabledCatalogs = enabled.toSet(),
                catalogOrder = order,
                selectedPopularListKeys = popular.toSet()
            )
            homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.TRAKT,
                providerOrder = order.map(::HomeRailKey),
                source = RailOrderMutationSource.ACCOUNT_SYNC,
            )
        }
    }

    catalogs.simkl?.let { simkl ->
        val enabled = simkl.catalogEnabledSet
        val order = simkl.catalogOrder
        if (enabled != null && order != null) {
            simklSettingsDataStore.setCatalogPreferences(
                enabledCatalogs = enabled.toSet(),
                catalogOrder = order
            )
            homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.SIMKL,
                providerOrder = order.map(::HomeRailKey),
                source = RailOrderMutationSource.ACCOUNT_SYNC,
            )
        }
    }

    catalogs.mdblist?.let { mdblist ->
        val hidden = mdblist.hiddenPersonalListKeys
        val selected = mdblist.selectedTopListKeys
        val order = mdblist.catalogOrder
        if (hidden != null && selected != null && order != null) {
            mdbListSettingsDataStore.setCatalogPreferences(
                hiddenPersonalListKeys = hidden.toSet(),
                selectedTopListKeys = selected.toSet(),
                catalogOrder = order
            )
            homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.MDBLIST,
                providerOrder = order.map(::HomeRailKey),
                source = RailOrderMutationSource.ACCOUNT_SYNC,
            )
        }
    }

    catalogs.tmdb?.let { tmdb ->
        tmdb.catalogEnabledSet?.let { enabled ->
            tmdbCatalogSettingsDataStore.setEnabledCatalogs(enabled.toSet())
        }
        tmdb.catalogOrder?.let { order ->
            tmdbCatalogSettingsDataStore.setCatalogOrder(order)
            homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.TMDB,
                providerOrder = order.map(::HomeRailKey),
                source = RailOrderMutationSource.ACCOUNT_SYNC,
            )
        }
    }

    catalogs.kitsu?.let { kitsu ->
        kitsu.catalogEnabledSet?.let { enabled ->
            kitsuCatalogSettingsDataStore.setEnabledCatalogs(enabled.toSet())
        }
        kitsu.catalogOrder?.let { order ->
            kitsuCatalogSettingsDataStore.setCatalogOrder(order)
            homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.KITSU,
                providerOrder = order.map(::HomeRailKey),
                source = RailOrderMutationSource.ACCOUNT_SYNC,
            )
        }
    }
}
