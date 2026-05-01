package com.nexio.tv.ui.screens.detail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.BuildConfig
import com.nexio.tv.core.anime.AnimeIdSource
import com.nexio.tv.core.anime.AnimeStremioId
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.metadata.router.FieldResolver
import com.nexio.tv.core.metadata.router.InMemoryAnimeIdentityIndex
import com.nexio.tv.core.metadata.router.InMemoryIdMappingStore
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataIdentityResolver
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRequestNormalizer
import com.nexio.tv.core.metadata.router.MetadataRouter
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ReviewsPage
import com.nexio.tv.core.metadata.router.ProviderPlanExecutor
import com.nexio.tv.core.metadata.router.ProviderPlanRunner
import com.nexio.tv.core.metadata.router.ResolverOrchestrator
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.core.tvdb.TvdbLanguageMapper
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityCalculator
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityPrecision
import com.nexio.tv.core.tvdb.TvdbSeriesTiming
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.data.trailer.TrailerService
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.EpisodeRatingsSelectionRepository
import com.nexio.tv.data.repository.MDBListRepository
import com.nexio.tv.data.integration.metadata.MetadataSecondaryRepository
import com.nexio.tv.data.repository.ReviewsRepository
import com.nexio.tv.data.repository.TrackingScrobbleItem
import com.nexio.tv.data.repository.TrackingScrobbleService
import com.nexio.tv.data.repository.AirDateGate
import com.nexio.tv.data.repository.TitleRatingOverrideRepository
import com.nexio.tv.data.repository.parseContentIds
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.ListMembershipChanges
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.NextToWatch
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.model.orDefault
import com.nexio.tv.domain.repository.LibraryRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import com.nexio.tv.core.util.isUnreleased
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.Context
import com.nexio.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

private const val TAG = "MetaDetailsViewModel"

private fun debugLog(tag: String, message: String) {
    if (!BuildConfig.DEBUG) return
    runCatching { Log.d(tag, message) }
}
private const val TRAKT_REVIEWS_PAGE_SIZE = 8

internal fun formatTvdbDetailLocalReleaseInfo(
    enrichment: TvMetadataEnrichment,
    deviceZoneId: ZoneId = ZoneId.systemDefault()
): String? = formatTvdbLocalReleaseInfo(
    releaseInfo = enrichment.releaseInfo,
    enrichment = enrichment,
    deviceZoneId = deviceZoneId
)

internal fun formatTvdbEpisodeLocalReleaseInfo(
    airDate: String?,
    enrichment: TvMetadataEnrichment,
    deviceZoneId: ZoneId = ZoneId.systemDefault()
): String? = formatTvdbLocalReleaseInfo(
    releaseInfo = airDate,
    enrichment = enrichment,
    deviceZoneId = deviceZoneId
)

private fun formatTvdbLocalReleaseInfo(
    releaseInfo: String?,
    enrichment: TvMetadataEnrichment,
    deviceZoneId: ZoneId
): String? {
    val availability = TvdbAirAvailabilityCalculator().computeAvailability(
        episodeAiredDate = releaseInfo,
        seriesTiming = TvdbSeriesTiming(
            airsTime = enrichment.airsTime,
            originalCountry = enrichment.originalCountry,
            originalNetwork = enrichment.originalNetwork,
            latestNetwork = enrichment.latestNetwork,
            platformName = enrichment.platformName
        ),
        deviceZoneId = deviceZoneId
    )
    if (availability.precision != TvdbAirAvailabilityPrecision.EXACT_INSTANT) return null
    val instantMs = availability.instantMs ?: return null
    return Instant.ofEpochMilli(instantMs)
        .atZone(deviceZoneId)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US))
}

internal fun shouldStopAutoTrailerOnLifecyclePause(
    isTrailerPlaying: Boolean,
    showTrailerControls: Boolean
): Boolean = isTrailerPlaying && !showTrailerControls

internal fun shouldTreatDetailTrailerPlaybackAsActiveTime(
    isTrailerPlaying: Boolean,
    showTrailerControls: Boolean
): Boolean = isTrailerPlaying && showTrailerControls

private data class DetailMetadataEnrichment(
    val meta: Meta,
    val tvEnrichment: TvMetadataEnrichment?,
    val animeRelated: List<com.nexio.tv.domain.model.MetaPreview> = emptyList(),
    val isAnimeDetail: Boolean = false,
    val tvdbLanguage: String,
    val tmdbContentType: ContentType,
    val isTvContent: Boolean,
    val settings: TmdbSettings
)

