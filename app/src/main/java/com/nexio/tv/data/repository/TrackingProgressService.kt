// effectiveProvider remains for legacy single-provider next-up surfaces.
// Progress source aggregation and optimistic mutation hooks use activeProviders.
// Plan: docs/superpowers/plans/2026-05-12-scrobble-cw-dual-provider-overhaul.md
@file:Suppress("DEPRECATION")

package com.nexio.tv.data.repository

import com.nexio.tv.core.tvdb.TvdbAirAvailabilityDiagnosticReason
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityPrecision
import com.nexio.tv.data.integration.mdblist.MDBListProgressService
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.data.repository.trakt.TraktEpisodeRef
import com.nexio.tv.domain.model.TrackingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    @Deprecated(
        message = "Profile-boundary: use ContinueWatchingSnapshotService.observeContinueWatching(profileId) for profile-scoped CW. This method routes by active tracking provider only and is consumed internally by ContinueWatchingSnapshotService.",
        replaceWith = ReplaceWith("ContinueWatchingSnapshotService.observeContinueWatching(profileId)")
    )
    fun observeContinueWatchingNextUp(): Flow<List<TrackingNextUpEntry>>
    fun observeSyntheticContinueWatchingNextUp(): Flow<List<TrackingNextUpEntry>>
    fun observeEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>>
    fun observeMovieWatched(contentId: String): Flow<Boolean>
    fun applyOptimisticProgress(progress: WatchProgress)
    fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?)
    fun clearOptimistic()
    fun invalidateLocalizedMetadata()
    suspend fun refreshOnStartup()
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
    private val mdbListProgressService: MDBListProgressService? = null,
    private val trackingProviderStateService: TrackingProviderStateService,
    private val tvdbContinueWatchingTimingEnricher: TvdbContinueWatchingTimingEnricher = TvdbContinueWatchingTimingEnricher()
) : TrackingProgressService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var currentActiveProviders: Set<TrackingProvider> = emptySet()

    init {
        scope.launch {
            trackingProviderStateService.state.collect { state ->
                currentActiveProviders = state.activeProviders
            }
        }
    }

    override fun observeAllProgress(): Flow<List<WatchProgress>> =
        trackingProviderStateService.state.flatMapLatest { state ->
            val active = state.activeProviders
            when {
                active.isEmpty() -> flowOf(emptyList())
                active.size == 1 -> when (active.single()) {
                    TrackingProvider.SIMKL -> simklProgressService.observeAllProgress()
                    TrackingProvider.TRAKT -> traktProgressService.observeAllProgress()
                    TrackingProvider.MDBLIST -> mdbListProgressService?.observeAllProgress() ?: flowOf(emptyList())
                }
                // Multiple authed: concatenate provider rows. Downstream
                // ContinueWatchingMerger collapses cross-provider duplicates by idBundle
                // and routes conflicts through ContinueWatchingProgressDiffPlanner.
                else -> combine(active.map(::allProgressFlowForProvider)) { providerRows ->
                    providerRows.flatMap { it }
                }
            }
        }

    private fun allProgressFlowForProvider(provider: TrackingProvider): Flow<List<WatchProgress>> {
        return when (provider) {
            TrackingProvider.SIMKL -> simklProgressService.observeAllProgress()
            TrackingProvider.TRAKT -> traktProgressService.observeAllProgress()
            TrackingProvider.MDBLIST -> mdbListProgressService?.observeAllProgress() ?: flowOf(emptyList())
        }
    }

    override fun observeRemoteSnapshotLoaded(): Flow<Boolean> =
        trackingProviderStateService.state.flatMapLatest { state ->
            val active = state.activeProviders
            if (active.isEmpty()) {
                return@flatMapLatest flowOf(false)
            }
            if (active.size == 1) {
                return@flatMapLatest snapshotLoadedFlowForProvider(active.single())
            }
            combine(active.map(::snapshotLoadedFlowForProvider)) { loaded -> loaded.any { it } }
        }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun observeContinueWatchingNextUp(): Flow<List<TrackingNextUpEntry>> =
        trackingProviderStateService.state.flatMapLatest { state ->
            if (!state.canReadEffectiveProvider) {
                return@flatMapLatest flowOf(emptyList())
            }
            when (state.effectiveProvider) {
                TrackingProvider.SIMKL -> simklProgressService.observeContinueWatchingNextUp()
                    .mapLatest { items -> tvdbContinueWatchingTimingEnricher.enrich(items) }
                TrackingProvider.TRAKT -> traktProgressService.observeContinueWatchingNextUp()
                    .mapLatest { items ->
                        tvdbContinueWatchingTimingEnricher.enrich(
                            items.map(TraktProgressService.NextUpEntry::toTrackingNextUpEntry)
                        )
                    }
                TrackingProvider.MDBLIST -> flowOf(emptyList())
            }
        }

    override fun observeSyntheticContinueWatchingNextUp(): Flow<List<TrackingNextUpEntry>> =
        trackingProviderStateService.state.flatMapLatest { state ->
            if (!state.canReadEffectiveProvider) {
                return@flatMapLatest flowOf(emptyList())
            }
            when (state.effectiveProvider) {
                TrackingProvider.SIMKL -> simklProgressService.observeSyntheticContinueWatchingNextUp()
                    .mapLatest { items -> tvdbContinueWatchingTimingEnricher.enrich(items) }
                TrackingProvider.TRAKT -> traktProgressService.observeSyntheticContinueWatchingNextUp()
                    .mapLatest { items ->
                        tvdbContinueWatchingTimingEnricher.enrich(
                            items.map(TraktProgressService.NextUpEntry::toTrackingNextUpEntry)
                        )
                    }
                TrackingProvider.MDBLIST -> flowOf(emptyList())
            }
        }

    override fun observeEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        trackingProviderStateService.state.flatMapLatest { state ->
            val active = state.activeProviders
            if (active.isEmpty()) {
                return@flatMapLatest flowOf(emptyMap())
            }
            if (active.size == 1) {
                return@flatMapLatest episodeProgressFlowForProvider(active.single(), contentId)
            }
            combine(active.map { provider -> episodeProgressFlowForProvider(provider, contentId) }) { maps ->
                val merged = linkedMapOf<Pair<Int, Int>, WatchProgress>()
                for (i in maps.indices) {
                    merged.putAll(maps[i])
                }
                merged
            }
        }

    override fun observeMovieWatched(contentId: String): Flow<Boolean> =
        trackingProviderStateService.state.flatMapLatest { state ->
            val active = state.activeProviders
            if (active.isEmpty()) {
                return@flatMapLatest flowOf(false)
            }
            if (active.size == 1) {
                return@flatMapLatest movieWatchedFlowForProvider(active.single(), contentId)
            }
            combine(active.map { provider -> movieWatchedFlowForProvider(provider, contentId) }) { values ->
                values.any { it }
            }
        }

    override fun applyOptimisticProgress(progress: WatchProgress) {
        val active = currentActiveProviders.toList()
        for (i in active.indices) {
            when (active[i]) {
                TrackingProvider.SIMKL -> simklProgressService.applyOptimisticProgress(progress)
                TrackingProvider.TRAKT -> traktProgressService.applyOptimisticProgress(progress)
                TrackingProvider.MDBLIST -> Unit
            }
        }
    }

    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) {
        val active = currentActiveProviders.toList()
        for (i in active.indices) {
            when (active[i]) {
                TrackingProvider.SIMKL -> simklProgressService.applyOptimisticRemoval(contentId, season, episode)
                TrackingProvider.TRAKT -> traktProgressService.applyOptimisticRemoval(contentId, season, episode)
                TrackingProvider.MDBLIST -> Unit
            }
        }
    }

    override fun clearOptimistic() {
        val active = currentActiveProviders.toList()
        for (i in active.indices) {
            when (active[i]) {
                TrackingProvider.SIMKL -> simklProgressService.clearOptimistic()
                TrackingProvider.TRAKT -> traktProgressService.clearOptimistic()
                TrackingProvider.MDBLIST -> Unit
            }
        }
    }

    override fun invalidateLocalizedMetadata() {
        val active = currentActiveProviders.toList()
        for (i in active.indices) {
            when (active[i]) {
                TrackingProvider.SIMKL -> simklProgressService.invalidateLocalizedMetadata()
                TrackingProvider.TRAKT -> traktProgressService.invalidateLocalizedMetadata()
                TrackingProvider.MDBLIST -> Unit
            }
        }
    }

    override suspend fun refreshNow() {
        val state = trackingProviderStateService.currentState()
        val active = state.activeProviders.toList()
        for (i in active.indices) {
            when (active[i]) {
                TrackingProvider.SIMKL -> simklProgressService.refreshNowImmediate()
                TrackingProvider.TRAKT -> traktProgressService.refreshNowImmediate()
                TrackingProvider.MDBLIST -> mdbListProgressService?.refreshNowImmediate()
            }
        }
    }

    override suspend fun refreshOnStartup() {
        val state = trackingProviderStateService.currentState()
        val active = state.activeProviders.toList()
        for (i in active.indices) {
            when (active[i]) {
                TrackingProvider.SIMKL -> simklProgressService.refreshNow()
                TrackingProvider.TRAKT -> traktProgressService.requestEventDrivenRefresh()
                TrackingProvider.MDBLIST -> mdbListProgressService?.refreshNowImmediate()
            }
        }
    }

    override suspend fun resolvePlaybackDeleteIdsForOutbox(
        contentId: String,
        season: Int?,
        episode: Int?
    ): List<Long> {
        val state = trackingProviderStateService.currentState()
        if (!state.canReadEffectiveProvider) return emptyList()
        return when (state.effectiveProvider) {
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
            TrackingProvider.MDBLIST -> emptyList()
        }
    }

    override suspend fun resolveSeasonEpisodeTraktIds(
        showContentId: String,
        season: Int,
        episodeNumbers: List<Int>
    ): Map<Int, TraktEpisodeRef> {
        val state = trackingProviderStateService.currentState()
        if (!state.traktAuthenticated) return emptyMap()
        return traktProgressService.resolveSeasonEpisodeTraktIds(
            showContentId = showContentId,
            season = season,
            episodeNumbers = episodeNumbers
        )
    }

    override suspend fun rollbackQueuedHistoryRemove(
        contentId: String,
        season: Int?,
        episode: Int?,
        removeShow: Boolean
    ) {
        val state = trackingProviderStateService.currentState()
        if (!state.canReadEffectiveProvider) return
        when (state.effectiveProvider) {
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
            TrackingProvider.MDBLIST -> Unit
        }
    }

    override suspend fun rollbackQueuedPlaybackDelete(
        contentId: String,
        season: Int?,
        episode: Int?,
        clearShow: Boolean
    ) {
        val state = trackingProviderStateService.currentState()
        if (!state.canReadEffectiveProvider) return
        when (state.effectiveProvider) {
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
            TrackingProvider.MDBLIST -> Unit
        }
    }

    private fun snapshotLoadedFlowForProvider(provider: TrackingProvider): Flow<Boolean> {
        return when (provider) {
            TrackingProvider.SIMKL -> simklProgressService.observeRemoteSnapshotLoaded()
            TrackingProvider.TRAKT -> traktProgressService.observeRemoteSnapshotLoaded()
            TrackingProvider.MDBLIST -> mdbListProgressService?.observeRemoteSnapshotLoaded() ?: flowOf(false)
        }
    }

    private fun episodeProgressFlowForProvider(
        provider: TrackingProvider,
        contentId: String
    ): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return when (provider) {
            TrackingProvider.SIMKL -> simklProgressService.observeEpisodeProgress(contentId)
            TrackingProvider.TRAKT -> traktProgressService.observeEpisodeProgress(contentId)
            TrackingProvider.MDBLIST -> mdbListProgressService?.observeEpisodeProgress(contentId) ?: flowOf(emptyMap())
        }
    }

    private fun movieWatchedFlowForProvider(provider: TrackingProvider, contentId: String): Flow<Boolean> {
        return when (provider) {
            TrackingProvider.SIMKL -> simklProgressService.observeMovieWatched(contentId)
            TrackingProvider.TRAKT -> traktProgressService.observeMovieWatched(contentId)
            TrackingProvider.MDBLIST -> mdbListProgressService?.observeMovieWatched(contentId) ?: flowOf(false)
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
