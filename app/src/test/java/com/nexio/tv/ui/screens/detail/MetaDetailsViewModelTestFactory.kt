package com.nexio.tv.ui.screens.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.testMetadataRouterFacade
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.anime.KitsuMetadataService
import com.nexio.tv.core.anime.projection.AnimeSeasonDetailRepository
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktAuthState
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.repository.MetadataDisplayRepository
import com.nexio.tv.data.repository.DetailRatingDisplayRepository
import com.nexio.tv.data.repository.DetailSecondaryDisplayRepository
import com.nexio.tv.data.repository.EpisodeRatingsSelectionRepository
import com.nexio.tv.data.repository.MDBListRepository
import com.nexio.tv.data.integration.metadata.MetadataSecondaryRepository
import com.nexio.tv.data.repository.ReviewsRepository
import com.nexio.tv.data.repository.TitleRatingOverrideRepository
import com.nexio.tv.data.repository.TrackingScrobbleService
import com.nexio.tv.data.trailer.SeasonMediaAvailability
import com.nexio.tv.data.trailer.TrailerService
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.LibraryRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Sentinel addon base URL used in tests to seed the ViewModel with the initial meta's
 * videos and links. When the canonical resolver path is taken, the production code in
 * [MetaDetailsViewModel.loadMeta] loads from this URL so that the initial episode list
 * and franchise link structure are visible immediately — matching the pre-canonical
 * architecture where the addon meta was the primary source of initial data.
 */
const val TEST_INITIAL_META_ADDON_URL = "test://initial"

/**
 * Shared factory for constructing [MetaDetailsViewModel] in unit tests.
 * All parameters have sensible defaults — override only what the test cares about.
 *
 * The [addonBaseUrl] defaults to [TEST_INITIAL_META_ADDON_URL] so that the production
 * "seed initial videos/links from addon" path fires and the test [meta]'s videos and
 * links are preserved through the canonical resolver path.
 */
fun buildMetaDetailsViewModel(
    meta: Meta,
    itemId: String = meta.id,
    itemType: String = "series",
    addonBaseUrl: String? = TEST_INITIAL_META_ADDON_URL,
    metaRepository: MetaRepository = defaultMetaRepository(meta),
    tmdbService: TmdbService = defaultTmdbService(),
    tmdbMetadataService: TmdbMetadataService = mockk(relaxed = true),
    tvMetadataRouter: TvMetadataRouter = defaultTvMetadataRouter(),
    kitsuMetadataService: KitsuMetadataService = mockk(relaxed = true),
    profileBoundary: ProfileBoundary = defaultProfileBoundary(),
    profileManager: ProfileManager = defaultProfileManager(),
    tmdbSettings: TmdbSettings = TmdbSettings(),
    watchProgressRepository: WatchProgressRepository = defaultWatchProgressRepository(),
    libraryRepository: LibraryRepository = defaultLibraryRepository(),
    trailerService: TrailerService? = null,
    metadataRouterFacade: MetadataRouterFacade? = null,
    metadataDisplayRepository: MetadataDisplayRepository? = null,
    addonRepository: AddonRepository? = null,
    mdbListRepository: MDBListRepository = mockk(relaxed = true),
    titleRatingOverrideRepository: TitleRatingOverrideRepository = defaultTitleRatingOverrideRepository(),
    episodeRatingsSelectionRepository: EpisodeRatingsSelectionRepository = mockk(relaxed = true),
    animeSeasonDetailRepository: AnimeSeasonDetailRepository = mockk(relaxed = true)
): MetaDetailsViewModel {
    val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>()
    every { layoutPreferenceDataStore.detailPageTrailerButtonEnabled } returns flowOf(false)
    every { layoutPreferenceDataStore.preferExternalMetaAddonDetail } returns flowOf(false)
    every { layoutPreferenceDataStore.blurUnwatchedEpisodes } returns flowOf(false)

    val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
    every { tmdbSettingsDataStore.settings } returns flowOf(tmdbSettings)

    val traktAuthDataStore = mockk<TraktAuthDataStore>()
    every { traktAuthDataStore.isEffectivelyAuthenticated } returns flowOf(false)
    every { traktAuthDataStore.state } returns flowOf(TraktAuthState())

    val effectiveTrailerService = trailerService ?: mockk<TrailerService>(relaxed = true).also {
        coEvery { it.getTitleMediaAvailability(any(), any(), any(), any()) } returns false
        coEvery { it.getSeasonMediaAvailability(any(), any(), any(), any()) } returns SeasonMediaAvailability()
    }

    val addonRepository = addonRepository ?: run {
        val default = mockk<AddonRepository>()
        every { default.getInstalledAddons() } returns flowOf(emptyList())
        default
    }

    val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
    every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())

    val context = mockk<Context>(relaxed = true)

    val metadataSecondaryRepository = MetadataSecondaryRepository(
        tmdbMetadataService = tmdbMetadataService,
        kitsuMetadataService = kitsuMetadataService
    )

    val effectiveMetadataRouterFacade = metadataRouterFacade ?: testMetadataRouterFacade(
        providerMetadataRouter = tvMetadataRouter,
        metadataSecondaryRepository = metadataSecondaryRepository,
        trailerService = effectiveTrailerService
    )

    return MetaDetailsViewModel(
        context = context,
        metaRepository = metaRepository,
        traktAuthDataStore = traktAuthDataStore,
        reviewsRepository = mockk<ReviewsRepository>(relaxed = true),
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        metadataRouterFacade = effectiveMetadataRouterFacade,
        metadataDisplayRepository = metadataDisplayRepository ?: MetadataDisplayRepository(
            metadataRouterFacade = effectiveMetadataRouterFacade,
            detailRatingDisplayRepository = DetailRatingDisplayRepository(
                titleRatingOverrideRepository = titleRatingOverrideRepository,
                mdbListRepository = mdbListRepository,
                episodeRatingsSelectionRepository = episodeRatingsSelectionRepository
            ),
            detailSecondaryDisplayRepository = DetailSecondaryDisplayRepository(
                metadataSecondaryRepository = metadataSecondaryRepository
            )
        ),
        profileBoundary = profileBoundary,
        profileManager = profileManager,
        libraryRepository = libraryRepository,
        traktLibraryService = mockk(relaxed = true),
        watchProgressRepository = watchProgressRepository,
        continueWatchingSnapshotService = mockk(relaxed = true),
        addonRepository = addonRepository,
        trackingScrobbleService = mockk<TrackingScrobbleService>(relaxed = true),
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        playerSettingsDataStore = playerSettingsDataStore,
        animeSeasonDetailRepository = animeSeasonDetailRepository,
        savedStateHandle = SavedStateHandle(
            mapOf(
                "itemId" to itemId,
                "itemType" to itemType,
                "addonBaseUrl" to addonBaseUrl
            )
        )
    )
}

