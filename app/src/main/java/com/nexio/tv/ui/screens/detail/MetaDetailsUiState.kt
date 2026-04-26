package com.nexio.tv.ui.screens.detail
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.NextToWatch
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.MDBListRatings

data class MetaDetailsUiState(
    val isLoading: Boolean = true,
    val meta: Meta? = null,
    val error: String? = null,
    val selectedSeason: Int = 1,
    val manualSeasonOverrideActive: Boolean = false,
    val seasons: List<Int> = emptyList(),
    val episodesForSeason: List<Video> = emptyList(),
    val isInLibrary: Boolean = false,
    val nextToWatch: NextToWatch? = null,
    val episodeProgressMap: Map<Pair<Int, Int>, WatchProgress> = emptyMap(),
    val trailerUrl: String? = null,
    val trailerAudioUrl: String? = null,
    val trailerUserAgent: String? = null,
    val trailerExternalUrl: String? = null,
    val pendingExternalTrailerUrl: String? = null,
    val titleHasPlayableTrailerMedia: Boolean = false,
    val trailerResolutionStatus: TrailerResolutionStatus = TrailerResolutionStatus.IDLE,
    val isTrailerPlaying: Boolean = false,
    val isTrailerLoading: Boolean = false,
    val showTrailerControls: Boolean = false,
    val hideLogoDuringTrailer: Boolean = false,
    val trailerButtonEnabled: Boolean = false,
    val selectedSeasonHasPlayableTrailerMedia: Boolean = false,
    val selectedSeasonHasPlayableRecap: Boolean = false,
    val seasonMediaAvailabilityBySeason: Map<Int, SeasonMediaActionAvailability> = emptyMap(),
    val librarySourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val libraryListTabs: List<LibraryListTab> = emptyList(),
    val isInWatchlist: Boolean = false,
    val showListPicker: Boolean = false,
    val pickerMembership: Map<String, Boolean> = emptyMap(),
    val pickerPending: Boolean = false,
    val pickerError: String? = null,
    val isMovieWatched: Boolean = false,
    val isMovieWatchedPending: Boolean = false,
    val watchedEpisodes: Set<Pair<Int, Int>> = emptySet(),
    val episodeWatchOverrides: Map<Pair<Int, Int>, Boolean> = emptyMap(),
    val episodeWatchedPendingKeys: Set<String> = emptySet(),
    val blurUnwatchedEpisodes: Boolean = false,
    val isAnimeDetail: Boolean = false,
    val relatedItems: List<MetaPreview> = emptyList(),
    val reviews: List<MetaReview> = emptyList(),
    val isReviewsLoading: Boolean = false,
    val reviewsError: String? = null,
    val collection: List<MetaPreview> = emptyList(),
    val collectionName: String? = null,
    val episodeRatings: Map<Pair<Int, Int>, EpisodeRating> = emptyMap(),
    val isEpisodeRatingsLoading: Boolean = false,
    val episodeRatingsError: String? = null,
    val mdbListRatings: MDBListRatings? = null,
    val showMdbListImdb: Boolean = false,
    val deterministicAutoplayEnabled: Boolean = false,
    val installedAddonsCount: Int = 0,
    val universalStreamerModeEnabled: Boolean = false,
    val userMessage: String? = null,
    val userMessageIsError: Boolean = false
)

enum class TrailerResolutionStatus {
    IDLE,
    RESOLVING,
    READY,
    FAILED
}

data class SeasonMediaActionAvailability(
    val hasTrailerOrTeaser: Boolean = false,
    val hasRecap: Boolean = false
)

sealed class MetaDetailsEvent {
    data class OnSeasonSelected(val season: Int) : MetaDetailsEvent()
    data class OnSeasonShortPress(val season: Int) : MetaDetailsEvent()
    data class OnSeasonOptionsOpened(val season: Int) : MetaDetailsEvent()
    data class OnPlaySeasonRecap(val season: Int) : MetaDetailsEvent()
    data class OnEpisodeClick(val video: Video) : MetaDetailsEvent()
    data object OnPlayClick : MetaDetailsEvent()
    data object OnToggleLibrary : MetaDetailsEvent()
    data object OnRetry : MetaDetailsEvent()
    data object OnBackPress : MetaDetailsEvent()
    data object OnUserInteraction : MetaDetailsEvent()
    data object OnPlayButtonFocused : MetaDetailsEvent()
    data object OnTrailerButtonClick : MetaDetailsEvent()
    data object OnTrailerEnded : MetaDetailsEvent()
    data object OnLifecyclePause : MetaDetailsEvent()
    data object OnExternalTrailerConsumed : MetaDetailsEvent()
    data object OnToggleMovieWatched : MetaDetailsEvent()
    data class OnToggleEpisodeWatched(val video: Video) : MetaDetailsEvent()
    data class OnClearEpisodeProgress(val video: Video) : MetaDetailsEvent()
    data class OnCheckInEpisode(val video: Video) : MetaDetailsEvent()
    data class OnMarkSeasonWatched(val season: Int) : MetaDetailsEvent()
    data class OnMarkSeasonUnwatched(val season: Int) : MetaDetailsEvent()
    data class OnMarkPreviousEpisodesWatched(val video: Video) : MetaDetailsEvent()
    data object OnLibraryLongPress : MetaDetailsEvent()
    data class OnPickerMembershipToggled(val listKey: String) : MetaDetailsEvent()
    data object OnPickerSave : MetaDetailsEvent()
    data object OnPickerDismiss : MetaDetailsEvent()
    data object OnClearMessage : MetaDetailsEvent()
    data class OnReviewItemFocused(val index: Int) : MetaDetailsEvent()
}

