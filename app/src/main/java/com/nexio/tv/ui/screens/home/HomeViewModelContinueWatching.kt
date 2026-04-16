package com.nexio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvdbLanguageMapper
import com.nexio.tv.data.repository.ContinueWatchingNextUpRef
import com.nexio.tv.data.repository.ContinueWatchingResumeRef
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.ContinueWatchingTimelineRow
import com.nexio.tv.data.repository.TrackingScrobbleItem
import com.nexio.tv.data.repository.buildMixedContinueWatchingTimeline
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.mergeFallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun shouldEnrichContinueWatchingProviderMetadata(
    items: List<ContinueWatchingItem>,
    traktUpNextItems: List<ContinueWatchingItem.NextUp>,
    settings: TmdbSettings
): Boolean {
    if (!settings.useBasicInfo) return false
    if (settings.isActive) return true
    return items.any { item -> isSeriesType(item.contentType()) } ||
        traktUpNextItems.any { item -> isSeriesType(item.contentType()) }
}

internal fun HomeViewModel.loadContinueWatchingPipeline() {
    viewModelScope.launch {
        continueWatchingSnapshotService.observeSnapshot().collectLatest { ownedSnapshot ->
            val capturedGeneration = homeProfileGeneration
            if (ownedSnapshot.profileId != activeHomeProfileSession.profileId) {
                Log.d(HomeViewModel.TAG, "Skipping foreign continue watching snapshot profile=${ownedSnapshot.profileId}")
                return@collectLatest
            }
            val snapshot = ownedSnapshot.snapshot
            val timeline = buildMixedContinueWatchingTimeline(
                resumeItems = snapshot.resumeItems,
                nextUpItems = snapshot.nextUpItems,
                resumeRef = ::resumeRefForContinueWatching,
                nextUpRef = ::nextUpRefForContinueWatching
            )
            val nowMs = System.currentTimeMillis()
            val items = timeline.map { row ->
                when (row) {
                    is ContinueWatchingTimelineRow.Resume -> row.value.toContinueWatchingInProgress(snapshot.displayMetadataByItemKey)
                    is ContinueWatchingTimelineRow.NextUp -> row.value.toContinueWatchingNextUp(snapshot.displayMetadataByItemKey, nowMs)
                }
            }.filter { item ->
                item !is ContinueWatchingItem.NextUp || item.info.hasAired
            }
            val traktUpNextItems = snapshot.traktUpNextItems.map { entry ->
                entry.toContinueWatchingNextUp(snapshot.displayMetadataByItemKey, nowMs)
            }.filter { it.info.hasAired }

            if (!isCurrentHomeProfileGeneration(capturedGeneration)) {
                Log.d(HomeViewModel.TAG, "Skipping stale continue watching publish generation=$capturedGeneration")
                return@collectLatest
            }

            _uiState.update { state ->
                if (state.continueWatchingItems == items && state.traktUpNextItems == traktUpNextItems) {
                    state
                } else {
                    state.copy(
                        continueWatchingItems = items,
                        traktUpNextItems = traktUpNextItems
                    )
                }
            }

            val settings = currentTmdbSettings
            if (shouldEnrichContinueWatchingProviderMetadata(items, traktUpNextItems, settings)) {
                continueWatchingEnrichmentJob?.cancel()
                continueWatchingEnrichmentJob = viewModelScope.launch {
                    try {
                        val enrichedItems = enrichContinueWatchingItems(items, settings)
                        val enrichedTraktItems = enrichContinueWatchingNextUpItems(traktUpNextItems, settings)
                        if (!isCurrentHomeProfileGeneration(capturedGeneration)) {
                            Log.d(HomeViewModel.TAG, "Skipping stale continue watching enrichment generation=$capturedGeneration")
                            return@launch
                        }
                        _uiState.update { state ->
                            state.copy(
                                continueWatchingItems = enrichedItems,
                                traktUpNextItems = enrichedTraktItems
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(HomeViewModel.TAG, "Continue watching metadata enrichment failed: ${e.message}")
                    }
                }
            }
        }
    }
}

internal suspend fun HomeViewModel.enrichContinueWatchingItems(
    items: List<ContinueWatchingItem>,
    settings: TmdbSettings
): List<ContinueWatchingItem> = coroutineScope {
    items.map { item ->
        async(Dispatchers.IO) {
            enrichContinueWatchingItemWithProvider(item, settings)
        }
    }.awaitAll()
}

internal suspend fun HomeViewModel.enrichContinueWatchingNextUpItems(
    items: List<ContinueWatchingItem.NextUp>,
    settings: TmdbSettings
): List<ContinueWatchingItem.NextUp> = coroutineScope {
    items.map { item ->
        async(Dispatchers.IO) {
            enrichContinueWatchingItemWithProvider(item, settings) as? ContinueWatchingItem.NextUp ?: item
        }
    }.awaitAll()
}

internal suspend fun HomeViewModel.enrichContinueWatchingItemWithProvider(
    item: ContinueWatchingItem,
    settings: TmdbSettings
): ContinueWatchingItem {
    val contentId = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.contentId
        is ContinueWatchingItem.NextUp -> item.info.contentId
    }
    val contentType = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.contentType
        is ContinueWatchingItem.NextUp -> item.info.contentType
    }
    val tvdbLanguage = TvdbLanguageMapper.normalize(profileBoundary.currentLanguageTag())
    return try {
        val enrichment = tvMetadataRouter.fetchEnrichment(
            TvMetadataRequest(
                contentId = contentId,
                fallbackContentId = item.providerFallbackContentId(),
                contentType = ContentType.fromString(contentType),
                language = tvdbLanguage
            )
        ).value ?: return item
        val localizedEpisodeDescription = localizedContinueWatchingEpisodeDescription(
            tvMetadataRouter = tvMetadataRouter,
            item = item,
            language = tvdbLanguage
        )

        val existing = when (item) {
            is ContinueWatchingItem.InProgress -> item.displayMetadata
            is ContinueWatchingItem.NextUp -> item.info.displayMetadata
        }

        val enrichedMetadata = enrichment.toHomeDisplayMetadata(
            fallback = existing,
            settings = settings
        )

        when (item) {
            is ContinueWatchingItem.InProgress -> item.copy(
                displayMetadata = enrichedMetadata,
                episodeDescription = localizedEpisodeDescription
                    ?: enrichedMetadata.description
                    ?: item.episodeDescription,
                genres = enrichedMetadata.genres.ifEmpty { item.genres },
                releaseInfo = enrichedMetadata.releaseInfo ?: item.releaseInfo
            )
            is ContinueWatchingItem.NextUp -> item.copy(
                info = item.info.copy(
                    displayMetadata = enrichedMetadata,
                    name = enrichedMetadata.title?.takeIf { it.isNotBlank() } ?: item.info.name,
                    poster = enrichedMetadata.poster ?: item.info.poster,
                    backdrop = enrichedMetadata.backdrop ?: item.info.backdrop,
                    logo = enrichedMetadata.logo ?: item.info.logo,
                    episodeDescription = localizedEpisodeDescription
                        ?: enrichedMetadata.description
                        ?: item.info.episodeDescription,
                    genres = enrichedMetadata.genres.ifEmpty { item.info.genres },
                    releaseInfo = enrichedMetadata.releaseInfo ?: item.info.releaseInfo
                )
            )
        }
    } catch (e: Exception) {
        Log.w(HomeViewModel.TAG, "Provider enrichment failed for continue watching item $contentId: ${e.message}")
        item
    }
}