fun defaultMetaRepository(meta: Meta): MetaRepository {
    val repo = mockk<MetaRepository>()
    every {
        repo.hydrateAddonOriginItem(
            addon = any(),
            type = any(),
            id = any(),
            cacheOnDisk = any(),
            writeToDisk = any(),
            origin = any()
        )
    } returns flowOf(NetworkResult.Success(meta))
    // Seed the test-initial URL so that the canonical-path addon-seed step in
    // MetaDetailsViewModel.loadMeta() can load the initial meta's videos and links.
    every {
        repo.getMeta(
            addonBaseUrl = TEST_INITIAL_META_ADDON_URL,
            type = any(),
            id = any(),
            cacheOnDisk = any(),
            writeToDisk = any(),
            origin = any()
        )
    } returns flowOf(NetworkResult.Success(meta))
    return repo
}

fun defaultTmdbService(): TmdbService {
    val service = mockk<TmdbService>(relaxed = true)
    coEvery { service.ensureTmdbId(any(), any()) } returns null
    coEvery { service.tmdbToImdb(any(), any()) } returns null
    return service
}

fun defaultTvMetadataRouter(): TvMetadataRouter {
    val router = mockk<TvMetadataRouter>()
    coEvery { router.fetchEnrichment(any()) } returns TvMetadataDecision(
        provider = TvProvider.TMDB,
        reason = TvMetadataDecisionReason.TVDB_INACTIVE,
        value = null
    )
    coEvery { router.fetchEpisodeEnrichment(any()) } returns TvMetadataDecision(
        provider = TvProvider.TMDB,
        reason = TvMetadataDecisionReason.TVDB_INACTIVE,
        value = emptyMap()
    )
    coEvery { router.fetchSeasonEpisodes(any(), any(), any(), any()) } returns TvMetadataDecision(
        provider = TvProvider.TMDB,
        reason = TvMetadataDecisionReason.TVDB_INACTIVE,
        value = emptyList()
    )
    return router
}

fun defaultProfileBoundary(): ProfileBoundary {
    return mockk {
        every { currentLanguageTag() } returns "en"
    }
}

fun defaultWatchProgressRepository(): WatchProgressRepository {
    val repo = mockk<WatchProgressRepository>(relaxed = true)
    every { repo.getAllEpisodeProgress(any(), any()) } returns flowOf(emptyMap())
    every { repo.getProgress(any(), any()) } returns flowOf(null)
    every { repo.isWatched(any(), any(), any(), any()) } returns flowOf(false)
    return repo
}

fun defaultProfileManager(): ProfileManager {
    val session = ActiveProfileSession(
        profileId = 1,
        sessionId = "session-1",
        sessionOrdinal = 1L,
        startedAtMs = 1_000L
    )
    return mockk {
        every { this@mockk.activeProfileId } returns MutableStateFlow(1)
        every { this@mockk.activeProfileSession } returns MutableStateFlow(session)
        every { this@mockk.isPrimaryProfileActive } returns true
    }
}

fun defaultLibraryRepository(): LibraryRepository {
    val repo = mockk<LibraryRepository>(relaxed = true)
    every { repo.sourceMode } returns flowOf(LibrarySourceMode.LOCAL)
    every { repo.listTabs } returns flowOf(emptyList())
    every { repo.isInLibrary(any(), any()) } returns flowOf(false)
    every { repo.isInWatchlist(any(), any()) } returns flowOf(false)
    return repo
}

fun defaultTitleRatingOverrideRepository(): TitleRatingOverrideRepository {
    return mockk<TitleRatingOverrideRepository>().also { repository ->
        coEvery { repository.titleRatingCandidates(any<MetaPreview>(), any(), any()) } returns emptyList()
        coEvery { repository.titleRatingCandidates(any<Meta>(), any(), any(), any(), any()) } returns emptyList()
        coEvery { repository.enrichMeta(any(), any(), any()) } answers { firstArg() }
        coEvery { repository.enrichMeta(any(), any(), any(), any()) } answers { firstArg() }
    }
}
