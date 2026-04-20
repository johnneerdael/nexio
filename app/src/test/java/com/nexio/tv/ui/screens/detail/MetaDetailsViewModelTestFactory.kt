package com.nexio.tv.ui.screens.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.profile.ProfileBoundary
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
import com.nexio.tv.data.repository.EpisodeRatingsSelectionRepository
import com.nexio.tv.data.repository.MDBListRepository
import com.nexio.tv.data.repository.TraktAuthService
import com.nexio.tv.data.repository.TrackingScrobbleService
import com.nexio.tv.data.trailer.SeasonMediaAvailability
import com.nexio.tv.data.trailer.TrailerService
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.LibraryRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

/**
 * Shared factory for constructing [MetaDetailsViewModel] in unit tests.
 * All parameters have sensible defaults — override only what the test cares about.
 */
fun buildMetaDetailsViewModel(
    meta: Meta,
    itemId: String = meta.id,
    itemType: String = "series",
    metaRepository: MetaRepository = defaultMetaRepository(meta),
    tmdbService: TmdbService = defaultTmdbService(),
    tmdbMetadataService: TmdbMetadataService = mockk(relaxed = true),
    tvMetadataRouter: TvMetadataRouter = defaultTvMetadataRouter(),
    profileBoundary: ProfileBoundary = defaultProfileBoundary(),
    tmdbSettings: TmdbSettings = TmdbSettings(),
    watchProgressRepository: WatchProgressRepository = defaultWatchProgressRepository(),
    libraryRepository: LibraryRepository = defaultLibraryRepository()
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

    val trailerService = mockk<TrailerService>(relaxed = true)
    coEvery { trailerService.getTitleMediaAvailability(any(), any(), any(), any()) } returns false
    coEvery { trailerService.getSeasonMediaAvailability(any(), any(), any(), any()) } returns SeasonMediaAvailability()

    val addonRepository = mockk<AddonRepository>()
    every { addonRepository.getInstalledAddons() } returns flowOf(emptyList())

    val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
    every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())

    val context = mockk<Context>(relaxed = true)
    val titleRatingOverrideRepository = mockk<com.nexio.tv.data.repository.TitleRatingOverrideRepository>()
    coEvery { titleRatingOverrideRepository.enrichMeta(any(), any(), any()) } answers { firstArg() }

    return MetaDetailsViewModel(
        context = context,
        metaRepository = metaRepository,
        traktApi = mockk(relaxed = true),
        traktAuthService = mockk(relaxed = true),
        traktAuthDataStore = traktAuthDataStore,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        tmdbService = tmdbService,
        tmdbMetadataService = tmdbMetadataService,
        tvMetadataRouter = tvMetadataRouter,
        profileBoundary = profileBoundary,
        mdbListRepository = mockk(relaxed = true),
        titleRatingOverrideRepository = titleRatingOverrideRepository,
        episodeRatingsSelectionRepository = mockk(relaxed = true),
        libraryRepository = libraryRepository,
        watchProgressRepository = watchProgressRepository,
        continueWatchingSnapshotService = mockk(relaxed = true),
        addonRepository = addonRepository,
        trackingScrobbleService = mockk<TrackingScrobbleService>(relaxed = true),
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        playerSettingsDataStore = playerSettingsDataStore,
        trailerService = trailerService,
        savedStateHandle = SavedStateHandle(
            mapOf(
                "itemId" to itemId,
                "itemType" to itemType
            )
        )
    )
}

fun defaultMetaRepository(meta: Meta): MetaRepository {
    val repo = mockk<MetaRepository>()
    every {
        repo.getMetaFromAllAddons(
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
    every { repo.getAllEpisodeProgress(any()) } returns flowOf(emptyMap())
    every { repo.getProgress(any()) } returns flowOf(null)
    return repo
}

fun defaultLibraryRepository(): LibraryRepository {
    val repo = mockk<LibraryRepository>(relaxed = true)
    every { repo.sourceMode } returns flowOf(LibrarySourceMode.LOCAL)
    every { repo.listTabs } returns flowOf(emptyList())
    every { repo.isInLibrary(any(), any()) } returns flowOf(false)
    every { repo.isInWatchlist(any(), any()) } returns flowOf(false)
    return repo
}
