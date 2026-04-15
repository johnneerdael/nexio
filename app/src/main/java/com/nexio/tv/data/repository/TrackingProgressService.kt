package com.nexio.tv.data.repository

import com.nexio.tv.core.tvdb.TvdbAirAvailabilityDiagnosticReason
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityPrecision
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.data.repository.trakt.TraktEpisodeRef
import com.nexio.tv.domain.model.TrackingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class TrackingNextUpEntry(
    val contentId: String,
    val contentType: String = "series",
    val name: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val videoId: String,
    val firstAired: String?,
    val firstAiredMs: Long,
    val activityAtMs: Long,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val traktShowId: Int? = null,
    val traktEpisodeId: Int? = null,
    val tvdbAvailabilityInstantMs: Long? = null,
    val tvdbAvailabilityPrecision: TvdbAirAvailabilityPrecision = TvdbAirAvailabilityPrecision.UNKNOWN,
    val tvdbAvailabilitySourceZoneId: String? = null,
    val tvdbAvailabilitySourcePolicy: String? = null,
    val tvdbAvailabilityDiagnosticReason: TvdbAirAvailabilityDiagnosticReason? = null,
    val tvdbAvailabilityDeviceLocalDateTime: String? = null
)

interface TrackingProgressService {
    fun observeAllProgress(): Flow<List<WatchProgress>>
    fun observeRemoteSnapshotLoaded(): Flow<Boolean>
    fun observeContinueWatchingNextUp(): Flow<List<TrackingNextUpEntry>>
    fun observeSyntheticContinueWatchingNextUp(): Flow<List<TrackingNextUpEntry>>
    fun observeEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>>
    fun observeMovieWatched(contentId: String): Flow<Boolean>
    fun applyOptimisticProgress(progress: WatchProgress)
    fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?)
    fun clearOptimistic()
    fun invalidateLocalizedMetadata()
    suspend fun refreshNow()
    suspend fun resolvePlaybackDeleteIdsForOutbox(
        contentId: String,
        season: Int?,
        episode: Int?
    ): List<Long>
    suspend fun resolveSeasonEpisodeTraktIds(
        showContentId: String,
        season: Int,
        episodeNumbers: List<Int>
    ): Map<Int, TraktEpisodeRef>
    suspend fun rollbackQueuedHistoryRemove(
        contentId: String,
        season: Int?,
        episode: Int?,
        removeShow: Boolean
    )
    suspend fun rollbackQueuedPlaybackDelete(
        contentId: String,
        season: Int?,
        episode: Int?,
        clearShow: Boolean
    )
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTrackingProgressService @Inject constructor(
    private val traktProgressService: TraktProgressService,
    private val simklProgressService: SimklProgressService,
    private val trackingProviderStateService: TrackingProviderStateService,
    private val tvdbContinueWatchingTimingEnricher: TvdbContinueWatchingTimingEnricher = TvdbContinueWatchingTimingEnricher()
) : TrackingProgressService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var currentProvider: TrackingProvider = TrackingProvider.TRAKT

    init {
        scope.launch {
            trackingProviderStateService.state.collect { state ->
                currentProvider = state.effectiveProvider
            }
        }
    }

    override fun observeAllProgress(): Flow<List<WatchProgress>> =
        trackingProviderStateService.state.flatMapLatest { state ->
            when (state.effectiveProvider) {
                TrackingProvider.SIMKL -> simklProgressService.observeAllProgress()
                TrackingProvider.TRAKT -> traktProgressService.observeAllProgress()
            }
        }

    override fun observeRemoteSnapshotLoaded(): Flow<Boolean> =
        trackingProviderStateService.state.flatMapLatest { state ->
            when (state.effectiveProvider) {
                TrackingProvider.SIMKL -> simklProgressService.observeRemoteSnapshotLoaded()
                TrackingProvider.TRAKT -> traktProgressService.observeRemoteSnapshotLoaded()
            }
        }

    override fun observeContinueWatchingNextUp(): Flow<List<TrackingNextUpEntry>> =
        trackingProviderStateService.state.flatMapLatest { state ->
            when (state.effectiveProvider) {
                TrackingProvider.SIMKL -> simklProgressService.observeContinueWatchingNextUp()
                    .mapLatest { items -> tvdbContinueWatchingTimingEnricher.enrich(items) }
                TrackingProvider.TRAKT -> traktProgressService.observeContinueWatchingNextUp()
                    .mapLatest { items ->
                        tvdbContinueWatchingTimingEnricher.enrich(
                            items.map(TraktProgressService.NextUpEntry::toTrackingNextUpEntry)
                        )
                    }
            }
        }

    override fun observeSyntheticContinueWatchingNextUp(): Flow<List<TrackingNextUpEntry>> =
        trackingProviderStateService.state.flatMapLatest { state ->
            when (state.effectiveProvider) {
                TrackingProvider.SIMKL -> simklProgressService.observeSyntheticContinueWatchingNextUp()
                    .mapLatest { items -> tvdbContinueWatchingTimingEnricher.enrich(items) }
                TrackingProvider.TRAKT -> traktProgressService.observeSyntheticContinueWatchingNextUp()
                    .mapLatest { items ->
                        tvdbContinueWatchingTimingEnricher.enrich(
                            items.map(TraktProgressService.NextUpEntry::toTrackingNextUpEntry)
                        )
                    }
            }
        }

    override fun observeEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        trackingProviderStateService.state.flatMapLatest { state ->
            when (state.effectiveProvider) {
                TrackingProvider.SIMKL -> simklProgressService.observeEpisodeProgress(contentId)
                TrackingProvider.TRAKT -> traktProgressService.observeEpisodeProgress(contentId)
            }
        }

    override fun observeMovieWatched(contentId: String): Flow<Boolean> =
        trackingProviderStateService.state.flatMapLatest { state ->
            when (state.effectiveProvider) {
                TrackingProvider.SIMKL -> simklProgressService.observeMovieWatched(contentId)
                TrackingProvider.TRAKT -> traktProgressService.observeMovieWatched(contentId)
            }
        }

    override fun applyOptimisticProgress(progress: WatchProgress) {
        when (currentProvider) {
            TrackingProvider.SIMKL -> simklProgressService.applyOptimisticProgress(progress)
            TrackingProvider.TRAKT -> traktProgressService.applyOptimisticProgress(progress)
        }
    }

    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) {
        when (currentProvider) {
            TrackingProvider.SIMKL -> simklProgressService.applyOptimisticRemoval(contentId, season, episode)
            TrackingProvider.TRAKT -> traktProgressService.applyOptimisticRemoval(contentId, season, episode)
        }
    }

    override fun clearOptimistic() {
        when (currentProvider) {
            TrackingProvider.SIMKL -> simklProgressService.clearOptimistic()
            TrackingProvider.TRAKT -> traktProgressService.clearOptimistic()
        }
    }

    override fun invalidateLocalizedMetadata() {
        when (currentProvider) {
            TrackingProvider.SIMKL -> simklProgressService.invalidateLocalizedMetadata()
            TrackingProvider.TRAKT -> traktProgressService.invalidateLocalizedMetadata()
        }
    }

    override suspend fun refreshNow() {
        when (currentProvider) {
            TrackingProvider.SIMKL -> simklProgressService.refreshNow()
            TrackingProvider.TRAKT -> traktProgressService.refreshNow()
        }
    }

    override suspend fun resolvePlaybackDeleteIdsForOutbox(
        contentId: String,
        season: Int?,
        episode: Int?
    ): List<Long> = when (currentProvider) {
        TrackingProvider.SIMKL -> simklProgressService.resolvePlaybackDeleteIdsForOutbox(
            contentId = contentId,
            season = season,
            episode = episode
        )
        TrackingProvider.TRAKT -> traktProgressService.resolvePlaybackDeleteIdsForOutbox(
            contentId = contentId,
            season = season,
            episode = episode
        )
    }

    override suspend fun resolveSeasonEpisodeTraktIds(
        showContentId: String,
        season: Int,
        episodeNumbers: List<Int>
    ): Map<Int, TraktEpisodeRef> = traktProgressService.resolveSeasonEpisodeTraktIds(
        showContentId = showContentId,
        season = season,
        episodeNumbers = episodeNumbers
    )

    override suspend fun rollbackQueuedHistoryRemove(
        contentId: String,
        season: Int?,
        episode: Int?,
        removeShow: Boolean
    ) {
        when (currentProvider) {
            TrackingProvider.SIMKL -> simklProgressService.rollbackQueuedHistoryRemove(
                contentId = contentId,
                season = season,
                episode = episode,
                removeShow = removeShow
            )
            TrackingProvider.TRAKT -> traktProgressService.rollbackQueuedHistoryRemove(
                contentId = contentId,
                season = season,
                episode = episode,
                removeShow = removeShow
            )
        }
    }

    override suspend fun rollbackQueuedPlaybackDelete(
        contentId: String,
        season: Int?,
        episode: Int?,
        clearShow: Boolean
    ) {
        when (currentProvider) {
            TrackingProvider.SIMKL -> simklProgressService.rollbackQueuedPlaybackDelete(
                contentId = contentId,
                season = season,
                episode = episode,
                clearShow = clearShow
            )
            TrackingProvider.TRAKT -> traktProgressService.rollbackQueuedPlaybackDelete(
                contentId = contentId,
                season = season,
                episode = episode,
                clearShow = clearShow
            )
        }
    }
}

fun TraktProgressService.NextUpEntry.toTrackingNextUpEntry(): TrackingNextUpEntry {
    return TrackingNextUpEntry(
        contentId = contentId,
        contentType = contentType,
        name = name,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        videoId = videoId,
        firstAired = firstAired,
        firstAiredMs = firstAiredMs,
        activityAtMs = activityAtMs,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        traktShowId = traktShowId,
        traktEpisodeId = traktEpisodeId
    )
}