@HiltViewModel
class MetaDetailsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metaRepository: MetaRepository,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val reviewsRepository: ReviewsRepository,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tmdbService: TmdbService,
    private val metadataRouterFacade: MetadataRouterFacade,
    private val metadataSecondaryRepository: MetadataSecondaryRepository,
    private val profileBoundary: ProfileBoundary,
    private val mdbListRepository: MDBListRepository,
    private val titleRatingOverrideRepository: TitleRatingOverrideRepository,
    private val episodeRatingsSelectionRepository: EpisodeRatingsSelectionRepository,
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val continueWatchingSnapshotService: ContinueWatchingSnapshotService,
    private val addonRepository: AddonRepository,
    private val trackingScrobbleService: TrackingScrobbleService,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val trailerService: TrailerService,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val itemId: String = savedStateHandle["itemId"] ?: ""
    private val itemType: String = savedStateHandle["itemType"] ?: ""
    private val preferredAddonBaseUrl: String? = savedStateHandle["addonBaseUrl"]
    private val detailSource: String = (savedStateHandle["detailSource"] as? String)
        ?.trim()
        ?.lowercase()
        .orEmpty()
    private val shouldCacheDetailMetaOnDisk: Boolean = detailSource !in setOf("library", "search")
    private val detailMetaOrigin: String = if (shouldCacheDetailMetaOnDisk) "detail" else "detail_$detailSource"

    private val _uiState = MutableStateFlow(MetaDetailsUiState())
    val uiState: StateFlow<MetaDetailsUiState> = _uiState.asStateFlow()
    private val effectiveContentId = MutableStateFlow(itemId)

    private var moreLikeThisJob: Job? = null
    private var reviewsJob: Job? = null
    private var collectionJob: Job? = null
    private var episodeMetadataJob: Job? = null
    private var episodeRatingsJob: Job? = null
    private var kitsuBridgeHydrationJob: Job? = null
    private var nextToWatchJob: Job? = null
    private var idleTimerJob: Job? = null
    private var trailerFetchJob: Job? = null
    private val loadingSeasonAvailability = mutableSetOf<Int>()

    private var trailerHasPlayed = false
    private var isPlayButtonFocused = false
    private var currentReviewsMetaId: String? = null
    private var aggregatedReviewsCache: MutableList<MetaReview> = mutableListOf()
    private var hasMoreTraktReviews = false
    private var nextTraktReviewsPage = 2
    private var isLoadingMoreReviews = false
    private var currentTmdbReviewId: String? = null
    private var currentTmdbReviewContentType: ContentType? = null

    init {
        observeDeterministicAutoplaySetting()
        observeInstalledAddons()
        observeMetaViewSettings()
        observeLibraryState()
        observeWatchProgress()
        observeWatchedEpisodes()
        observeMovieWatched()
        observeBlurUnwatchedEpisodes()
        loadMeta()
    }

    private fun observeDeterministicAutoplaySetting() {
        viewModelScope.launch {
            playerSettingsDataStore.playerSettings
                .map { it.deterministicAutoplayEnabled }
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _uiState.update { state ->
                        if (state.deterministicAutoplayEnabled == enabled) state
                        else state.copy(deterministicAutoplayEnabled = enabled)
                    }
                }
        }
    }

    private fun observeInstalledAddons() {
        viewModelScope.launch {
            addonRepository.getInstalledAddons()
                .distinctUntilChanged()
                .collectLatest { addons ->
                    val count = addons.size
                    val universalMode = shouldUseUniversalStreamerMode(count)
                    _uiState.update { state ->
                        if (
                            state.installedAddonsCount == count &&
                            state.universalStreamerModeEnabled == universalMode
                        ) {
                            state
                        } else {
                            state.copy(
                                installedAddonsCount = count,
                                universalStreamerModeEnabled = universalMode
                            )
                        }
                    }
                }
        }
    }

    private fun observeMetaViewSettings() {
        viewModelScope.launch {
            layoutPreferenceDataStore.detailPageTrailerButtonEnabled
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    _uiState.update { state ->
                        if (state.trailerButtonEnabled == enabled) {
                            state
                        } else {
                            state.copy(trailerButtonEnabled = enabled)
                        }
                    }
                }
        }
    }

    private fun setTrailerPlaybackState(
        isPlaying: Boolean,
        showControls: Boolean,
        hideLogo: Boolean
    ) {
        _uiState.update { state ->
            if (
                state.isTrailerPlaying == isPlaying &&
                state.showTrailerControls == showControls &&
                state.hideLogoDuringTrailer == hideLogo
            ) {
                state
            } else {
                state.copy(
                    isTrailerPlaying = isPlaying,
                    showTrailerControls = showControls,
                    hideLogoDuringTrailer = hideLogo
                )
            }
        }
    }

    private fun enterTrailerResolvingState(selectedSeason: Int = _uiState.value.selectedSeason) {
        _uiState.update { state ->
            val cachedSeasonAvailability = state.seasonMediaAvailabilityBySeason[selectedSeason]
            state.copy(
                trailerResolutionStatus = TrailerResolutionStatus.RESOLVING,
                isTrailerLoading = true,
                trailerUrl = null,
                trailerAudioUrl = null,
                trailerExternalUrl = null,
                pendingExternalTrailerUrl = null,
                selectedSeasonHasPlayableTrailerMedia = cachedSeasonAvailability?.hasTrailerOrTeaser == true,
                selectedSeasonHasPlayableRecap = cachedSeasonAvailability?.hasRecap == true
            )
        }
    }

    private fun updateNextToWatch(nextToWatch: NextToWatch) {
        _uiState.update { state ->
            if (state.nextToWatch == nextToWatch) return@update state
            state.withNextToWatch(nextToWatch)
        }
    }

    fun onEvent(event: MetaDetailsEvent) {
        when (event) {
            is MetaDetailsEvent.OnSeasonSelected -> selectSeason(event.season)
            is MetaDetailsEvent.OnSeasonShortPress -> playSeasonTrailer(event.season)
            is MetaDetailsEvent.OnSeasonOptionsOpened -> preloadSeasonMediaAvailability(event.season, forceRefresh = true)
            is MetaDetailsEvent.OnPlaySeasonRecap -> playSeasonRecap(event.season)
            is MetaDetailsEvent.OnEpisodeClick -> { /* Navigate to stream */ }
            MetaDetailsEvent.OnPlayClick -> { /* Start playback */ }
            MetaDetailsEvent.OnToggleLibrary -> toggleLibrary()
            MetaDetailsEvent.OnRetry -> loadMeta()
            MetaDetailsEvent.OnBackPress -> { /* Handle in screen */ }
            MetaDetailsEvent.OnUserInteraction -> handleUserInteraction()
            MetaDetailsEvent.OnPlayButtonFocused -> handlePlayButtonFocused()
            MetaDetailsEvent.OnTrailerButtonClick -> handleTrailerButtonClick()
            MetaDetailsEvent.OnTrailerEnded -> handleTrailerEnded()
            MetaDetailsEvent.OnLifecyclePause -> handleLifecyclePause()
            MetaDetailsEvent.OnExternalTrailerConsumed -> consumePendingExternalTrailer()
            MetaDetailsEvent.OnToggleMovieWatched -> toggleMovieWatched()
            is MetaDetailsEvent.OnToggleEpisodeWatched -> toggleEpisodeWatched(event.video)
            is MetaDetailsEvent.OnClearEpisodeProgress -> clearEpisodeProgress(event.video)
            is MetaDetailsEvent.OnCheckInEpisode -> checkInEpisode(event.video)
            is MetaDetailsEvent.OnMarkSeasonWatched -> markSeasonWatched(event.season)
            is MetaDetailsEvent.OnMarkSeasonUnwatched -> markSeasonUnwatched(event.season)
            is MetaDetailsEvent.OnMarkPreviousEpisodesWatched -> markPreviousEpisodesWatched(event.video)
            MetaDetailsEvent.OnLibraryLongPress -> openListPicker()
            is MetaDetailsEvent.OnPickerMembershipToggled -> togglePickerMembership(event.listKey)
            MetaDetailsEvent.OnPickerSave -> savePickerMembership()
            MetaDetailsEvent.OnPickerDismiss -> dismissListPicker()
            MetaDetailsEvent.OnClearMessage -> clearMessage()
            is MetaDetailsEvent.OnReviewItemFocused -> maybeLoadMoreReviews(event.index)
        }
    }

    private fun observeLibraryState() {
        viewModelScope.launch {
            libraryRepository.sourceMode
                .distinctUntilChanged()
                .collectLatest { sourceMode ->
                    _uiState.update { state ->
                        if (state.librarySourceMode == sourceMode) {
                            state
                        } else {
                            state.copy(librarySourceMode = sourceMode)
                        }
                    }
                }
        }

        viewModelScope.launch {
            libraryRepository.listTabs
                .distinctUntilChanged()
                .collectLatest { tabs ->
                _uiState.update { state ->
                    val selectedMembership = state.pickerMembership
                    val filteredMembership = if (selectedMembership.isEmpty()) {
                        selectedMembership
                    } else {
                        tabs.associate { tab -> tab.key to (selectedMembership[tab.key] == true) }
                    }
                    if (state.libraryListTabs == tabs &&
                        state.pickerMembership == filteredMembership
                    ) {
                        state
                    } else {
                        state.copy(
                            libraryListTabs = tabs,
                            pickerMembership = filteredMembership
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            libraryRepository.isInLibrary(itemId = itemId, itemType = itemType)
                .distinctUntilChanged()
                .collectLatest { inLibrary ->
                    _uiState.update { state ->
                        if (state.isInLibrary == inLibrary) state else state.copy(isInLibrary = inLibrary)
                    }
                }
        }

        viewModelScope.launch {
            libraryRepository.isInWatchlist(itemId = itemId, itemType = itemType)
                .distinctUntilChanged()
                .collectLatest { inWatchlist ->
                    _uiState.update { state ->
                        if (state.isInWatchlist == inWatchlist) state else state.copy(isInWatchlist = inWatchlist)
                    }
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeWatchProgress() {
        if (itemType.lowercase() == "movie") return
        viewModelScope.launch {
            effectiveContentId.flatMapLatest { contentId ->
                watchProgressRepository.getAllEpisodeProgress(contentId)
            }
                .distinctUntilChanged()
                .collectLatest { progressMap ->
                _uiState.update { state ->
                    val updatedState = state.withEpisodeProgressSnapshot(progressMap)
                    if (
                        state.episodeProgressMap == updatedState.episodeProgressMap &&
                        state.watchedEpisodes == updatedState.watchedEpisodes &&
                        state.episodeWatchOverrides == updatedState.episodeWatchOverrides
                    ) {
                        state
                    } else {
                        updatedState
                    }
                }
                // Recalculate next to watch when progress changes
                calculateNextToWatch()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeWatchedEpisodes() {
        if (itemType.lowercase() == "movie") return
        viewModelScope.launch {
            effectiveContentId.flatMapLatest { contentId ->
                watchProgressRepository.getAllEpisodeProgress(contentId)
            }
                .map { progressMap ->
                    progressMap
                        .filterValues { it.isCompleted() }
                        .keys
                }
                .distinctUntilChanged()
                .collectLatest { watchedSet ->
                _uiState.update { state ->
                    val overrides = reconcileEpisodeWatchOverrides(
                        rawWatchedSet = watchedSet,
                        overrides = state.episodeWatchOverrides
                    )
                    val effectiveWatched = applyEpisodeWatchOverrides(
                        rawWatchedSet = watchedSet,
                        overrides = overrides
                    )
                    if (
                        state.watchedEpisodes == effectiveWatched &&
                        state.episodeWatchOverrides == overrides
                    ) {
                        state
                    } else {
                        state.copy(
                            watchedEpisodes = effectiveWatched,
                            episodeWatchOverrides = overrides
                        )
                    }
                }
                calculateNextToWatch()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeMovieWatched() {
        if (itemType.lowercase() != "movie") return
        viewModelScope.launch {
            effectiveContentId.flatMapLatest { contentId ->
                watchProgressRepository.isWatched(contentId)
            }
                .distinctUntilChanged()
                .collectLatest { watched ->
                _uiState.update { state ->
                    if (state.isMovieWatched == watched) state else state.copy(isMovieWatched = watched)
                }
            }
        }
    }

    private fun observeBlurUnwatchedEpisodes() {
        viewModelScope.launch {
            layoutPreferenceDataStore.blurUnwatchedEpisodes
                .distinctUntilChanged()
                .collectLatest { enabled ->
                _uiState.update { state ->
                    if (state.blurUnwatchedEpisodes == enabled) state else state.copy(blurUnwatchedEpisodes = enabled)
                }
            }
        }
    }

    private fun loadMeta() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    trailerUrl = null,
                    trailerAudioUrl = null,
                    trailerExternalUrl = null,
                    pendingExternalTrailerUrl = null,
                    isTrailerPlaying = false,
                    isTrailerLoading = false,
                    showTrailerControls = false,
                    hideLogoDuringTrailer = false,
                    selectedSeasonHasPlayableTrailerMedia = false,
                    selectedSeasonHasPlayableRecap = false,
                    seasonMediaAvailabilityBySeason = emptyMap(),
                    episodeRatings = emptyMap(),
                    isEpisodeRatingsLoading = false,
                    episodeRatingsError = null,
                    mdbListRatings = null,
                    showMdbListImdb = false,
                    relatedItems = emptyList(),
                    reviews = emptyList(),
                    isReviewsLoading = false,
                    reviewsError = null,
                    collection = emptyList(),
                    collectionName = null
                )
            }
            trailerHasPlayed = false
            isPlayButtonFocused = false
            idleTimerJob?.cancel()
            trailerFetchJob?.cancel()
            episodeMetadataJob?.cancel()
            loadingSeasonAvailability.clear()

            val metaLookupId = resolveMetaLookupId(itemId = itemId, itemType = itemType)

            val metadataRequest = MetadataRequest(
                contentId = metaLookupId,
                contentType = ContentType.fromString(itemType),
                sourceContext = MetadataSourceContext(
                    itemType = itemType,
                    addonMetadata = _uiState.value.meta?.toHomeDisplayMetadata()
                ),
                depth = MetadataDepth.DETAIL_CORE
            )

            val canonical = try {
                metadataRouterFacade.resolveRequest(metadataRequest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loadMeta resolveRequest failed for $metaLookupId: ${e.message}", e)
                null
            }

            if (canonical != null && canonical.route != null) {
                val meta = canonical.toMeta(
                    contentId = metaLookupId,
                    contentType = ContentType.fromString(itemType)
                )
                applyMetaWithEnrichment(meta)
            } else if (preferredAddonBaseUrl?.isNotBlank() == true) {
                // Last-resort A: item came from an addon catalog rail and the router yielded nothing.
                metaRepository.getMeta(
                    addonBaseUrl = preferredAddonBaseUrl,
                    type = itemType,
                    id = metaLookupId,
                    cacheOnDisk = shouldCacheDetailMetaOnDisk,
                    origin = detailMetaOrigin
                ).collect { result ->
                    when (result) {
                        is NetworkResult.Success -> applyMetaWithEnrichment(result.data)
                        is NetworkResult.Error -> {
                            if (tryApplyStreamOnlyFallback(result.message)) {
                                _uiState.update { state ->
                                    if (state.userMessage == null) {
                                        state.copy(
                                            userMessage = "Metadata unavailable. Opening stream selection.",
                                            userMessageIsError = false
                                        )
                                    } else {
                                        state
                                    }
                                }
                            } else {
                                _uiState.update { it.copy(isLoading = false, error = result.message) }
                            }
                        }
                        NetworkResult.Loading -> {
                            _uiState.update { it.copy(isLoading = true) }
                        }
                    }
                }
            } else {
                // Last-resort B: no canonical route AND no addon origin. Try all addons as absolute
                // fallback (covers legacy non-tvdb: ids like tt... where identity resolution
                // could not produce a provider-native id). This path is intentionally kept as a
                // safety net; canonical routing is always attempted first (above).
                metaRepository.getMetaFromAllAddons(
                    type = itemType,
                    id = metaLookupId,
                    cacheOnDisk = shouldCacheDetailMetaOnDisk,
                    origin = detailMetaOrigin
                ).collect { result ->
                    when (result) {
                        is NetworkResult.Success -> applyMetaWithEnrichment(result.data)
                        is NetworkResult.Error -> {
                            if (tryApplyStreamOnlyFallback(result.message)) {
                                _uiState.update { state ->
                                    if (state.userMessage == null) {
                                        state.copy(
                                            userMessage = "Metadata unavailable. Opening stream selection.",
                                            userMessageIsError = false
                                        )
                                    } else {
                                        state
                                    }
                                }
                            } else {
                                Log.w(TAG, "loadMeta: no canonical route AND no addon origin AND all-addons fallback failed for $metaLookupId")
                                _uiState.update { it.copy(isLoading = false, error = result.message) }
                            }
                        }
                        NetworkResult.Loading -> {
                            _uiState.update { it.copy(isLoading = true) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun resolveMetaLookupId(itemId: String, itemType: String): String {
        val raw = itemId.trim()
        if (!raw.startsWith("tmdb:", ignoreCase = true)) return raw

        val tmdbNumericId = raw
            .substringAfter(':', missingDelimiterValue = "")
            .substringBefore(':')
            .toIntOrNull()
            ?: return raw

        return tmdbService.tmdbToImdb(tmdbNumericId, itemType) ?: raw
    }

    private fun tryApplyStreamOnlyFallback(errorMessage: String?): Boolean {
        if (!shouldUseStreamOnlyFallback(errorMessage)) return false
        val fallbackMeta = buildStreamOnlyFallbackMeta()
        applyMeta(fallbackMeta)
        return true
    }

    private fun shouldUseStreamOnlyFallback(errorMessage: String?): Boolean {
        val normalized = errorMessage?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return false
        return normalized.contains("meta not found") ||
            normalized.contains("no addons support meta")
    }

    private fun buildStreamOnlyFallbackMeta(): Meta {
        val fallbackName = deriveFallbackName(itemId)
        return Meta(
            id = itemId,
            type = ContentType.fromString(itemType),
            rawType = itemType,
            name = fallbackName,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            writer = emptyList(),
            cast = emptyList(),
            videos = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            ageRating = null,
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            trailerYtIds = emptyList()
        )
    }

    private fun deriveFallbackName(sourceId: String): String {
        val candidate = sourceId
            .substringAfterLast(':')
            .substringBefore('?')
            .replace('_', ' ')
            .replace('.', ' ')
            .trim()
        return candidate.ifBlank { sourceId }
    }

    private fun applyMeta(meta: Meta) {
        meta.id.takeIf { it.isNotBlank() }?.let { loadedContentId ->
            effectiveContentId.value = loadedContentId
        }
        _uiState.update { state -> state.withRefreshedMeta(meta) }
        trailerHasPlayed = false
        preloadTitleTrailerAvailability(meta)
        preloadAllSeasonMediaAvailability(meta)

        // Calculate next to watch after meta is loaded
        calculateNextToWatch()
    }

    private suspend fun applyMetaWithEnrichment(meta: Meta) {
        val expandedMeta = expandAnimeAddonSeasons(meta)
        val preferredSeason = meta.videos
            .mapNotNull { it.season }
            .distinct()
            .singleOrNull()
        val enrichment = enrichMeta(expandedMeta, includeEpisodeMetadata = false)
        applyMeta(enrichment.meta)
        if (preferredSeason != null) {
            _uiState.update { state ->
                if (preferredSeason !in state.seasons || state.selectedSeason == preferredSeason) {
                    state
                } else {
                    state.withManualSeasonSelection(preferredSeason)
                }
            }
        }
        if (enrichment.isAnimeDetail) {
            _uiState.update { state ->
                state.copy(
                    isAnimeDetail = true,
                    relatedItems = enrichment.animeRelated,
                    reviews = emptyList(),
                    isReviewsLoading = false,
                    reviewsError = null
                )
            }
            hydrateKitsuNavigationTargetsAsync(enrichment.meta)
        } else {
            // Start recommendations fetch early for non-anime titles.
            _uiState.update { it.copy(isAnimeDetail = false) }
            loadMoreLikeThisAsync(meta)
            loadReviewsAsync(meta)
        }
        loadEpisodeMetadataAsync(enrichment)
        loadEpisodeRatingsAsync(enrichment.meta)
        loadMDBListRatings(enrichment.meta)
    }

    private fun loadEpisodeMetadataAsync(enrichment: DetailMetadataEnrichment) {
        episodeMetadataJob?.cancel()
        if (!enrichment.isTvContent || !enrichment.settings.useEpisodes) return

        val expectedMetaId = enrichment.meta.id
        episodeMetadataJob = viewModelScope.launch {
            try {
                val currentMeta = _uiState.value.meta?.takeIf { it.id == expectedMetaId } ?: return@launch
                val updated = applyTvEpisodeEnrichment(
                    targetMeta = currentMeta,
                    tvEnrichment = enrichment.tvEnrichment,
                    tmdbContentType = enrichment.tmdbContentType,
                    tvdbLanguage = enrichment.tvdbLanguage,
                    settings = enrichment.settings,
                    isTvContent = enrichment.isTvContent
                )

                _uiState.update { state ->
                    val stateMeta = state.meta ?: return@update state
                    if (stateMeta.id != expectedMetaId || stateMeta.videos == updated.videos) {
                        state
                    } else {
                        state.withRefreshedMeta(updated)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Failed to hydrate episode metadata for $expectedMetaId: ${error.message}", error)
            }
        }
    }

    private fun loadMoreLikeThisAsync(meta: Meta) {
        moreLikeThisJob?.cancel()
        moreLikeThisJob = viewModelScope.launch {
            val settings = tmdbSettingsDataStore.settings.first()
            if (!shouldLoadMoreLikeThis(settings)) {
                _uiState.update { it.copy(relatedItems = emptyList()) }
                return@launch
            }

            val tmdbContentType = resolveTmdbContentType(meta)
            val tmdbLookupType = tmdbContentType.toApiString()
            val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbLookupType)
                ?: tmdbService.ensureTmdbId(itemId, itemType)
            if (tmdbId.isNullOrBlank()) {
                _uiState.update { it.copy(relatedItems = emptyList()) }
                return@launch
            }

            val rawRecommendations = runCatching {
                metadataRouterFacade.fetchRecommendations(
                    metadataRequest = MetadataRequest(
                        contentId = "tmdb:$tmdbId",
                        contentType = tmdbContentType,
                        sourceContext = MetadataSourceContext(itemType = tmdbLookupType),
                        language = currentTvdbLanguageTag(),
                        depth = MetadataDepth.DETAIL_SECONDARY
                    ),
                    tmdbId = tmdbId,
                    contentType = tmdbContentType
                )
            }.getOrElse {
                if (it is CancellationException) throw it
                Log.w(TAG, "Failed to load More like this for ${meta.id}: ${it.message}")
                emptyList()
            }

            val recommendations = rawRecommendations

            _uiState.update { state ->
                if (state.meta == null || state.meta.id == meta.id) {
                    state.copy(relatedItems = recommendations)
                } else {
                    state
                }
            }
        }
    }

    private fun shouldLoadMoreLikeThis(settings: TmdbSettings): Boolean {
        return settings.isActive && settings.useMoreLikeThis
    }

    private fun shouldLoadReviews(settings: TmdbSettings): Boolean {
        return settings.isActive && settings.useReviews
    }

    private fun loadReviewsAsync(meta: Meta) {
        reviewsJob?.cancel()
        reviewsJob = viewModelScope.launch {
            currentReviewsMetaId = meta.id
            resetReviewsPaginationState()

            val settings = tmdbSettingsDataStore.settings.first()
            if (!shouldLoadReviews(settings)) {
                _uiState.update {
                    it.copy(
                        reviews = emptyList(),
                        isReviewsLoading = false,
                        reviewsError = null
                    )
                }
                return@launch
            }

            _uiState.update { state ->
                if (state.meta == null || state.meta.id == meta.id) {
                    state.copy(
                        reviews = emptyList(),
                        isReviewsLoading = true,
                        reviewsError = null
                    )
                } else {
                    state
                }
            }

            val tmdbContentType = resolveTmdbContentType(meta)
            val tmdbLookupType = tmdbContentType.toApiString()
            val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbLookupType)
                ?: tmdbService.ensureTmdbId(itemId, itemType)
            currentTmdbReviewId = tmdbId
            currentTmdbReviewContentType = tmdbContentType

            // F-05-02 closed: Trakt comments now route via MetadataRouterFacade.fetchReviewsPage,
            // delivered by TraktReviewMetadataAdapter (DI-bound, plan-step in DETAIL_SECONDARY).
            // The aggregated ReviewsPage carries TMDB + Trakt reviews merged by ReviewResolver.
            val initialPage = if (tmdbId.isNullOrBlank()) {
                ReviewsPage(reviews = emptyList(), hasMore = false, nextPage = null)
            } else {
                runCatching {
                    metadataRouterFacade.fetchReviewsPage(
                        metadataRequest = MetadataRequest(
                            contentId = "tmdb:$tmdbId",
                            contentType = tmdbContentType,
                            sourceContext = MetadataSourceContext(itemType = tmdbContentType.toApiString()),
                            language = currentTvdbLanguageTag(),
                            depth = MetadataDepth.DETAIL_SECONDARY
                        ),
                        tmdbId = tmdbId,
                        contentType = tmdbContentType,
                        page = 1,
                        limit = TRAKT_REVIEWS_PAGE_SIZE
                    )
                }.getOrElse {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "Failed to load reviews for ${meta.id}: ${it.message}")
                    ReviewsPage(reviews = emptyList(), hasMore = false, nextPage = null)
                }
            }

            if (currentReviewsMetaId != meta.id) return@launch

            aggregatedReviewsCache = initialPage.reviews.toMutableList()
            hasMoreTraktReviews = initialPage.hasMore
            nextTraktReviewsPage = initialPage.nextPage ?: 2

            _uiState.update { state ->
                if (state.meta == null || state.meta.id == meta.id) {
                    state.copy(
                        reviews = aggregatedReviewsCache.toList(),
                        isReviewsLoading = false,
                        reviewsError = if (aggregatedReviewsCache.isEmpty()) "Reviews are unavailable for this title." else null
                    )
                } else {
                    state
                }
            }
        }
    }

    private fun maybeLoadMoreReviews(focusedIndex: Int) {
        if (focusedIndex < 0) return
        if (!hasMoreTraktReviews || isLoadingMoreReviews) return
        val expectedMetaId = currentReviewsMetaId ?: return
        if (_uiState.value.meta?.id != expectedMetaId) return

        val lastIndex = _uiState.value.reviews.lastIndex
        if (lastIndex < 0) return
        val loadTriggerIndex = (lastIndex - 1).coerceAtLeast(0)
        if (focusedIndex < loadTriggerIndex) return

        loadMoreTraktReviewsPage(expectedMetaId)
    }

    private fun loadMoreTraktReviewsPage(expectedMetaId: String) {
        if (!hasMoreTraktReviews || isLoadingMoreReviews) return
        val tmdbId = currentTmdbReviewId ?: return
        val tmdbContentType = currentTmdbReviewContentType ?: return
        val pageToLoad = nextTraktReviewsPage

        isLoadingMoreReviews = true
        viewModelScope.launch {
            try {
                val pageResult = runCatching {
                    metadataRouterFacade.fetchReviewsPage(
                        metadataRequest = MetadataRequest(
                            contentId = "tmdb:$tmdbId",
                            contentType = tmdbContentType,
                            sourceContext = MetadataSourceContext(itemType = tmdbContentType.toApiString()),
                            language = currentTvdbLanguageTag(),
                            depth = MetadataDepth.DETAIL_SECONDARY
                        ),
                        tmdbId = tmdbId,
                        contentType = tmdbContentType,
                        page = pageToLoad,
                        limit = TRAKT_REVIEWS_PAGE_SIZE
                    )
                }.getOrElse {
                    if (it is CancellationException) throw it
                    Log.w(
                        TAG,
                        "Failed to load reviews page=$pageToLoad for $expectedMetaId: ${it.message}"
                    )
                    return@launch
                }

                if (currentReviewsMetaId != expectedMetaId) return@launch

                val existingIds = aggregatedReviewsCache
                    .asSequence()
                    .map { "${it.source}:${it.id}" }
                    .toHashSet()
                val uniqueNewReviews = pageResult.reviews.filter { review ->
                    existingIds.add("${review.source}:${review.id}")
                }
                if (uniqueNewReviews.isNotEmpty()) {
                    aggregatedReviewsCache.addAll(uniqueNewReviews)
                }

                hasMoreTraktReviews = pageResult.hasMore
                nextTraktReviewsPage = pageResult.nextPage ?: (pageToLoad + 1)

                _uiState.update { state ->
                    if (state.meta == null || state.meta.id == expectedMetaId) {
                        state.copy(
                            reviews = aggregatedReviewsCache.toList(),
                            reviewsError = if (aggregatedReviewsCache.isEmpty()) "Reviews are unavailable for this title." else null
                        )
                    } else {
                        state
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                isLoadingMoreReviews = false
            }
        }
    }

    private fun resetReviewsPaginationState() {
        aggregatedReviewsCache = mutableListOf()
        hasMoreTraktReviews = false
        nextTraktReviewsPage = 2
        isLoadingMoreReviews = false
        currentTmdbReviewId = null
        currentTmdbReviewContentType = null
    }

    private fun loadCollectionAsync(collectionId: Int, collectionName: String?, settings: TmdbSettings) {
        collectionJob?.cancel()
        collectionJob = viewModelScope.launch {
            if (!settings.isActive || !settings.useCollections) {
                _uiState.update { it.copy(collection = emptyList(), collectionName = null) }
                return@launch
            }

            val items = runCatching {
                metadataSecondaryRepository.fetchMovieCollection(
                    collectionId = collectionId
                )
            }.getOrElse {
                if (it is CancellationException) throw it
                Log.w(TAG, "Failed to load collection $collectionId: ${it.message}")
                emptyList()
            }

            val filteredItems = items

            _uiState.update { state ->
                state.copy(collection = filteredItems, collectionName = collectionName)
            }
        }
    }

    private suspend fun loadMDBListRatings(meta: Meta) {
        val ratingsResult = runCatching {
            mdbListRepository.getRatingsForMeta(
                meta = meta,
                fallbackItemId = itemId,
                fallbackItemType = itemType
            )
        }.getOrElse {
            if (it is CancellationException) throw it
            null
        }

        _uiState.update { state ->
            state.copy(
                mdbListRatings = ratingsResult?.ratings,
                showMdbListImdb = ratingsResult?.hasImdbRating == true
            )
        }
    }

    private fun loadEpisodeRatingsAsync(meta: Meta) {
        episodeRatingsJob?.cancel()
        val isSeries = isSeriesDetailMeta(meta)
        val seasonNumbers = meta.videos.mapNotNull { it.season }.distinct().sorted()

        _uiState.update { state ->
            if (state.meta == null || state.meta.id != meta.id) {
                state
            } else {
                state.copy(
                    episodeRatings = emptyMap(),
                    isEpisodeRatingsLoading = isSeries && seasonNumbers.isNotEmpty(),
                    episodeRatingsError = null
                )
            }
        }

        if (!isSeries || seasonNumbers.isEmpty()) return

        episodeRatingsJob = viewModelScope.launch {
            val episodesBySeason = meta.videos
                .mapNotNull { video ->
                    val season = video.season
                    val episode = video.episode
                    if (season != null && episode != null) season to episode else null
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, episodes) -> episodes.toSet() }

            try {
                val ratings = episodeRatingsSelectionRepository.getEpisodeRatings(
                    meta = meta,
                    fallbackItemId = itemId,
                    fallbackItemType = itemType,
                    episodesBySeason = episodesBySeason
                )

                _uiState.update { state ->
                    if (state.meta?.id != meta.id) {
                        state
                    } else {
                        state.copy(
                            episodeRatings = ratings,
                            isEpisodeRatingsLoading = false,
                            episodeRatingsError = null
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Failed to load episode ratings for ${meta.id}: ${error.message}", error)
                _uiState.update { state ->
                    if (state.meta?.id != meta.id) {
                        state
                    } else {
                        state.copy(
                            episodeRatings = emptyMap(),
                            isEpisodeRatingsLoading = false,
                            episodeRatingsError = context.getString(R.string.ratings_unavailable)
                        )
                    }
                }
            }
        }
    }

    private suspend fun enrichMeta(
        meta: Meta,
        includeEpisodeMetadata: Boolean = true
    ): DetailMetadataEnrichment {
        val settings = tmdbSettingsDataStore.settings.first()
        val tmdbContentType = resolveTmdbContentType(meta)
        val isTvContent = tmdbContentType == ContentType.SERIES || tmdbContentType == ContentType.TV
        val parsedAnimeIds = listOfNotNull(
            AnimeStremioId.parse(meta.id),
            AnimeStremioId.parse(itemId)
        )
        val hasAnimeId = parsedAnimeIds.any { animeId ->
            animeId.source != AnimeIdSource.IMDB || tmdbContentType != ContentType.MOVIE
        }
        val tvdbLanguage = currentTvdbLanguageTag()
        val tvDecision = if (isTvContent || hasAnimeId) {
            metadataRouterFacade.fetchTvEnrichment(
                metadataRequest = MetadataRequest(
                    contentId = meta.id,
                    contentType = tmdbContentType,
                    sourceContext = MetadataSourceContext(itemType = tmdbContentType.toApiString()),
                    language = tvdbLanguage,
                    depth = MetadataDepth.DETAIL_CORE
                ),
                tvRequest = TvMetadataRequest(
                    contentId = meta.id,
                    fallbackContentId = itemId,
                    contentType = tmdbContentType,
                    language = tvdbLanguage
                )
            )
        } else {
            null
        }
        val tvEnrichment = tvDecision?.value
        val isKitsuAnimeByProvider = tvDecision?.provider == TvProvider.KITSU
        fun result(updatedMeta: Meta): DetailMetadataEnrichment {
            return DetailMetadataEnrichment(
                meta = updatedMeta,
                tvEnrichment = tvEnrichment,
                animeRelated = emptyList(),
                isAnimeDetail = isKitsuAnimeByProvider,
                tvdbLanguage = tvdbLanguage,
                tmdbContentType = tmdbContentType,
                isTvContent = isTvContent,
                settings = settings
            )
        }

        val tmdbEnrichment = if (isTvContent) {
            val tmdbId = tvEnrichment?.remoteIds
                ?.get("tmdb")
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (
                tmdbId != null &&
                settings.isActive &&
                shouldSupplementTvdbDetailWithTmdb(tvEnrichment, settings)
            ) {
                metadataRouterFacade.fetchTmdbEnrichment(
                    metadataRequest = MetadataRequest(
                        contentId = "tmdb:$tmdbId",
                        contentType = tmdbContentType,
                        sourceContext = MetadataSourceContext(itemType = tmdbContentType.toApiString()),
                        language = tvdbLanguage,
                        depth = MetadataDepth.DETAIL_CORE
                    ),
                    tmdbId = tmdbId,
                    contentType = tmdbContentType
                )
            } else {
                null
            }
        } else {
            if (hasAnimeId && tvEnrichment != null) {
                null
            } else {
                if (!settings.isActive) return result(meta)
                val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbContentType.toApiString())
                    ?: tmdbService.ensureTmdbId(itemId, itemType)
                    ?: return result(meta)
                metadataRouterFacade.fetchTmdbEnrichment(
                    metadataRequest = MetadataRequest(
                        contentId = "tmdb:$tmdbId",
                        contentType = tmdbContentType,
                        sourceContext = MetadataSourceContext(itemType = tmdbContentType.toApiString()),
                        language = tvdbLanguage,
                        depth = MetadataDepth.DETAIL_CORE
                    ),
                    tmdbId = tmdbId,
                    contentType = tmdbContentType
                )
            }
        }

        var updated = meta
        val localizedTitle = tvEnrichment?.localizedTitle ?: tmdbEnrichment?.localizedTitle
        val description = tvEnrichment?.description ?: tmdbEnrichment?.description
        val genres = tvEnrichment?.genres?.takeIf { it.isNotEmpty() }
            ?: tmdbEnrichment?.genres?.takeIf { it.isNotEmpty() }
        val backdrop = tvEnrichment?.backdrop ?: tmdbEnrichment?.backdrop
        val logo = tvEnrichment?.logo ?: tmdbEnrichment?.logo
        val kitsuAdvancedSourceId = if (isKitsuAnimeByProvider) {
            listOf(meta.id, itemId).firstOrNull { AnimeStremioId.parse(it) != null }
        } else {
            null
        }
        val kitsuAdvanced = if (kitsuAdvancedSourceId != null) {
            metadataSecondaryRepository.fetchKitsuAdvancedDetail(
                rawId = kitsuAdvancedSourceId,
                mediaKind = tmdbContentType.toAnimeMediaKind(),
                preferredLanguageCode = tvEnrichment?.language
            )
        } else {
            null
        }

        val releaseInfo = tvEnrichment?.releaseInfo ?: tmdbEnrichment?.releaseInfo
        val localReleaseInfo = tvEnrichment?.let(::formatTvdbDetailLocalReleaseInfo)
        val rating = tvEnrichment?.rating ?: tmdbEnrichment?.rating
        val ratingSource = when {
            tvEnrichment?.rating != null -> tvEnrichment.ratingSource.orDefault()
            tmdbEnrichment?.rating != null -> tmdbEnrichment.ratingSource.orDefault(com.nexio.tv.domain.model.TitleRatingSource.TMDB)
            else -> updated.ratingSource.orDefault()
        }
        val runtimeMinutes = tvEnrichment?.runtimeMinutes ?: tmdbEnrichment?.runtimeMinutes
        val ageRating = tvEnrichment?.ageRating ?: tmdbEnrichment?.ageRating
        val countries = tvEnrichment?.countries ?: tmdbEnrichment?.countries
        val language = tvEnrichment?.language ?: tmdbEnrichment?.language

        // Group: Artwork (logo, backdrop)
        if ((tvEnrichment != null || tmdbEnrichment != null) && settings.useArtwork) {
            updated = updated.copy(
                background = backdrop ?: updated.background,
                logo = logo ?: updated.logo
            )
        }

        // Group: Basic Info (description, genres, rating)
        if ((tvEnrichment != null || tmdbEnrichment != null) && settings.useBasicInfo) {
            updated = updated.copy(
                name = localizedTitle ?: updated.name,
                description = description ?: updated.description
            )
            if (!genres.isNullOrEmpty()) {
                updated = updated.copy(genres = genres)
            }
            updated = updated.copy(
                imdbRating = rating?.toFloat() ?: updated.imdbRating,
                ratingSource = if (rating != null) ratingSource else updated.ratingSource.orDefault()
            )
        }

        // Group: Details (runtime, release info, country, language)
        if ((tvEnrichment != null || tmdbEnrichment != null) && settings.useDetails) {
            updated = updated.copy(
                runtime = runtimeMinutes?.toString() ?: updated.runtime,
                releaseInfo = releaseInfo ?: updated.releaseInfo,
                localReleaseInfo = localReleaseInfo ?: updated.localReleaseInfo,
                ageRating = ageRating ?: updated.ageRating,
                country = countries?.joinToString(", ") ?: updated.country,
                language = language ?: updated.language
            )
        }

        // Group: Credits (cast with photos, director, writer)
        if (tmdbEnrichment != null && settings.useCredits) {
            val peopleCredits = buildList {
                addAll(tmdbEnrichment.directorMembers)
                addAll(tmdbEnrichment.writerMembers)
                addAll(tmdbEnrichment.castMembers)
            }
                .filter { it.name.isNotBlank() }
                .distinctBy { it.tmdbId ?: (it.name.lowercase() + "|" + (it.character ?: "")) }

            if (peopleCredits.isNotEmpty()) {
                updated = updated.copy(
                    castMembers = peopleCredits,
                    cast = tmdbEnrichment.castMembers.takeIf { it.isNotEmpty() }?.map { it.name } ?: updated.cast
                )
            }
            updated = updated.copy(
                director = if (tmdbEnrichment.director.isNotEmpty()) tmdbEnrichment.director else updated.director,
                writer = if (tmdbEnrichment.writer.isNotEmpty()) tmdbEnrichment.writer else updated.writer
            )
        } else if (isKitsuAnimeByProvider && settings.useCredits && kitsuAdvanced?.characters?.isNotEmpty() == true) {
            val animeCharacters = kitsuAdvanced.characters.map { character ->
                com.nexio.tv.domain.model.MetaCastMember(
                    name = character.characterName,
                    character = character.actorName,
                    photo = character.characterImage ?: character.actorImage,
                    tmdbId = null,
                    provider = "kitsu",
                    providerId = character.actorId
                )
            }
            updated = updated.copy(
                castMembers = animeCharacters,
                cast = animeCharacters.map { it.name }
            )
        } else if (tvEnrichment != null && settings.useCredits) {
            if (tvEnrichment.castMembers.isNotEmpty()) {
                updated = updated.copy(
                    castMembers = tvEnrichment.castMembers,
                    cast = tvEnrichment.castMembers.map { it.name }
                )
            }
        }

        // Group: Productions
        if (tmdbEnrichment != null && settings.useProductions && tmdbEnrichment.productionCompanies.isNotEmpty()) {
            updated = updated.copy(productionCompanies = tmdbEnrichment.productionCompanies)
        } else if (isKitsuAnimeByProvider && settings.useProductions && kitsuAdvanced?.productionCompanies?.isNotEmpty() == true) {
            updated = updated.copy(
                productionCompanies = kitsuAdvanced.productionCompanies.map { company ->
                    com.nexio.tv.domain.model.MetaCompany(
                        tmdbId = null,
                        name = company.producerName,
                        kind = com.nexio.tv.domain.model.MetaCompanyKind.COMPANY,
                        provider = "kitsu",
                        providerId = company.producerId
                    )
                }
            )
        } else if (tvEnrichment != null && settings.useProductions && tvEnrichment.productionCompanies.isNotEmpty()) {
            updated = updated.copy(productionCompanies = tvEnrichment.productionCompanies)
        }

        // Group: Networks
        if (tmdbEnrichment != null && settings.useNetworks && tmdbEnrichment.networks.isNotEmpty()) {
            updated = updated.copy(networks = tmdbEnrichment.networks)
        } else if (tvEnrichment != null && settings.useNetworks && tvEnrichment.networks.isNotEmpty()) {
            updated = updated.copy(networks = tvEnrichment.networks)
        }

        // Group: TVDB season-order context
        if (tvEnrichment?.seasonOrderContext != null) {
            updated = updated.copy(tvdbSeasonOrderContext = tvEnrichment.seasonOrderContext)
        }

        // Group: Episodes (titles, overviews, thumbnails, runtime)
        if (includeEpisodeMetadata) {
            updated = applyTvEpisodeEnrichment(
                targetMeta = updated,
                tvEnrichment = tvEnrichment,
                tmdbContentType = tmdbContentType,
                tvdbLanguage = tvdbLanguage,
                settings = settings,
                isTvContent = isTvContent
            )
        }

        updated = titleRatingOverrideRepository.enrichMeta(
            meta = updated,
            fallbackItemId = itemId,
            fallbackItemType = itemType
        )

        if (tmdbEnrichment?.collectionId != null) {
            loadCollectionAsync(tmdbEnrichment.collectionId, tmdbEnrichment.collectionName, settings)
        }
        val animeRelated = kitsuAdvanced?.relatedTitles
            .orEmpty()
            .map { item ->
                com.nexio.tv.domain.model.MetaPreview(
                    id = "kitsu:${item.mediaId}",
                    type = ContentType.SERIES,
                    rawType = "anime",
                    name = item.title,
                    poster = item.poster,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = item.synopsis,
                    releaseInfo = item.releaseInfo?.take(4),
                    imdbRating = null,
                    genres = emptyList()
                )
            }

        return result(updated).copy(animeRelated = animeRelated)
    }

    private fun hydrateKitsuNavigationTargetsAsync(meta: Meta) {
        kitsuBridgeHydrationJob?.cancel()

        val actorNamesNeedingIds = meta.castMembers
            .filter { it.provider.equals("kitsu", ignoreCase = true) && it.tmdbId == null }
            .mapNotNull { it.character?.trim()?.takeIf { name -> name.isNotBlank() } }
            .distinct()
        val companyNamesNeedingIds = meta.productionCompanies
            .filter { it.provider.equals("kitsu", ignoreCase = true) && it.tmdbId == null }
            .map { it.name.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (actorNamesNeedingIds.isEmpty() && companyNamesNeedingIds.isEmpty()) return

        val expectedMetaId = meta.id
        kitsuBridgeHydrationJob = viewModelScope.launch {
            val tmdbPersonIdsByActorName = coroutineScope {
                actorNamesNeedingIds.associateWith { actorName ->
                    async {
                        metadataRouterFacade.findPersonIdByExactName(
                            metadataRequest = MetadataRequest(
                                contentId = "tmdb:person:$actorName",
                                contentType = ContentType.MOVIE,
                                sourceContext = MetadataSourceContext(),
                                language = currentTvdbLanguageTag(),
                                depth = MetadataDepth.DETAIL_SECONDARY
                            ),
                            name = actorName
                        )
                    }
                }.mapValues { (_, deferred) -> deferred.await() }
            }
            val tmdbCompanyIdsByName = coroutineScope {
                companyNamesNeedingIds.associateWith { companyName ->
                    async {
                        metadataRouterFacade.findCompanyIdByExactName(
                            metadataRequest = MetadataRequest(
                                contentId = "tmdb:company:$companyName",
                                contentType = ContentType.MOVIE,
                                sourceContext = MetadataSourceContext(),
                                language = currentTvdbLanguageTag(),
                                depth = MetadataDepth.DETAIL_SECONDARY
                            ),
                            name = companyName
                        )
                    }
                }.mapValues { (_, deferred) -> deferred.await() }
            }

            _uiState.update { state ->
                val currentMeta = state.meta ?: return@update state
                if (currentMeta.id != expectedMetaId) return@update state

                val updatedCastMembers = currentMeta.castMembers.map { member ->
                    val actorName = member.character?.trim()
                    val tmdbId = actorName?.let { tmdbPersonIdsByActorName[it] }
                    if (tmdbId == null || member.tmdbId == tmdbId) {
                        member
                    } else {
                        member.copy(tmdbId = tmdbId)
                    }
                }
                val updatedProductionCompanies = currentMeta.productionCompanies.map { company ->
                    val tmdbId = tmdbCompanyIdsByName[company.name.trim()]
                    if (tmdbId == null || company.tmdbId == tmdbId) {
                        company
                    } else {
                        company.copy(tmdbId = tmdbId)
                    }
                }
                if (
                    updatedCastMembers == currentMeta.castMembers &&
                    updatedProductionCompanies == currentMeta.productionCompanies
                ) {
                    state
                } else {
                    state.copy(
                        meta = currentMeta.copy(
                            castMembers = updatedCastMembers,
                            productionCompanies = updatedProductionCompanies
                        ),
                        episodesForSeason = buildEpisodesForSeason(
                            currentMeta.videos,
                            state.selectedSeason
                        )
                    )
                }
            }
        }
    }

    private suspend fun expandAnimeAddonSeasons(meta: Meta): Meta {
        val addonBaseUrl = preferredAddonBaseUrl?.takeIf { it.isNotBlank() } ?: return meta
        if (itemType.lowercase() == "movie") return meta
        if (AnimeStremioId.parse(meta.id) == null) return meta

        val linkedTargets = extractAddonSeasonTargets(meta.links)
        if (linkedTargets.isEmpty()) return meta

        val visitedIds = mutableSetOf(meta.id)
        val pending = ArrayDeque(linkedTargets)
        val siblingMetas = mutableListOf<Meta>()

        while (pending.isNotEmpty()) {
            val target = pending.removeFirst()
            if (!visitedIds.add(target.itemId)) continue
            val siblingMeta = fetchPreferredAddonMeta(
                addonBaseUrl = addonBaseUrl,
                itemType = target.itemType,
                itemId = target.itemId
            ) ?: continue
            siblingMetas += siblingMeta
            extractAddonSeasonTargets(siblingMeta.links)
                .filterNot { it.itemId in visitedIds }
                .forEach { pending.addLast(it) }
        }

        if (siblingMetas.isEmpty()) return meta

        val mergedVideos = (listOf(meta) + siblingMetas)
            .flatMap { it.videos }
            .distinctBy { video ->
                val season = video.season ?: -1
                val episode = video.episode ?: -1
                "$season:$episode:${video.id}"
            }
            .sortedWith(compareBy<Video>({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }, { it.title }))

        return meta.copy(videos = mergedVideos)
    }

    private suspend fun fetchPreferredAddonMeta(
        addonBaseUrl: String,
        itemType: String,
        itemId: String
    ): Meta? {
        return when (
            val result = metaRepository.getMeta(
                addonBaseUrl = addonBaseUrl,
                type = itemType,
                id = itemId,
                cacheOnDisk = shouldCacheDetailMetaOnDisk,
                origin = detailMetaOrigin
            ).first { it !is NetworkResult.Loading }
        ) {
            is NetworkResult.Success -> result.data
            else -> null
        }
    }

    private fun extractAddonSeasonTargets(links: List<com.nexio.tv.domain.model.MetaLink>): List<LinkedAddonDetailTarget> {
        return links.asSequence()
            .filter { it.category.equals("Franchise", ignoreCase = true) }
            .filter {
                it.name.startsWith("Prequel:", ignoreCase = true) ||
                    it.name.startsWith("Sequel:", ignoreCase = true)
            }
            .mapNotNull { parseAddonDetailTarget(it.url) }
            .filter { it.itemType.equals("series", ignoreCase = true) }
            .toList()
    }

    private fun parseAddonDetailTarget(url: String): LinkedAddonDetailTarget? {
        val detailMarker = "detail/"
        val detailIndex = url.indexOf(detailMarker)
        if (detailIndex < 0) return null
        val tail = url.substring(detailIndex + detailMarker.length)
        val itemType = tail.substringBefore('/').trim()
        val itemId = tail.substringAfter('/', missingDelimiterValue = "").substringBefore('?').trim()
        if (itemType.isBlank() || itemId.isBlank()) return null
        return LinkedAddonDetailTarget(
            itemType = itemType,
            itemId = itemId
        )
    }

    private data class LinkedAddonDetailTarget(
        val itemType: String,
        val itemId: String
    )

    private suspend fun applyTvEpisodeEnrichment(
        targetMeta: Meta,
        tvEnrichment: TvMetadataEnrichment?,
        tmdbContentType: ContentType,
        tvdbLanguage: String,
        settings: TmdbSettings,
        isTvContent: Boolean
    ): Meta {
        if (!settings.useEpisodes || !isTvContent) return targetMeta

        val seasonNumbers = targetMeta.videos.mapNotNull { it.season }.distinct()

        val episodeDecision = metadataRouterFacade.fetchTvEpisodeEnrichment(
            metadataRequest = MetadataRequest(
                contentId = targetMeta.id,
                contentType = tmdbContentType,
                sourceContext = MetadataSourceContext(itemType = tmdbContentType.toApiString()),
                language = tvdbLanguage,
                seasonNumber = seasonNumbers.firstOrNull(),
                depth = MetadataDepth.SEASON
            ),
            tvRequest = TvMetadataRequest(
                contentId = targetMeta.id,
                fallbackContentId = itemId,
                contentType = tmdbContentType,
                language = tvdbLanguage,
                seasonNumbers = seasonNumbers
            )
        )
        val episodeMap = episodeDecision.value.orEmpty()
        if (episodeMap.isEmpty()) return targetMeta

        // When the meta arrived with no pre-existing episode structure (e.g. from the canonical
        // router path which returns videos=emptyList()), build video stubs from the episode map
        // so that the enrichment results (runtimes, thumbnails, etc.) are not silently discarded.
        if (targetMeta.videos.isEmpty()) {
            return targetMeta.copy(
                videos = buildKitsuEpisodeVideos(
                    seriesId = targetMeta.id,
                    episodeLabel = context.getString(R.string.episodes_episode),
                    episodeMap = episodeMap
                )
            )
        }

        return targetMeta.copy(
            videos = targetMeta.videos.map { video ->
                val season = video.season
                val episode = video.episode
                val key = if (season != null && episode != null) season to episode else null
                val ep = key?.let { episodeMap[it] }

                video.copy(
                    title = ep?.title ?: video.title,
                    overview = ep?.overview ?: video.overview,
                    released = ep?.airDate ?: video.released,
                    localReleaseInfo = if (tvEnrichment != null && ep?.airDate != null) {
                        formatTvdbEpisodeLocalReleaseInfo(ep.airDate, tvEnrichment) ?: video.localReleaseInfo
                    } else {
                        video.localReleaseInfo
                    },
                    thumbnail = ep?.thumbnail ?: video.thumbnail,
                    runtime = ep?.runtimeMinutes ?: video.runtime,
                    tvdbEpisodeOrder = ep?.tvdbEpisodeOrder ?: video.tvdbEpisodeOrder
                )
            }
        )
    }

    private fun shouldSupplementTvdbDetailWithTmdb(
        tvEnrichment: TvMetadataEnrichment?,
        settings: TmdbSettings
    ): Boolean {
        if (tvEnrichment == null) return false
        if (settings.useCredits && tvEnrichment.castMembers.any { it.tmdbId == null }) return true
        if (settings.useProductions && tvEnrichment.productionCompanies.any { it.tmdbId == null || it.logo == null }) return true
        if (settings.useNetworks && tvEnrichment.networks.any { it.tmdbId == null || it.logo == null }) return true
        return false
    }

    private fun currentTvdbLanguageTag(): String {
        return TvdbLanguageMapper.normalize(profileBoundary.currentLanguageTag()).code
    }

    private fun ContentType.toAnimeMediaKind(): ContentMediaKind =
        if (this == ContentType.MOVIE) ContentMediaKind.MOVIE else ContentMediaKind.SERIES

    private fun resolveTmdbContentType(meta: Meta): ContentType {
        val fromRoute = parseDetailApiTypeToContentType(itemType)
        if (fromRoute != null) return fromRoute

        val fromMetaApi = parseDetailApiTypeToContentType(meta.apiType)
        if (fromMetaApi != null) return fromMetaApi

        return when (meta.type) {
            ContentType.SERIES, ContentType.TV -> ContentType.SERIES
            ContentType.MOVIE -> ContentType.MOVIE
            else -> ContentType.MOVIE
        }
    }

    private fun selectSeason(season: Int) {
        trailerFetchJob?.cancel()
        _uiState.update {
            it.withManualSeasonSelection(season).copy(
                trailerUrl = null,
                trailerAudioUrl = null,
                trailerExternalUrl = null,
                pendingExternalTrailerUrl = null,
                trailerResolutionStatus = TrailerResolutionStatus.IDLE,
                isTrailerLoading = false,
                isTrailerPlaying = false,
                showTrailerControls = false,
                hideLogoDuringTrailer = false
            )
        }
        trailerHasPlayed = false
        isPlayButtonFocused = false
        preloadSeasonMediaAvailability(season)
    }

    private fun playSeasonTrailer(season: Int) {
        val previousState = _uiState.value
        prepareSeasonMediaTakeover(season)
        trailerHasPlayed = false
        viewModelScope.launch {
            val availability = ensureSeasonMediaAvailability(season)
            if (!availability.hasTrailerOrTeaser) {
                _uiState.update { state ->
                    state.withFailedSeasonMediaPlaybackAttempt(
                        season = season,
                        availability = availability,
                        previousTrailerUrl = previousState.trailerUrl,
                        previousTrailerAudioUrl = previousState.trailerAudioUrl,
                        previousTrailerExternalUrl = previousState.trailerExternalUrl
                    )
                }
                return@launch
            }
            fetchSeasonTrailer(playWhenReady = true)
        }
    }

    private fun playSeasonRecap(season: Int) {
        val previousState = _uiState.value
        prepareSeasonMediaTakeover(season)
        trailerHasPlayed = false
        viewModelScope.launch {
            val availability = ensureSeasonMediaAvailability(season)
            if (!availability.hasRecap) {
                _uiState.update { state ->
                    state.withFailedSeasonMediaPlaybackAttempt(
                        season = season,
                        availability = availability,
                        previousTrailerUrl = previousState.trailerUrl,
                        previousTrailerAudioUrl = previousState.trailerAudioUrl,
                        previousTrailerExternalUrl = previousState.trailerExternalUrl
                    )
                }
                return@launch
            }
            fetchSeasonRecap(playWhenReady = true)
        }
    }

    private fun prepareSeasonMediaTakeover(season: Int) {
        trailerFetchJob?.cancel()
        _uiState.update { state ->
            val updatedState = state.withManualSeasonSelection(season)
            val cachedSeasonAvailability = updatedState.seasonMediaAvailabilityBySeason[season]
            updatedState.copy(
                pendingExternalTrailerUrl = null,
                trailerResolutionStatus = TrailerResolutionStatus.RESOLVING,
                isTrailerLoading = true,
                trailerUrl = null,
                trailerAudioUrl = null,
                trailerExternalUrl = null,
                isTrailerPlaying = false,
                showTrailerControls = false,
                hideLogoDuringTrailer = false,
                selectedSeasonHasPlayableTrailerMedia = cachedSeasonAvailability?.hasTrailerOrTeaser == true,
                selectedSeasonHasPlayableRecap = cachedSeasonAvailability?.hasRecap == true
            )
        }
    }

    private fun preloadTitleTrailerAvailability(meta: Meta) {
        viewModelScope.launch {
            val isTvContent = parseDetailApiTypeToContentType(meta.apiType) == ContentType.SERIES
            val tmdbId = if (isTvContent) null else runCatching {
                tmdbService.ensureTmdbId(meta.id, meta.apiType) ?: tmdbService.ensureTmdbId(itemId, itemType)
            }.getOrElse {
                if (it is CancellationException) throw it
                null
            }
            debugLog(
                TAG,
                "preloadTitleTrailerAvailability start itemId=${meta.id} type=${meta.apiType} tmdbId=$tmdbId isTvContent=$isTvContent fallbackYtIds=${meta.trailerYtIds.count { it.isNotBlank() }}"
            )
            val available = trailerService.getTitleMediaAvailability(
                tmdbId = tmdbId,
                type = meta.apiType,
                contentId = meta.id,
                fallbackYtIds = meta.trailerYtIds
            )
            _uiState.update { state ->
                state.copy(
                    titleHasPlayableTrailerMedia = available,
                    trailerResolutionStatus = when {
                        state.trailerResolutionStatus == TrailerResolutionStatus.RESOLVING ->
                            TrailerResolutionStatus.RESOLVING
                        !state.trailerUrl.isNullOrBlank() || !state.trailerExternalUrl.isNullOrBlank() ->
                            TrailerResolutionStatus.READY
                        available -> TrailerResolutionStatus.IDLE
                        else -> TrailerResolutionStatus.FAILED
                    }
                )
            }
            val state = _uiState.value
            debugLog(
                TAG,
                "preloadTitleTrailerAvailability result itemId=${meta.id} available=$available resolutionStatus=${state.trailerResolutionStatus} trailerUrl=${!state.trailerUrl.isNullOrBlank()} trailerExternalUrl=${!state.trailerExternalUrl.isNullOrBlank()}"
            )
        }
    }

    private fun preloadAllSeasonMediaAvailability(meta: Meta) {
        if (parseDetailApiTypeToContentType(meta.apiType) != ContentType.SERIES) return
        meta.videos
            .mapNotNull { it.season }
            .distinct()
            .sorted()
            .forEach(::preloadSeasonMediaAvailability)
    }

    private fun preloadSeasonMediaAvailability(season: Int, forceRefresh: Boolean = false) {
        val meta = _uiState.value.meta ?: return
        if (parseDetailApiTypeToContentType(meta.apiType) != ContentType.SERIES) return
        if (!forceRefresh && _uiState.value.seasonMediaAvailabilityBySeason.containsKey(season)) return
        if (!loadingSeasonAvailability.add(season)) return

        viewModelScope.launch {
            try {
                val tmdbId = null // TV content: let TrailerService try TVDB first
                val seasonAvailability = trailerService.getSeasonMediaAvailability(
                    tmdbId = tmdbId,
                    type = meta.apiType,
                    seasonNumber = season,
                    contentId = meta.id
                )
                _uiState.update { state ->
                    state.withSeasonMediaAvailability(
                        season,
                        SeasonMediaActionAvailability(
                            hasTrailerOrTeaser = seasonAvailability.hasTrailerOrTeaser,
                            hasRecap = seasonAvailability.hasRecap
                        )
                    )
                }
            } finally {
                loadingSeasonAvailability.remove(season)
            }
        }
    }

    private suspend fun ensureSeasonMediaAvailability(
        season: Int,
        forceRefresh: Boolean = false
    ): SeasonMediaActionAvailability {
        val meta = _uiState.value.meta ?: return SeasonMediaActionAvailability()
        if (parseDetailApiTypeToContentType(meta.apiType) != ContentType.SERIES) {
            return SeasonMediaActionAvailability()
        }

        val cached = _uiState.value.seasonMediaAvailabilityBySeason[season]
        if (!forceRefresh && cached != null) {
            return cached
        }

        val tmdbId = null // TV content: let TrailerService try TVDB first
        val seasonAvailability = trailerService.getSeasonMediaAvailability(
            tmdbId = tmdbId,
            type = meta.apiType,
            seasonNumber = season,
            contentId = meta.id
        )
        val resolvedAvailability = SeasonMediaActionAvailability(
            hasTrailerOrTeaser = seasonAvailability.hasTrailerOrTeaser,
            hasRecap = seasonAvailability.hasRecap
        )
        _uiState.update { state ->
            state.withSeasonMediaAvailability(season, resolvedAvailability)
        }
        return resolvedAvailability
    }

    internal fun setSelectedSeasonProgrammatically(season: Int) {
        _uiState.update { it.withProgrammaticSeasonSelection(season) }
    }

    private fun calculateNextToWatch() {
        val meta = _uiState.value.meta ?: return
        val progressMap = buildEpisodeProgressForNavigation(
            meta = meta,
            rawProgressMap = _uiState.value.episodeProgressMap,
            overrides = _uiState.value.episodeWatchOverrides
        )
        val isSeries = isSeriesDetailMeta(meta)
        nextToWatchJob?.cancel()

        nextToWatchJob = viewModelScope.launch {
            if (!isSeries) {
                // For movies, check if there's an in-progress watch
                val progress = watchProgressRepository.getProgress(itemId).firstOrNull()
                val nextToWatch = if (progress != null && shouldResumeProgress(progress)) {
                    NextToWatch(
                        watchProgress = progress,
                        isResume = true,
                        nextVideoId = meta.id,
                        nextSeason = null,
                        nextEpisode = null,
                        displayText = context.getString(R.string.detail_btn_resume)
                    )
                } else {
                    NextToWatch(
                        watchProgress = null,
                        isResume = false,
                        nextVideoId = meta.id,
                        nextSeason = null,
                        nextEpisode = null,
                        displayText = context.getString(R.string.detail_btn_play)
                    )
                }
                updateNextToWatch(nextToWatch)
                return@launch
            }

            val nextToWatch = buildSeriesNextToWatchCandidate(
                episodes = meta.videos,
                progressMap = progressMap,
                metaId = meta.id
            ).toNextToWatch(
                hasWatchedSomething = progressMap.isNotEmpty()
            )

            updateNextToWatch(nextToWatch)
        }
    }

    private fun SeriesNextToWatchCandidate.toNextToWatch(
        hasWatchedSomething: Boolean
    ): NextToWatch {
        val displayText = when {
            watchProgress != null && isResume && nextSeason != null && nextEpisode != null -> {
                context.getString(R.string.detail_btn_resume_episode, nextSeason, nextEpisode)
            }
            watchProgress != null && isResume -> {
                context.getString(R.string.detail_btn_resume)
            }
            nextSeason != null && nextEpisode != null && hasWatchedSomething -> {
                context.getString(R.string.detail_btn_next_episode, nextSeason, nextEpisode)
            }
            nextSeason != null && nextEpisode != null -> {
                context.getString(R.string.detail_btn_play_episode, nextSeason, nextEpisode)
            }
            watchProgress != null -> {
                context.getString(R.string.detail_btn_resume)
            }
            else -> {
                context.getString(R.string.detail_btn_play)
            }
        }

        return NextToWatch(
            watchProgress = watchProgress,
            isResume = isResume,
            nextVideoId = nextVideoId,
            nextSeason = nextSeason,
            nextEpisode = nextEpisode,
            displayText = displayText
        )
    }

    private fun shouldResumeProgress(progress: WatchProgress): Boolean {
        if (progress.isCompleted()) return false
        if (progress.progressPercentage >= 0.02f) return true

        val hasStartedPlayback = progress.position > 0L ||
            progress.progressPercent?.let { it > 0f } == true
        return hasStartedPlayback &&
            progress.source != WatchProgress.SOURCE_TRAKT_HISTORY &&
            progress.source != WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
    }

    private fun toggleLibrary() {
        val meta = _uiState.value.meta ?: return
        val progressContentId = effectiveProgressContentId(meta)
        viewModelScope.launch {
            val input = meta.toLibraryEntryInput()
            val wasInWatchlist = _uiState.value.isInWatchlist
            val wasInLibrary = _uiState.value.isInLibrary
            runCatching {
                libraryRepository.toggleDefault(input)
                val message = if (_uiState.value.librarySourceMode == LibrarySourceMode.TRAKT || _uiState.value.librarySourceMode == LibrarySourceMode.SIMKL) {
                    when (_uiState.value.librarySourceMode) {
                        LibrarySourceMode.TRAKT ->
                            if (wasInWatchlist) "Removed from Trakt Watchlist" else "Added to Trakt Watchlist"
                        LibrarySourceMode.SIMKL ->
                            if (wasInWatchlist) "Removed from SIMKL Watchlist" else "Added to SIMKL Watchlist"
                        else -> if (wasInWatchlist) "Removed from watchlist" else "Added to watchlist"
                    }
                } else {
                    if (wasInLibrary) context.getString(R.string.detail_removed_from_library) else context.getString(R.string.detail_added_to_library)
                }
                showMessage(message)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                showMessage(
                    message = error.message ?: "Failed to update library",
                    isError = true
                )
            }
        }
    }

    private fun openListPicker() {
        if (_uiState.value.librarySourceMode == LibrarySourceMode.LOCAL || _uiState.value.librarySourceMode == LibrarySourceMode.DEBRID) return
        val meta = _uiState.value.meta ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pickerPending = true, pickerError = null) }
            runCatching {
                val snapshot = libraryRepository.getMembershipSnapshot(meta.toLibraryEntryInput())
                _uiState.update {
                    it.copy(
                        showListPicker = true,
                        pickerMembership = snapshot.listMembership,
                        pickerPending = false,
                        pickerError = null
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        pickerPending = false,
                        pickerError = error.message ?: "Failed to load lists",
                        showListPicker = false
                    )
                }
                showMessage(error.message ?: "Failed to load lists", isError = true)
            }
        }
    }

    private fun togglePickerMembership(listKey: String) {
        val current = _uiState.value.pickerMembership[listKey] == true
        _uiState.update {
            it.copy(
                pickerMembership = it.pickerMembership.toMutableMap().apply {
                    this[listKey] = !current
                },
                pickerError = null
            )
        }
    }

    private fun savePickerMembership() {
        if (_uiState.value.pickerPending) return
        if (_uiState.value.librarySourceMode == LibrarySourceMode.LOCAL || _uiState.value.librarySourceMode == LibrarySourceMode.DEBRID) return
        val meta = _uiState.value.meta ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(pickerPending = true, pickerError = null) }
            runCatching {
                libraryRepository.applyMembershipChanges(
                    item = meta.toLibraryEntryInput(),
                    changes = ListMembershipChanges(
                        desiredMembership = _uiState.value.pickerMembership
                    )
                )
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        pickerPending = false,
                        showListPicker = false,
                        pickerError = null
                    )
                }
                showMessage(context.getString(R.string.detail_lists_updated))
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        pickerPending = false,
                        pickerError = error.message ?: "Failed to update lists"
                    )
                }
                showMessage(error.message ?: "Failed to update lists", isError = true)
            }
        }
    }

    private fun dismissListPicker() {
        _uiState.update {
            it.copy(
                showListPicker = false,
                pickerPending = false,
                pickerError = null
            )
        }
    }

    private fun toggleMovieWatched() {
        val meta = _uiState.value.meta ?: return
        if (meta.apiType != "movie") return
        if (_uiState.value.isMovieWatchedPending) return
        val progressContentId = effectiveProgressContentId(meta)

        viewModelScope.launch {
            _uiState.update { it.copy(isMovieWatchedPending = true) }
            runCatching {
                if (_uiState.value.isMovieWatched) {
                    watchProgressRepository.removeFromHistory(progressContentId)
                    showMessage(context.getString(R.string.detail_movie_marked_unwatched))
                } else {
                    watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(meta, progressContentId))
                    showMessage(context.getString(R.string.detail_movie_marked_watched))
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                showMessage(
                    message = error.message ?: "Failed to update watched status",
                    isError = true
                )
            }
            _uiState.update { it.copy(isMovieWatchedPending = false) }
        }
    }

    private fun toggleEpisodeWatched(video: Video) {
        val meta = _uiState.value.meta ?: return
        val season = video.season ?: return
        val episode = video.episode ?: return
        val pendingKey = episodePendingKey(video)
        if (_uiState.value.episodeWatchedPendingKeys.contains(pendingKey)) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKey)
            }

            val episodeKey = season to episode
            val isWatched = _uiState.value.isEpisodeWatched(season, episode)
            val progressContentId = effectiveProgressContentId(meta)
            applyEpisodeWatchOverride(setOf(episodeKey), watched = !isWatched)
            runCatching {
                if (isWatched) {
                    watchProgressRepository.removeFromHistory(progressContentId, season, episode)
                    showMessage(context.getString(R.string.detail_episode_marked_unwatched))
                } else {
                    watchProgressRepository.markAsCompleted(buildCompletedEpisodeProgress(meta, video, progressContentId))
                    showMessage(context.getString(R.string.detail_episode_marked_watched))
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                showMessage(
                    message = error.message ?: "Failed to update episode watched status",
                    isError = true
                )
                clearEpisodeWatchOverride(setOf(episodeKey))
            }

            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - pendingKey)
            }
        }
    }

    fun isSeasonFullyWatched(season: Int): Boolean {
        val state = _uiState.value
        val meta = state.meta ?: return false
        val episodes = meta.videos.filter { it.season == season && it.episode != null }
        if (episodes.isEmpty()) return false
        return episodes.all { video ->
            val s = video.season ?: return@all false
            val e = video.episode ?: return@all false
            state.isEpisodeWatched(s, e)
        }
    }

    private fun markSeasonWatched(season: Int) {
        val meta = _uiState.value.meta ?: return
        viewModelScope.launch {
            try {
                val nowMs = System.currentTimeMillis()
                val tmdbContentType = resolveTmdbContentType(meta)
                val isTvContent = tmdbContentType == ContentType.SERIES || tmdbContentType == ContentType.TV
                val seasonEpisodes = if (isTvContent) {
                    metadataRouterFacade.fetchTvSeasonEpisodes(
                        metadataRequest = MetadataRequest(
                            contentId = meta.id,
                            contentType = tmdbContentType,
                            sourceContext = MetadataSourceContext(itemType = tmdbContentType.toApiString()),
                            language = null,
                            seasonNumber = season,
                            depth = MetadataDepth.SEASON
                        ),
                        contentId = meta.id,
                        fallbackContentId = itemId,
                        seasonNumber = season,
                        language = null
                    ).value.orEmpty()
                        .filter { episode -> AirDateGate.isAired(0L, episode.airDate, nowMs) }
                        .map { episode ->
                            com.nexio.tv.domain.model.SeasonEpisodeMark(
                                episodeNumber = episode.episodeNumber,
                                airDate = episode.airDate
                            )
                        }
                } else {
                    val tmdbIdStr = tmdbService.ensureTmdbId(meta.id, tmdbContentType.toApiString())
                    val tvId = tmdbIdStr?.toIntOrNull()
                        ?: parseContentIds(meta.id).tmdb
                        ?: run {
                            showMessage(
                                message = context.getString(R.string.detail_marked_episodes_watched, 0),
                                isError = true
                            )
                            return@launch
                        }
                    metadataSecondaryRepository.fetchSeasonEpisodes(tvId, season, null)
                        .filter { ep -> AirDateGate.isAired(0L, ep.airDate, nowMs) }
                        .map { ep ->
                            com.nexio.tv.domain.model.SeasonEpisodeMark(
                                episodeNumber = ep.episodeNumber,
                                airDate = ep.airDate
                            )
                        }
                }
                if (seasonEpisodes.isEmpty()) {
                    showMessage(context.getString(R.string.detail_all_episodes_watched))
                    return@launch
                }
                val episodeKeys = seasonEpisodes.mapNotNull { mark ->
                    val episodeNumber = mark.episodeNumber ?: return@mapNotNull null
                    season to episodeNumber
                }.toSet()
                if (episodeKeys.isEmpty()) {
                    showMessage(context.getString(R.string.detail_all_episodes_watched))
                    return@launch
                }
                val pendingKeys = meta.videos
                    .filter { video ->
                        val episodeNumber = video.episode ?: return@filter false
                        video.season == season && episodeKeys.contains(season to episodeNumber)
                    }
                    .map(::episodePendingKey)
                    .toSet()
                _uiState.update {
                    it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKeys)
                }
                applyEpisodeWatchOverride(episodeKeys, watched = true)
                watchProgressRepository.markAsCompletedBatch(meta, season, seasonEpisodes)
                showMessage(context.getString(R.string.detail_marked_episodes_watched, seasonEpisodes.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val episodeKeys = _uiState.value.episodeWatchOverrides
                    .filterKeys { it.first == season }
                    .filterValues { watched -> watched }
                    .keys
                clearEpisodeWatchOverride(episodeKeys)
                Log.w(TAG, "markSeasonWatched failed for season=$season: ${e.message}")
                showMessage(
                    message = e.message ?: context.getString(R.string.detail_marked_episodes_watched, 0),
                    isError = true
                )
            } finally {
                _uiState.update { state ->
                    state.copy(
                        episodeWatchedPendingKeys = state.episodeWatchedPendingKeys.filterNot { key ->
                            meta.videos.any { video ->
                                video.season == season && episodePendingKey(video) == key
                            }
                        }.toSet()
                    )
                }
            }
        }
    }

    private fun markSeasonUnwatched(season: Int) {
        val meta = _uiState.value.meta ?: return
        val progressContentId = effectiveProgressContentId(meta)
        viewModelScope.launch {
            val episodes = meta.videos.filter { it.season == season && it.episode != null }
            val watched = episodes.filter { video ->
                val s = video.season!!
                val e = video.episode!!
                _uiState.value.isEpisodeWatched(s, e)
            }
            if (watched.isEmpty()) {
                showMessage(context.getString(R.string.detail_no_watched_episodes))
                return@launch
            }

            val episodeKeys = watched.map { it.season!! to it.episode!! }.toSet()
            val pendingKeys = watched.map { episodePendingKey(it) }.toSet()
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKeys)
            }
            applyEpisodeWatchOverride(episodeKeys, watched = false)

            var unmarked = 0
            for (video in watched) {
                val key = episodePendingKey(video)
                val seasonNumber = video.season ?: continue
                val episodeNumber = video.episode ?: continue
                val episodeKey = seasonNumber to episodeNumber
                runCatching {
                    watchProgressRepository.removeFromHistory(progressContentId, seasonNumber, episodeNumber)
                    unmarked++
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Failed to unmark S${video.season}E${video.episode}: ${error.message}")
                    clearEpisodeWatchOverride(setOf(episodeKey))
                }
                _uiState.update {
                    it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - key)
                }
            }

            showMessage(context.getString(R.string.detail_marked_episodes_unwatched, unmarked))
        }
    }

    private fun markPreviousEpisodesWatched(video: Video) {
        val meta = _uiState.value.meta ?: return
        val targetSeason = video.season ?: return
        val targetEpisode = video.episode ?: return
        val progressContentId = effectiveProgressContentId(meta)

        viewModelScope.launch {
            val previous = meta.videos.filter { v ->
                v.season == targetSeason && v.episode != null && v.episode < targetEpisode
            }
            val unwatched = previous.filter { v ->
                val s = v.season!!
                val e = v.episode!!
                !_uiState.value.isEpisodeWatched(s, e)
            }
            if (unwatched.isEmpty()) {
                showMessage(context.getString(R.string.detail_all_previous_watched))
                return@launch
            }

            val episodeKeys = unwatched.map { it.season!! to it.episode!! }.toSet()
            val pendingKeys = unwatched.map { episodePendingKey(it) }.toSet()
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKeys)
            }
            applyEpisodeWatchOverride(episodeKeys, watched = true)

            var marked = 0
            for (ep in unwatched) {
                val key = episodePendingKey(ep)
                val episodeKey = ep.season!! to ep.episode!!
                runCatching {
                    watchProgressRepository.markAsCompleted(buildCompletedEpisodeProgress(meta, ep, progressContentId))
                    marked++
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Failed to mark S${ep.season}E${ep.episode} as watched: ${error.message}")
                    clearEpisodeWatchOverride(setOf(episodeKey))
                }
                _uiState.update {
                    it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - key)
                }
            }

            showMessage(context.getString(R.string.detail_marked_previous_watched, marked))
        }
    }

    private fun effectiveProgressContentId(meta: Meta? = _uiState.value.meta): String {
        return meta?.id?.takeIf { it.isNotBlank() } ?: effectiveContentId.value.takeIf { it.isNotBlank() } ?: itemId
    }

    private fun buildCompletedMovieProgress(
        meta: Meta,
        contentId: String = effectiveProgressContentId(meta)
    ): WatchProgress {
        return WatchProgress(
            contentId = contentId,
            contentType = meta.apiType,
            name = meta.name,
            poster = meta.poster,
            backdrop = meta.background,
            logo = meta.logo,
            videoId = meta.id,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 1L,
            duration = 1L,
            lastWatched = System.currentTimeMillis(),
            progressPercent = 100f
        )
    }

    private fun buildCompletedEpisodeProgress(
        meta: Meta,
        video: Video,
        contentId: String = effectiveProgressContentId(meta)
    ): WatchProgress {
        val runtimeMs = video.runtime?.toLong()?.times(60_000L) ?: 1L
        return WatchProgress(
            contentId = contentId,
            contentType = meta.apiType,
            name = meta.name,
            poster = meta.poster,
            backdrop = video.thumbnail ?: meta.background,
            logo = meta.logo,
            videoId = video.id,
            season = video.season,
            episode = video.episode,
            episodeTitle = video.title,
            position = runtimeMs,
            duration = runtimeMs,
            lastWatched = System.currentTimeMillis(),
            progressPercent = 100f
        )
    }

    private fun episodePendingKey(video: Video): String {
        return "${video.id}:${video.season ?: -1}:${video.episode ?: -1}"
    }

    private fun showMessage(message: String, isError: Boolean = false) {
        _uiState.update { state ->
            if (state.userMessage == message && state.userMessageIsError == isError) {
                state
            } else {
                state.copy(
                    userMessage = message,
                    userMessageIsError = isError
                )
            }
        }
    }

    private fun clearMessage() {
        _uiState.update { state ->
            if (state.userMessage == null && !state.userMessageIsError) {
                state
            } else {
                state.copy(userMessage = null, userMessageIsError = false)
            }
        }
    }

    private fun extractImdbId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        val normalized = rawId.trim()
        return if (normalized.startsWith("tt", ignoreCase = true)) {
            normalized.substringBefore(':')
        } else {
            null
        }
    }

    private fun Meta.toLibraryEntryInput(): LibraryEntryInput {
        val year = Regex("(\\d{4})").find(releaseInfo ?: "")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val parsedIds = parseContentIds(id)
        return LibraryEntryInput(
            itemId = id,
            itemType = apiType,
            title = name,
            year = year,
            traktId = parsedIds.trakt,
            imdbId = parsedIds.imdb,
            tmdbId = parsedIds.tmdb,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            addonBaseUrl = preferredAddonBaseUrl
        )
    }

    fun getNextEpisodeInfo(): String? {
        val nextToWatch = _uiState.value.nextToWatch
        return nextToWatch?.displayText
    }

    private fun fetchTrailerUrl(playWhenReady: Boolean = false, useSelectedSeasonForSeries: Boolean = true) {
        val meta = _uiState.value.meta ?: return

        trailerFetchJob?.cancel()
        trailerFetchJob = viewModelScope.launch {
            val selectedSeason = _uiState.value.selectedSeason
            enterTrailerResolvingState(selectedSeason)

            val year = meta.releaseInfo
                ?.takeIf { it.isNotBlank() }
                ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value }

            val isTvContent = parseDetailApiTypeToContentType(meta.apiType) == ContentType.SERIES
            val tmdbId = if (isTvContent) null else runCatching {
                tmdbService.ensureTmdbId(meta.id, meta.apiType) ?: tmdbService.ensureTmdbId(itemId, itemType)
            }.getOrElse {
                if (it is CancellationException) throw it
                null
            }

            val trailerContentType = resolveTmdbContentType(meta)
            val trailerResult = metadataRouterFacade.fetchTrailer(
                metadataRequest = MetadataRequest(
                    contentId = if (!tmdbId.isNullOrBlank()) "tmdb:$tmdbId" else meta.id,
                    contentType = trailerContentType,
                    sourceContext = MetadataSourceContext(itemType = trailerContentType.toApiString()),
                    language = currentTvdbLanguageTag(),
                    depth = MetadataDepth.DETAIL_MEDIA
                ),
                title = meta.name,
                year = year,
                tmdbId = tmdbId,
                type = meta.apiType,
                seasonNumber = if (
                    useSelectedSeasonForSeries &&
                    parseDetailApiTypeToContentType(meta.apiType) == ContentType.SERIES
                ) {
                    selectedSeason
                } else {
                    null
                },
                contentId = meta.id,
                fallbackYtIds = meta.trailerYtIds
            )

            _uiState.update { state ->
                val baseState = state

                when (trailerResult) {
                    is TrailerResolutionResult.Playback -> baseState.copy(
                        trailerUrl = trailerResult.source.videoUrl,
                        trailerAudioUrl = trailerResult.source.audioUrl,
                        trailerExternalUrl = null,
                        trailerResolutionStatus = TrailerResolutionStatus.READY,
                        isTrailerLoading = false,
                        selectedSeasonHasPlayableTrailerMedia = if (parseDetailApiTypeToContentType(meta.apiType) == ContentType.SERIES) {
                            state.seasonMediaAvailabilityBySeason[selectedSeason]?.hasTrailerOrTeaser == true
                        } else {
                            baseState.titleHasPlayableTrailerMedia
                        }
                    )

                    is TrailerResolutionResult.External -> baseState.copy(
                        trailerUrl = null,
                        trailerAudioUrl = null,
                        trailerExternalUrl = trailerResult.url,
                        pendingExternalTrailerUrl = if (playWhenReady) trailerResult.url else null,
                        trailerResolutionStatus = TrailerResolutionStatus.READY,
                        isTrailerLoading = false
                    )

                    null -> baseState.copy(
                        trailerUrl = null,
                        trailerAudioUrl = null,
                        trailerExternalUrl = null,
                        trailerResolutionStatus = TrailerResolutionStatus.FAILED,
                        isTrailerLoading = false
                    )
                }
            }

            if (playWhenReady && trailerResult is TrailerResolutionResult.Playback) {
                setTrailerPlaybackState(
                    isPlaying = true,
                    showControls = true,
                    hideLogo = true
                )
            } else if (playWhenReady && trailerResult is TrailerResolutionResult.External) {
                trailerHasPlayed = true
            }

        }
    }

    private fun fetchSeasonRecap(playWhenReady: Boolean = false) {
        val meta = _uiState.value.meta ?: return
        if (parseDetailApiTypeToContentType(meta.apiType) != ContentType.SERIES) return

        trailerFetchJob?.cancel()
        trailerFetchJob = viewModelScope.launch {
            val previousState = _uiState.value
            val selectedSeason = _uiState.value.selectedSeason
            _uiState.update { state ->
                val cachedSeasonAvailability = state.seasonMediaAvailabilityBySeason[selectedSeason]
                state.copy(
                    trailerResolutionStatus = TrailerResolutionStatus.RESOLVING,
                    isTrailerLoading = true,
                    trailerUrl = null,
                    trailerAudioUrl = null,
                    trailerExternalUrl = null,
                    pendingExternalTrailerUrl = null,
                    selectedSeasonHasPlayableTrailerMedia = cachedSeasonAvailability?.hasTrailerOrTeaser == true,
                    selectedSeasonHasPlayableRecap = cachedSeasonAvailability?.hasRecap == true
                )
            }

            val year = meta.releaseInfo
                ?.takeIf { it.isNotBlank() }
                ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value }
            val tmdbId = null // TV content: let TrailerService try TVDB first
            // F2-TM-01: route through facade so metadata.route_decision and
            // metadata.resolver_schedule trace events fire for the recap path.
            val recapSource = metadataRouterFacade.fetchSeasonRecap(
                metadataRequest = MetadataRequest(
                    contentId = meta.id,
                    contentType = ContentType.SERIES,
                    sourceContext = MetadataSourceContext(itemType = meta.apiType),
                    language = currentTvdbLanguageTag(),
                    depth = MetadataDepth.DETAIL_MEDIA
                ),
                title = meta.name,
                year = year,
                tmdbId = tmdbId,
                type = meta.apiType,
                seasonNumber = selectedSeason,
                contentId = meta.id
            )

            _uiState.update { state ->
                val availability = SeasonMediaActionAvailability(
                    hasTrailerOrTeaser = state.seasonMediaAvailabilityBySeason[selectedSeason]?.hasTrailerOrTeaser == true,
                    hasRecap = recapSource != null
                )
                if (recapSource == null && playWhenReady) {
                    state.withFailedSeasonMediaPlaybackAttempt(
                        season = selectedSeason,
                        availability = availability,
                        previousTrailerUrl = previousState.trailerUrl,
                        previousTrailerAudioUrl = previousState.trailerAudioUrl,
                        previousTrailerExternalUrl = previousState.trailerExternalUrl
                    )
                } else {
                    state.withSeasonMediaAvailability(
                        selectedSeason,
                        availability
                    ).copy(
                        trailerUrl = recapSource?.videoUrl,
                        trailerAudioUrl = recapSource?.audioUrl,
                        trailerExternalUrl = null,
                        trailerResolutionStatus = if (recapSource != null) TrailerResolutionStatus.READY else TrailerResolutionStatus.FAILED,
                        isTrailerLoading = false
                    )
                }
            }

            if (playWhenReady && recapSource != null) {
                setTrailerPlaybackState(
                    isPlaying = true,
                    showControls = true,
                    hideLogo = true
                )
            } else if (playWhenReady) {
                setTrailerPlaybackState(
                    isPlaying = false,
                    showControls = false,
                    hideLogo = false
                )
            }
        }
    }

    private fun fetchSeasonTrailer(playWhenReady: Boolean = false) {
        val meta = _uiState.value.meta ?: return
        if (parseDetailApiTypeToContentType(meta.apiType) != ContentType.SERIES) return

        trailerFetchJob?.cancel()
        trailerFetchJob = viewModelScope.launch {
            val previousState = _uiState.value
            val selectedSeason = _uiState.value.selectedSeason
            _uiState.update { state ->
                val cachedSeasonAvailability = state.seasonMediaAvailabilityBySeason[selectedSeason]
                state.copy(
                    trailerResolutionStatus = TrailerResolutionStatus.RESOLVING,
                    isTrailerLoading = true,
                    trailerUrl = null,
                    trailerAudioUrl = null,
                    trailerExternalUrl = null,
                    pendingExternalTrailerUrl = null,
                    selectedSeasonHasPlayableTrailerMedia = cachedSeasonAvailability?.hasTrailerOrTeaser == true,
                    selectedSeasonHasPlayableRecap = cachedSeasonAvailability?.hasRecap == true
                )
            }

            val year = meta.releaseInfo
                ?.takeIf { it.isNotBlank() }
                ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value }
            val tmdbId = null // TV content: let TrailerService try TVDB first
            // F2-TM-01: route through facade so metadata.route_decision and
            // metadata.resolver_schedule trace events fire for the season-trailer path.
            val seasonTrailerSource = metadataRouterFacade.fetchSeasonTrailer(
                metadataRequest = MetadataRequest(
                    contentId = meta.id,
                    contentType = ContentType.SERIES,
                    sourceContext = MetadataSourceContext(itemType = meta.apiType),
                    language = currentTvdbLanguageTag(),
                    depth = MetadataDepth.DETAIL_MEDIA
                ),
                title = meta.name,
                year = year,
                tmdbId = tmdbId,
                type = meta.apiType,
                seasonNumber = selectedSeason,
                contentId = meta.id
            )

            _uiState.update { state ->
                val availability = SeasonMediaActionAvailability(
                    hasTrailerOrTeaser = seasonTrailerSource != null,
                    hasRecap = state.seasonMediaAvailabilityBySeason[selectedSeason]?.hasRecap == true
                )
                if (seasonTrailerSource == null && playWhenReady) {
                    state.withFailedSeasonMediaPlaybackAttempt(
                        season = selectedSeason,
                        availability = availability,
                        previousTrailerUrl = previousState.trailerUrl,
                        previousTrailerAudioUrl = previousState.trailerAudioUrl,
                        previousTrailerExternalUrl = previousState.trailerExternalUrl
                    )
                } else {
                    state.withSeasonMediaAvailability(
                        selectedSeason,
                        availability
                    ).copy(
                        trailerUrl = seasonTrailerSource?.videoUrl,
                        trailerAudioUrl = seasonTrailerSource?.audioUrl,
                        trailerExternalUrl = null,
                        trailerResolutionStatus = if (seasonTrailerSource != null) TrailerResolutionStatus.READY else TrailerResolutionStatus.FAILED,
                        isTrailerLoading = false
                    )
                }
            }

            if (playWhenReady && seasonTrailerSource != null) {
                setTrailerPlaybackState(
                    isPlaying = true,
                    showControls = true,
                    hideLogo = true
                )
            } else if (playWhenReady) {
                setTrailerPlaybackState(
                    isPlaying = false,
                    showControls = false,
                    hideLogo = false
                )
            }
        }
    }

    private fun startIdleTimer() {
        idleTimerJob?.cancel()
    }

    private fun handlePlayButtonFocused() {
        debugLog(
            TAG,
            "handlePlayButtonFocused alreadyFocused=$isPlayButtonFocused trailerPlaying=${_uiState.value.isTrailerPlaying} showControls=${_uiState.value.showTrailerControls}"
        )
        if (isPlayButtonFocused) return
        isPlayButtonFocused = true
        startIdleTimer()
    }

    private fun handleUserInteraction() {
        val state = _uiState.value
        val shouldStopAutoTrailer = state.isTrailerPlaying && !state.showTrailerControls
        val hasActiveIdleTimer = idleTimerJob?.isActive == true
        if (!isPlayButtonFocused && !hasActiveIdleTimer && !shouldStopAutoTrailer) {
            return
        }

        idleTimerJob?.cancel()
        isPlayButtonFocused = false

        if (shouldStopAutoTrailer) {
            trailerHasPlayed = true
            setTrailerPlaybackState(
                isPlaying = false,
                showControls = false,
                hideLogo = false
            )
        }
    }

    private fun handleTrailerButtonClick() {
        val state = _uiState.value
        idleTimerJob?.cancel()
        isPlayButtonFocused = false
        debugLog(
            TAG,
            "handleTrailerButtonClick titleAvailable=${state.titleHasPlayableTrailerMedia} loading=${state.isTrailerLoading} trailerUrl=${!state.trailerUrl.isNullOrBlank()} trailerExternalUrl=${!state.trailerExternalUrl.isNullOrBlank()}"
        )

        when {
            !state.trailerUrl.isNullOrBlank() -> {
                setTrailerPlaybackState(
                    isPlaying = true,
                    showControls = true,
                    hideLogo = true
                )
            }

            !state.trailerExternalUrl.isNullOrBlank() -> {
                trailerHasPlayed = true
                _uiState.update {
                    it.copy(pendingExternalTrailerUrl = state.trailerExternalUrl)
                }
            }

            state.titleHasPlayableTrailerMedia && !state.isTrailerLoading -> {
                debugLog(TAG, "handleTrailerButtonClick starting trailer resolution")
                enterTrailerResolvingState(state.selectedSeason)
                fetchTrailerUrl(playWhenReady = true, useSelectedSeasonForSeries = false)
            }
        }
    }

    private fun handleTrailerEnded() {
        debugLog(
            TAG,
            "handleTrailerEnded trailerPlaying=${_uiState.value.isTrailerPlaying} showControls=${_uiState.value.showTrailerControls}"
        )
        trailerHasPlayed = true
        isPlayButtonFocused = false
        setTrailerPlaybackState(
            isPlaying = false,
            showControls = false,
            hideLogo = false
        )
    }

    private fun handleLifecyclePause() {
        idleTimerJob?.cancel()
        isPlayButtonFocused = false
        val state = _uiState.value
        if (!shouldStopAutoTrailerOnLifecyclePause(state.isTrailerPlaying, state.showTrailerControls)) {
            return
        }
        trailerHasPlayed = true
        setTrailerPlaybackState(
            isPlaying = false,
            showControls = false,
            hideLogo = false
        )
    }

    private fun consumePendingExternalTrailer() {
        _uiState.update { state ->
            if (state.pendingExternalTrailerUrl == null) {
                state
            } else {
                state.copy(pendingExternalTrailerUrl = null)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        idleTimerJob?.cancel()
        trailerFetchJob?.cancel()
        reviewsJob?.cancel()
        nextToWatchJob?.cancel()
    }

    private fun clearEpisodeProgress(video: Video) {
        val season = video.season ?: return
        val episode = video.episode ?: return
        val pendingKey = episodePendingKey(video)
        if (_uiState.value.episodeWatchedPendingKeys.contains(pendingKey)) return

        viewModelScope.launch {
            val progressContentId = effectiveProgressContentId()
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys + pendingKey)
            }
            // Capture the actual resume entry (keyed by contentId+season+episode, not video.id)
            // so rollback restores the same instance that was removed.
            val captured = continueWatchingSnapshotService.currentRawResumeItems()
                .firstOrNull { it.contentId == progressContentId && it.season == season && it.episode == episode }
            // Only optimistically remove if there's actually an entry to remove.
            if (captured != null) {
                continueWatchingSnapshotService.removeResumeEntry(captured.videoId)
            }
            val episodeKey = season to episode
            applyEpisodeWatchOverride(setOf(episodeKey), watched = false)
            runCatching {
                watchProgressRepository.removeFromHistory(progressContentId, season, episode)
                showMessage(context.getString(R.string.cw_action_clear_progress))
                continueWatchingSnapshotService.ensureFresh(force = true)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                // Roll back the optimistic removal on Trakt failure.
                if (captured != null) {
                    continueWatchingSnapshotService.reinsertResumeEntry(captured)
                }
                showMessage(
                    message = error.message ?: "Failed to clear episode progress",
                    isError = true
                )
                clearEpisodeWatchOverride(setOf(episodeKey))
            }
            _uiState.update {
                it.copy(episodeWatchedPendingKeys = it.episodeWatchedPendingKeys - pendingKey)
            }
        }
    }

    private fun MetaDetailsUiState.withEpisodeProgressSnapshot(
        rawProgressMap: Map<Pair<Int, Int>, WatchProgress>
    ): MetaDetailsUiState {
        val overrides = reconcileEpisodeWatchOverrides(
            rawWatchedSet = rawCompletedEpisodeSet(rawProgressMap),
            overrides = episodeWatchOverrides
        )
        val effectiveWatched = applyEpisodeWatchOverrides(
            rawWatchedSet = rawCompletedEpisodeSet(rawProgressMap),
            overrides = overrides
        )
        return copy(
            episodeProgressMap = rawProgressMap,
            watchedEpisodes = effectiveWatched,
            episodeWatchOverrides = overrides
        )
    }

    private fun MetaDetailsUiState.isEpisodeWatched(season: Int, episode: Int): Boolean {
        episodeWatchOverrides[season to episode]?.let { return it }
        return episodeProgressMap[season to episode]?.isCompleted() == true ||
            watchedEpisodes.contains(season to episode)
    }

    private fun applyEpisodeWatchOverride(
        episodeKeys: Set<Pair<Int, Int>>,
        watched: Boolean
    ) {
        _uiState.update { state ->
            val overrides = state.episodeWatchOverrides.toMutableMap()
            episodeKeys.forEach { overrides[it] = watched }
            val reconciled = reconcileEpisodeWatchOverrides(
                rawWatchedSet = rawCompletedEpisodeSet(state.episodeProgressMap),
                overrides = overrides
            )
            state.copy(
                episodeWatchOverrides = reconciled,
                watchedEpisodes = applyEpisodeWatchOverrides(
                    rawWatchedSet = rawCompletedEpisodeSet(state.episodeProgressMap),
                    overrides = reconciled
                )
            )
        }
    }

    private fun clearEpisodeWatchOverride(episodeKeys: Set<Pair<Int, Int>>) {
        _uiState.update { state ->
            if (episodeKeys.none { it in state.episodeWatchOverrides }) {
                return@update state
            }
            val overrides = state.episodeWatchOverrides.toMutableMap()
            episodeKeys.forEach(overrides::remove)
            val reconciled = reconcileEpisodeWatchOverrides(
                rawWatchedSet = rawCompletedEpisodeSet(state.episodeProgressMap),
                overrides = overrides
            )
            state.copy(
                episodeWatchOverrides = reconciled,
                watchedEpisodes = applyEpisodeWatchOverrides(
                    rawWatchedSet = rawCompletedEpisodeSet(state.episodeProgressMap),
                    overrides = reconciled
                )
            )
        }
    }

    private fun rawCompletedEpisodeSet(
        progressMap: Map<Pair<Int, Int>, WatchProgress>
    ): Set<Pair<Int, Int>> {
        return progressMap
            .filterValues { it.isCompleted() }
            .keys
    }

    private fun reconcileEpisodeWatchOverrides(
        rawWatchedSet: Set<Pair<Int, Int>>,
        overrides: Map<Pair<Int, Int>, Boolean>
    ): Map<Pair<Int, Int>, Boolean> {
        return overrides.filter { (key, watched) ->
            rawWatchedSet.contains(key) != watched
        }
    }

    private fun applyEpisodeWatchOverrides(
        rawWatchedSet: Set<Pair<Int, Int>>,
        overrides: Map<Pair<Int, Int>, Boolean>
    ): Set<Pair<Int, Int>> {
        val effective = rawWatchedSet.toMutableSet()
        overrides.forEach { (key, watched) ->
            if (watched) {
                effective.add(key)
            } else {
                effective.remove(key)
            }
        }
        return effective.toSet()
    }

    private fun buildEpisodeProgressForNavigation(
        meta: Meta,
        rawProgressMap: Map<Pair<Int, Int>, WatchProgress>,
        overrides: Map<Pair<Int, Int>, Boolean>
    ): Map<Pair<Int, Int>, WatchProgress> {
        if (overrides.isEmpty()) return rawProgressMap
        val effective = rawProgressMap.toMutableMap()
        overrides.forEach { (episodeKey, watched) ->
            if (!watched) {
                effective.remove(episodeKey)
                return@forEach
            }
            val season = episodeKey.first
            val episode = episodeKey.second
            val current = effective[episodeKey]
            if (current?.isCompleted() == true) return@forEach
            val video = meta.videos.firstOrNull { it.season == season && it.episode == episode } ?: return@forEach
            effective[episodeKey] = buildCompletedEpisodeProgress(meta, video)
        }
        return effective
    }

    private fun checkInEpisode(video: Video) {
        val season = video.season ?: return
        val episode = video.episode ?: return
        val meta = _uiState.value.meta ?: return
        val progressContentId = effectiveProgressContentId(meta)
        if (progressContentId.isBlank()) {
            showMessage(message = context.getString(R.string.error_missing_tracking_ids), isError = true)
            return
        }

        viewModelScope.launch {
            runCatching {
                // ARCHITECTURE: checkin runs in ambient-profile context — there's no playback session
                // to bind a specific owner profile. ownerProfileId reflects the active profile at
                // invocation time. ownerSessionId is not available here; the boundary check in the
                // scrobble layer falls back to the current active session. (F2-H-03)
                trackingScrobbleService.checkin(
                    TrackingScrobbleItem.Episode(
                        contentId = progressContentId,
                        showTitle = meta.name,
                        showYear = null,
                        season = season,
                        number = episode,
                        episodeTitle = video.title
                    ),
                    ownerProfileId = profileBoundary.activeContext.value?.profileId
                )
            }.onSuccess { success ->
                if (success) {
                    showMessage(context.getString(R.string.cw_action_check_in))
                } else {
                    showMessage(message = context.getString(R.string.error_tracking_check_in_failed), isError = true)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                showMessage(
                    message = error.message ?: context.getString(R.string.error_tracking_check_in_failed),
                    isError = true
                )
            }
        }
    }
}

