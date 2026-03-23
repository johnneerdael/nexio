package com.nexio.tv.core.sync

import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.data.local.AnimeSkipSettingsDataStore
import com.nexio.tv.data.local.GeminiSettingsDataStore
import com.nexio.tv.data.local.ImdbSettings
import com.nexio.tv.data.local.ImdbSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.OmdbSettingsDataStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.remote.supabase.AccountAddonPayload
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.CatalogSyncSettings
import com.nexio.tv.data.remote.supabase.HomeCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.IntegrationSettings
import com.nexio.tv.data.remote.supabase.ImdbSyncSettings
import com.nexio.tv.data.remote.supabase.MDBListCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.FormatterSyncSettings
import com.nexio.tv.data.remote.supabase.TraktCatalogSyncSettings
import com.nexio.tv.domain.model.AddonParserPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 3

internal fun observeAccountConfigSyncChanges(
    heroCatalogSelections: Flow<Unit>,
    homeCatalogOrderKeys: Flow<Unit>,
    disabledHomeCatalogKeys: Flow<Unit>,
    tmdbSettings: Flow<Unit>,
    mdbListSettings: Flow<Unit>,
    mdbListCatalogPreferences: Flow<Unit>,
    omdbSettings: Flow<Unit>,
    animeSkipEnabled: Flow<Unit>,
    animeSkipClientId: Flow<Unit>,
    geminiSettings: Flow<Unit>,
    posterRatingsSettings: Flow<Unit>,
    premiumizeSettings: Flow<Unit>,
    premiumizeAccountState: Flow<Unit>,
    realDebridState: Flow<Unit>,
    traktAuthState: Flow<Unit>,
    traktCatalogPreferences: Flow<Unit>
): Flow<Unit> {
    return merge(
        heroCatalogSelections,
        homeCatalogOrderKeys,
        disabledHomeCatalogKeys,
        tmdbSettings,
        mdbListSettings,
        mdbListCatalogPreferences,
        omdbSettings,
        animeSkipEnabled,
        animeSkipClientId,
        geminiSettings,
        posterRatingsSettings,
        premiumizeSettings,
        premiumizeAccountState,
        realDebridState,
        traktAuthState,
        traktCatalogPreferences
    )
}

internal fun buildAccountConfigSyncPayload(
    integrations: IntegrationSettings,
    heroCatalogKeys: List<String>,
    homeCatalogOrderKeys: List<String>,
    disabledHomeCatalogKeys: List<String>,
    traktCatalogEnabledSet: List<String>,
    traktCatalogOrder: List<String>,
    traktSelectedPopularListKeys: List<String>,
    mdbListHiddenPersonalListKeys: List<String>,
    mdbListSelectedTopListKeys: List<String>,
    mdbListCatalogOrder: List<String>,
    formatter: FormatterSyncSettings
): AccountConfigSyncPayload {
    return AccountConfigSyncPayload(
        schemaVersion = ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION,
        integrations = integrations,
        catalogs = CatalogSyncSettings(
            home = HomeCatalogSyncSettings(
                heroCatalogKeys = heroCatalogKeys,
                homeCatalogOrderKeys = homeCatalogOrderKeys,
                disabledHomeCatalogKeys = disabledHomeCatalogKeys
            ),
            trakt = TraktCatalogSyncSettings(
                catalogEnabledSet = traktCatalogEnabledSet,
                catalogOrder = traktCatalogOrder,
                selectedPopularListKeys = traktSelectedPopularListKeys
            ),
            mdblist = MDBListCatalogSyncSettings(
                hiddenPersonalListKeys = mdbListHiddenPersonalListKeys,
                selectedTopListKeys = mdbListSelectedTopListKeys,
                catalogOrder = mdbListCatalogOrder
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
                        }.getOrDefault(AddonParserPreset.GENERIC)
                    )
                }
        }
}

internal suspend fun buildImdbSyncSettings(
    imdbSettingsDataStore: ImdbSettingsDataStore
): ImdbSyncSettings {
    val settings: ImdbSettings = imdbSettingsDataStore.settings.first()
    return ImdbSyncSettings(
        enabled = settings.enabled,
        baseUrl = settings.baseUrl
    )
}

internal suspend fun applyImdbSyncSettings(
    settings: ImdbSyncSettings,
    imdbSettingsDataStore: ImdbSettingsDataStore
) {
    imdbSettingsDataStore.setEnabled(settings.enabled)
    imdbSettingsDataStore.setBaseUrl(settings.baseUrl)
}

internal suspend fun applyAccountConfigSyncSettings(
    settings: AccountConfigSyncPayload,
    layoutPreferenceDataStore: LayoutPreferenceDataStore,
    tmdbSettingsDataStore: TmdbSettingsDataStore,
    mdbListSettingsDataStore: MDBListSettingsDataStore,
    omdbSettingsDataStore: OmdbSettingsDataStore,
    animeSkipSettingsDataStore: AnimeSkipSettingsDataStore,
    geminiSettingsDataStore: GeminiSettingsDataStore,
    imdbSettingsDataStore: ImdbSettingsDataStore,
    posterRatingsSettingsDataStore: PosterRatingsSettingsDataStore,
    traktSettingsDataStore: TraktSettingsDataStore,
    playerSettingsDataStore: PlayerSettingsDataStore
) {
    layoutPreferenceDataStore.setHeroCatalogKeys(settings.catalogs.home.heroCatalogKeys)
    layoutPreferenceDataStore.setHomeCatalogOrderKeys(settings.catalogs.home.homeCatalogOrderKeys)
    layoutPreferenceDataStore.setDisabledHomeCatalogKeys(settings.catalogs.home.disabledHomeCatalogKeys)

    tmdbSettingsDataStore.setEnabled(settings.integrations.tmdb.enabled)
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
    mdbListSettingsDataStore.setCatalogPreferences(
        hiddenPersonalListKeys = settings.catalogs.mdblist.hiddenPersonalListKeys.toSet(),
        selectedTopListKeys = settings.catalogs.mdblist.selectedTopListKeys.toSet(),
        catalogOrder = settings.catalogs.mdblist.catalogOrder
    )

    omdbSettingsDataStore.setEnabled(settings.integrations.omdb.enabled)

    animeSkipSettingsDataStore.setEnabled(settings.integrations.animeSkip.enabled)
    animeSkipSettingsDataStore.setClientId(settings.integrations.animeSkip.clientId)

    geminiSettingsDataStore.setEnabled(settings.integrations.gemini.enabled)

    posterRatingsSettingsDataStore.setRpdbEnabled(settings.integrations.posterRatings.rpdbEnabled)
    posterRatingsSettingsDataStore.setTopPostersEnabled(settings.integrations.posterRatings.topPostersEnabled)

    traktSettingsDataStore.setCatalogPreferences(
        enabledCatalogs = settings.catalogs.trakt.catalogEnabledSet.toSet(),
        catalogOrder = settings.catalogs.trakt.catalogOrder,
        selectedPopularListKeys = settings.catalogs.trakt.selectedPopularListKeys.toSet()
    )

    playerSettingsDataStore.setSyncedFormatterEnabled(settings.formatter.enabled)
    playerSettingsDataStore.setSyncedFormatterSelectedTemplateId(settings.formatter.selectedTemplateId)
    playerSettingsDataStore.setSyncedFormatterCustomTemplate(
        label = settings.formatter.customTemplate?.label,
        nameTemplate = settings.formatter.customTemplate?.nameTemplate,
        descriptionTemplate = settings.formatter.customTemplate?.descriptionTemplate
    )
    applyImdbSyncSettings(settings.integrations.imdb, imdbSettingsDataStore)
}