internal fun MetaDetailsUiState.withManualSeasonSelection(season: Int): MetaDetailsUiState {
    return copy(
        selectedSeason = season,
        episodesForSeason = buildEpisodesForSeason(meta?.videos.orEmpty(), season),
        manualSeasonOverrideActive = true,
        selectedSeasonHasPlayableTrailerMedia = seasonMediaAvailabilityBySeason[season]?.hasTrailerOrTeaser == true,
        selectedSeasonHasPlayableRecap = seasonMediaAvailabilityBySeason[season]?.hasRecap == true
    )
}

internal fun MetaDetailsUiState.withProgrammaticSeasonSelection(season: Int): MetaDetailsUiState {
    return copy(
        selectedSeason = season,
        episodesForSeason = buildEpisodesForSeason(meta?.videos.orEmpty(), season),
        selectedSeasonHasPlayableTrailerMedia = seasonMediaAvailabilityBySeason[season]?.hasTrailerOrTeaser == true,
        selectedSeasonHasPlayableRecap = seasonMediaAvailabilityBySeason[season]?.hasRecap == true
    )
}

internal fun MetaDetailsUiState.withSeasonMediaAvailability(
    season: Int,
    availability: SeasonMediaActionAvailability
): MetaDetailsUiState {
    val updatedAvailability = seasonMediaAvailabilityBySeason + (season to availability)
    return copy(
        seasonMediaAvailabilityBySeason = updatedAvailability,
        selectedSeasonHasPlayableTrailerMedia = if (season == selectedSeason) availability.hasTrailerOrTeaser else selectedSeasonHasPlayableTrailerMedia,
        selectedSeasonHasPlayableRecap = if (season == selectedSeason) availability.hasRecap else selectedSeasonHasPlayableRecap
    )
}

internal fun MetaDetailsUiState.withFailedSeasonMediaPlaybackAttempt(
    season: Int,
    availability: SeasonMediaActionAvailability,
    previousTrailerUrl: String?,
    previousTrailerAudioUrl: String?,
    previousTrailerExternalUrl: String?
): MetaDetailsUiState {
    return withSeasonMediaAvailability(season, availability).copy(
        trailerUrl = previousTrailerUrl,
        trailerAudioUrl = previousTrailerAudioUrl,
        trailerExternalUrl = previousTrailerExternalUrl,
        pendingExternalTrailerUrl = null,
        trailerResolutionStatus = TrailerResolutionStatus.FAILED,
        isTrailerLoading = false,
        isTrailerPlaying = false,
        showTrailerControls = false,
        hideLogoDuringTrailer = false
    )
}

internal fun MetaDetailsUiState.withNextToWatch(nextToWatch: NextToWatch): MetaDetailsUiState {
    val targetSeason = nextToWatch.nextSeason
    val shouldSwitchSeason = shouldAutoSwitchToTargetSeason(
        selectedSeason = selectedSeason,
        targetSeason = targetSeason,
        manualOverrideActive = manualSeasonOverrideActive,
        availableSeasons = seasons
    )

    if (!shouldSwitchSeason || targetSeason == null || meta == null) {
        return copy(nextToWatch = nextToWatch)
    }

    return copy(
        nextToWatch = nextToWatch,
        selectedSeason = targetSeason,
        episodesForSeason = buildEpisodesForSeason(meta.videos, targetSeason)
    )
}

internal fun MetaDetailsUiState.withRefreshedMeta(meta: Meta): MetaDetailsUiState {
    val seasons = meta.videos
        .mapNotNull { it.season }
        .distinct()
        .sorted()
    val selectedSeason = resolveSeasonAfterMetaRefresh(seasons)

    return copy(
        isLoading = false,
        meta = meta,
        seasons = seasons,
        selectedSeason = selectedSeason,
        episodesForSeason = buildEpisodesForSeason(meta.videos, selectedSeason),
        error = null
    )
}

internal fun MetaDetailsUiState.resolveSeasonAfterMetaRefresh(refreshedSeasons: List<Int>): Int {
    val defaultSeason = refreshedSeasons.firstOrNull { it > 0 } ?: refreshedSeasons.firstOrNull() ?: 1
    val preservedSeason = selectedSeason.takeIf { it in refreshedSeasons }
    val ctaTargetSeason = nextToWatch?.nextSeason?.takeIf { it in refreshedSeasons }

    return when {
        manualSeasonOverrideActive && preservedSeason != null -> preservedSeason
        !manualSeasonOverrideActive && ctaTargetSeason != null -> ctaTargetSeason
        else -> defaultSeason
    }
}

internal fun buildEpisodesForSeason(videos: List<Video>, season: Int): List<Video> {
    return videos
        .filter { it.season == season }
        .sortedBy { it.episode }
}