/**
 * Converts a [MetadataResolutionResult] from [MetadataRouterFacade.resolveRequest] to the legacy
 * [Meta] shape expected by [MetaDetailsViewModel.applyMetaWithEnrichment].
 *
 * cast / videos / director / writer etc. are left empty because [applyMetaWithEnrichment]
 * calls [enrichMeta] which chains [fetchTvEnrichment] / [fetchTmdbEnrichment] /
 * [fetchTvEpisodeEnrichment] after this returns — those enrich the missing fields.
 */
private fun MetadataResolutionResult.toMeta(contentId: String, contentType: ContentType): Meta {
    val doc = resolvedDocument
    val display = displayMetadata
    return Meta(
        id = doc.canonicalId ?: contentId,
        type = contentType,
        rawType = contentType.toApiString(),
        name = doc.title ?: display.title.orEmpty(),
        poster = doc.poster ?: display.poster,
        posterShape = PosterShape.POSTER,
        background = doc.backdrop ?: display.backdrop,
        logo = doc.logo ?: display.logo,
        description = doc.overview ?: display.description,
        releaseInfo = display.releaseInfo,
        imdbRating = (doc.rating as? Number)?.toFloat() ?: display.imdbRating,
        ratingSource = display.ratingSource,
        genres = display.genres,
        runtime = doc.runtimeMinutes?.let { "${it}m" } ?: display.runtime,
        director = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        country = null,
        awards = null,
        language = null,
        links = emptyList(),
        posterProviderTag = display.posterProviderTag
    )
}