internal suspend fun localizedContinueWatchingEpisodeDescription(
    tvMetadataRouter: com.nexio.tv.core.tvdb.TvMetadataRouter,
    item: ContinueWatchingItem,
    language: String? = null
): String? {
    val season = item.season() ?: return null
    val episode = item.episode() ?: return null
    if (!isSeriesType(item.contentType())) return null

    return tvMetadataRouter.fetchEpisodeEnrichment(
        TvMetadataRequest(
            contentId = item.contentId(),
            fallbackContentId = item.providerFallbackContentId(),
            contentType = ContentType.fromString(item.contentType()),
            language = language,
            seasonNumbers = listOf(season)
        )
    ).value?.get(season to episode)?.overview?.takeIf { it.isNotBlank() }
}

private fun TvMetadataEnrichment.toHomeDisplayMetadata(
    fallback: HomeDisplayMetadata?,
    settings: TmdbSettings
): HomeDisplayMetadata {
    return HomeDisplayMetadata(
        title = localizedTitle,
        description = description,
        genres = genres,
        imdbRating = rating?.toFloat(),
        poster = if (settings.useArtwork) poster else null,
        backdrop = if (settings.useArtwork) backdrop else null,
        logo = if (settings.useArtwork) logo else null,
        releaseInfo = if (settings.useDetails) releaseInfo else null
    ).mergeFallback(fallback)
}

private fun ContinueWatchingItem.providerFallbackContentId(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.videoId
        is ContinueWatchingItem.NextUp -> info.videoId
    }
}

private fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    val value = raw.trim()
    val zone = ZoneId.systemDefault()

    return runCatching {
        Instant.parse(value).atZone(zone).toLocalDate()
    }.getOrNull() ?: runCatching {
        OffsetDateTime.parse(value).toInstant().atZone(zone).toLocalDate()
    }.getOrNull() ?: runCatching {
        LocalDateTime.parse(value).toLocalDate()
    }.getOrNull() ?: runCatching {
        LocalDate.parse(value)
    }.getOrNull() ?: runCatching {
        val datePortion = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b").find(value)?.value
            ?: return@runCatching null
        LocalDate.parse(datePortion)
    }.getOrNull()
}

private fun formatEpisodeAirDateLabel(releaseDate: LocalDate): String {
    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val formatter = if (releaseDate.year == todayLocal.year) {
        DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    } else {
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    }
    return releaseDate.format(formatter)
}

/**
 * When TVDB provides an exact air-time instant, format it as device-local date + 24h time
 * (e.g., "Apr 23, 03:00"). This gives users a clear visual indicator that TVDB timing is
 * active and shows the converted local release time.
 *
 * Falls back to null when no TVDB instant is available (caller should use date-only label).
 */
private fun formatDeviceLocalAirLabel(tvdbInstantMs: Long?, deviceLocalDateTimeStr: String?): String? {
    if (tvdbInstantMs == null || tvdbInstantMs <= 0L) return null

    val deviceZone = ZoneId.systemDefault()
    val deviceLocal: ZonedDateTime = (
        if (!deviceLocalDateTimeStr.isNullOrBlank()) {
            runCatching { ZonedDateTime.parse(deviceLocalDateTimeStr).withZoneSameInstant(deviceZone) }.getOrNull()
        } else null
    ) ?: runCatching { Instant.ofEpochMilli(tvdbInstantMs).atZone(deviceZone) }.getOrNull()
        ?: return null

    val todayLocal = LocalDate.now(deviceZone)
    val pattern = if (deviceLocal.toLocalDate().year == todayLocal.year) "MMM d, HH:mm" else "MMM d yyyy, HH:mm"
    return deviceLocal.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

private fun nextUpDismissKey(contentId: String): String {
    return contentId.trim()
}

internal fun HomeViewModel.removeContinueWatchingPipeline(
    contentId: String,
    season: Int? = null,
    episode: Int? = null,
    isNextUp: Boolean = false
) {
    if (isNextUp) {
        val targetId = nextUpDismissKey(contentId)
        _uiState.update { state ->
            state.copy(
                continueWatchingItems = state.continueWatchingItems.filterNot { item ->
                    when (item) {
                        is ContinueWatchingItem.NextUp ->
                            nextUpDismissKey(item.info.contentId) == targetId
                        is ContinueWatchingItem.InProgress -> false
                    }
                },
                traktUpNextItems = state.traktUpNextItems.filterNot { item ->
                    nextUpDismissKey(item.info.contentId) == targetId
                }
            )
        }
        viewModelScope.launch {
            continueWatchingSnapshotService.removeShowOptimistically(targetId)
            runCatching {
                watchProgressRepository.clearShowProgress(targetId)
                continueWatchingSnapshotService.ensureFresh(force = true)
            }.onFailure { error ->
                Log.w(HomeViewModel.TAG, "Failed to clear show progress for $targetId", error)
            }
        }
        return
    }
    // For InProgress items: find the matching resume entry for optimistic removal + rollback.
    val capturedProgress = _uiState.value.continueWatchingItems
        .filterIsInstance<ContinueWatchingItem.InProgress>()
        .firstOrNull { item ->
            item.progress.contentId == contentId &&
                (season == null || item.progress.season == season) &&
                (episode == null || item.progress.episode == episode)
        }?.progress
    viewModelScope.launch {
        Log.d(
            HomeViewModel.TAG,
            "removeContinueWatching requested contentId=$contentId season=$season episode=$episode isNextUp=$isNextUp"
        )
        if (capturedProgress != null) {
            continueWatchingSnapshotService.removeResumeEntry(capturedProgress.videoId)
        }
        runCatching {
            watchProgressRepository.removeProgress(
                contentId = contentId,
                season = season,
                episode = episode
            )
            continueWatchingSnapshotService.ensureFresh(force = true)
        }.onFailure { error ->
            if (capturedProgress != null) {
                runCatching { continueWatchingSnapshotService.reinsertResumeEntry(capturedProgress) }
                    .onFailure { Log.w(HomeViewModel.TAG, "Failed to rollback removeResumeEntry", it) }
            }
            Log.w(HomeViewModel.TAG, "Failed to remove continue watching progress for $contentId", error)
        }
    }
}

internal fun HomeViewModel.markContinueWatchingAsWatchedPipeline(item: ContinueWatchingItem) {
    val nextUpTargetId: String? = if (item is ContinueWatchingItem.NextUp) {
        val targetId = nextUpDismissKey(item.info.contentId)
        _uiState.update { state ->
            state.copy(
                continueWatchingItems = state.continueWatchingItems.filterNot { current ->
                    current is ContinueWatchingItem.NextUp &&
                        nextUpDismissKey(current.info.contentId) == targetId
                },
                traktUpNextItems = state.traktUpNextItems.filterNot { current ->
                    nextUpDismissKey(current.info.contentId) == targetId
                }
            )
        }
        targetId
    } else {
        null
    }

    val episodeRef = when (item) {
        is ContinueWatchingItem.InProgress -> {
            val p = item.progress
            if (p.season != null && p.episode != null) {
                listOf(ContinueWatchingSnapshotService.EpisodeRef(p.contentId, p.season, p.episode))
            } else emptyList()
        }
        is ContinueWatchingItem.NextUp -> {
            val info = item.info
            listOf(ContinueWatchingSnapshotService.EpisodeRef(info.contentId, info.season, info.episode))
        }
    }
    val capturedProgress = (item as? ContinueWatchingItem.InProgress)?.progress

    viewModelScope.launch {
        if (nextUpTargetId != null) {
            continueWatchingSnapshotService.removeShowOptimistically(nextUpTargetId)
        }
        if (episodeRef.isNotEmpty()) {
            continueWatchingSnapshotService.applyEpisodesMarked(episodeRef)
        }
        try {
            val now = System.currentTimeMillis()
            val progress = when (item) {
                is ContinueWatchingItem.InProgress -> item.progress
                is ContinueWatchingItem.NextUp -> WatchProgress(
                    contentId = item.info.contentId,
                    contentType = item.info.contentType,
                    name = item.info.name,
                    poster = item.info.poster,
                    backdrop = item.info.backdrop,
                    logo = item.info.logo,
                    videoId = item.info.videoId,
                    season = item.info.season,
                    episode = item.info.episode,
                    episodeTitle = item.info.episodeTitle,
                    position = 1L,
                    duration = 1L,
                    lastWatched = now,
                    progressPercent = 100f
                )
            }
            watchProgressRepository.markAsCompleted(progress)
        } catch (error: Throwable) {
            Log.w(HomeViewModel.TAG, "Failed to mark continue-watching item as watched", error)
            if (episodeRef.isNotEmpty()) {
                val rollback = listOfNotNull(capturedProgress)
                runCatching { continueWatchingSnapshotService.rollbackEpisodes(rollback) }
                    .onFailure { Log.w(HomeViewModel.TAG, "Failed to rollback applyEpisodesMarked", it) }
            }
        } finally {
            runCatching {
                continueWatchingSnapshotService.ensureFresh(force = true)
            }.onFailure { error ->
                Log.w(HomeViewModel.TAG, "Failed to refresh continue-watching snapshot after mark watched", error)
            }
        }
    }
}

internal fun HomeViewModel.checkInContinueWatchingPipeline(item: ContinueWatchingItem) {
    viewModelScope.launch {
        val scrobbleItem = buildTrackingScrobbleItemForContinueWatching(item)
        if (scrobbleItem == null) {
            Log.d(HomeViewModel.TAG, "Skipped tracking check-in: missing/unsupported IDs for item=$item")
            return@launch
        }
        runCatching {
            trackingScrobbleService.checkin(scrobbleItem)
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed tracking check-in for continue-watching item", error)
        }
    }
}

private fun buildTrackingScrobbleItemForContinueWatching(item: ContinueWatchingItem): TrackingScrobbleItem? {
    return when (item) {
        is ContinueWatchingItem.InProgress -> {
            if (item.progress.contentId.isBlank()) return null
            if (
                (item.progress.contentType.equals("series", ignoreCase = true) ||
                    item.progress.contentType.equals("tv", ignoreCase = true)) &&
                item.progress.season != null &&
                item.progress.episode != null
            ) {
                TrackingScrobbleItem.Episode(
                    contentId = item.progress.contentId,
                    showTitle = item.progress.name,
                    showYear = null,
                    season = item.progress.season,
                    number = item.progress.episode,
                    episodeTitle = item.progress.episodeTitle
                )
            } else {
                TrackingScrobbleItem.Movie(
                    contentId = item.progress.contentId,
                    title = item.progress.name,
                    year = null
                )
            }
        }

        is ContinueWatchingItem.NextUp -> {
            if (item.info.contentId.isBlank()) return null
            TrackingScrobbleItem.Episode(
                contentId = item.info.contentId,
                showTitle = item.info.name,
                showYear = null,
                season = item.info.season,
                number = item.info.episode,
                episodeTitle = item.info.episodeTitle
            )
        }
    }
}

private fun resumeRefForContinueWatching(progress: WatchProgress): ContinueWatchingResumeRef {
    val suppressNextUp = progress.season != null &&
        progress.episode != null &&
        (progress.contentType.equals("series", ignoreCase = true) || progress.contentType.equals("tv", ignoreCase = true))
    return ContinueWatchingResumeRef(
        contentId = progress.contentId,
        activityAtMs = progress.lastWatched,
        suppressNextUp = suppressNextUp
    )
}

private fun nextUpRefForContinueWatching(
    entry: com.nexio.tv.data.repository.TrackingNextUpEntry
): ContinueWatchingNextUpRef {
    return ContinueWatchingNextUpRef(
        contentId = entry.contentId,
        activityAtMs = entry.activityAtMs,
        firstAiredMs = entry.firstAiredMs,
        availabilityInstantMs = entry.tvdbAvailabilityInstantMs
    )
}

private fun WatchProgress.toContinueWatchingInProgress(
    displayMetadataByItemKey: Map<String, HomeDisplayMetadata>
): ContinueWatchingItem.InProgress {
    val displayMetadata = displayMetadataByItemKey[homeDisplayItemKey(contentType, contentId)]
    return ContinueWatchingItem.InProgress(
        progress = this,
        displayMetadata = displayMetadata,
        episodeDescription = displayMetadata?.description,
        episodeImdbRating = displayMetadata?.imdbRating,
        genres = displayMetadata?.genres.orEmpty(),
        releaseInfo = displayMetadata?.releaseInfo
    )
}

private fun com.nexio.tv.data.repository.TrackingNextUpEntry.toContinueWatchingNextUp(
    displayMetadataByItemKey: Map<String, HomeDisplayMetadata>,
    nowMs: Long
): ContinueWatchingItem.NextUp {
    val hasAired = com.nexio.tv.data.repository.AirDateGate.isAired(
        tvdbAvailabilityInstantMs,
        firstAiredMs,
        firstAired,
        nowMs
    )
    val airLabel = if (!hasAired) {
        formatDeviceLocalAirLabel(tvdbAvailabilityInstantMs, tvdbAvailabilityDeviceLocalDateTime)
            ?: parseEpisodeReleaseDate(firstAired)?.let(::formatEpisodeAirDateLabel)
    } else null
    val displayMetadata = displayMetadataByItemKey[homeDisplayItemKey(contentType, contentId)]
    return ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = contentId,
            contentType = contentType,
            name = displayMetadata?.title ?: name,
            poster = displayMetadata?.poster ?: poster,
            backdrop = displayMetadata?.backdrop ?: backdrop,
            logo = displayMetadata?.logo ?: logo,
            displayMetadata = displayMetadata,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
            episodeDescription = displayMetadata?.description,
            thumbnail = null,
            released = firstAired,
            hasAired = hasAired,
            airDateLabel = airLabel,
            lastWatched = activityAtMs,
            imdbRating = displayMetadata?.imdbRating,
            genres = displayMetadata?.genres.orEmpty(),
            releaseInfo = displayMetadata?.releaseInfo
        )
    )
}

internal fun HomeViewModel.enrichContinueWatchingWithCurrentSettings() {
    val settings = currentTmdbSettings
    val currentItems = _uiState.value.continueWatchingItems
    val currentTraktItems = _uiState.value.traktUpNextItems
    if (!shouldEnrichContinueWatchingProviderMetadata(currentItems, currentTraktItems, settings)) return
    if (currentItems.isEmpty() && currentTraktItems.isEmpty()) return

    continueWatchingEnrichmentJob?.cancel()
    continueWatchingEnrichmentJob = viewModelScope.launch {
        try {
            val enrichedItems = enrichContinueWatchingItems(currentItems, settings)
            val enrichedTraktItems = enrichContinueWatchingNextUpItems(currentTraktItems, settings)
            _uiState.update { state ->
                state.copy(
                    continueWatchingItems = enrichedItems,
                    traktUpNextItems = enrichedTraktItems
                )
            }
        } catch (e: Exception) {
            Log.w(HomeViewModel.TAG, "Continue watching metadata enrichment failed: ${e.message}")
        }
    }
}