internal fun parseDetailApiTypeToContentType(apiType: String?): ContentType? {
    val normalized = apiType?.trim()?.lowercase().orEmpty()
    return when (normalized) {
        "movie", "film" -> ContentType.MOVIE
        "series", "tv", "show", "tvshow", "anime" -> ContentType.SERIES
        else -> null
    }
}

internal fun isSeriesDetailMeta(meta: Meta): Boolean {
    if (meta.type == ContentType.MOVIE) return false
    if (meta.type == ContentType.SERIES || meta.type == ContentType.TV) return true
    if (parseDetailApiTypeToContentType(meta.apiType) == ContentType.SERIES) return true
    return meta.videos.any { video -> video.season != null && video.episode != null }
}

internal fun buildKitsuEpisodeVideos(
    seriesId: String,
    episodeLabel: String,
    episodeMap: Map<Pair<Int, Int>, TvEpisodeMetadata>
): List<Video> {
    return episodeMap.entries
        .sortedWith(
            compareBy<Map.Entry<Pair<Int, Int>, TvEpisodeMetadata>> { it.key.first }
                .thenBy { it.key.second }
        )
        .map { (key, episode) ->
            val seasonNumber = episode.seasonNumber ?: key.first
            val episodeNumber = episode.episodeNumber ?: key.second
            Video(
                id = "$seriesId:$seasonNumber:$episodeNumber",
                title = episode.title ?: "$episodeLabel $episodeNumber",
                released = episode.airDate,
                thumbnail = episode.thumbnail,
                streams = emptyList(),
                season = seasonNumber,
                episode = episodeNumber,
                overview = episode.overview,
                runtime = episode.runtimeMinutes,
                tvdbEpisodeOrder = episode.tvdbEpisodeOrder
            )
        }
}
