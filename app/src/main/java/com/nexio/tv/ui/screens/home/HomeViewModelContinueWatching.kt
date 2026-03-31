package com.nexio.tv.ui.screens.home

import android.text.format.DateFormat
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.local.WatchedSeriesEntry
import com.nexio.tv.data.local.expandSeriesContentIdAliases
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import com.nexio.tv.data.repository.ContinueWatchingNextUpRef
import com.nexio.tv.data.repository.ContinueWatchingResumeRef
import com.nexio.tv.data.repository.ContinueWatchingTimelineRow
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.data.repository.TraktScrobbleItem
import com.nexio.tv.data.repository.buildMixedContinueWatchingTimeline
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.WatchedItem
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.model.homeDisplayItemKey
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val SERIES_NEXT_UP_LOOKUP_LIMIT = 24

internal fun HomeViewModel.loadContinueWatchingPipeline() {
    viewModelScope.launch {
        continueWatchingSnapshotService.observeSnapshot().collectLatest { snapshot ->
            lastContinueWatchingSnapshot = snapshot
            publishContinueWatchingSnapshot(snapshot)
            syncWatchedSeriesStateFromLocalItems(snapshot)
        }
    }
}

private suspend fun HomeViewModel.syncWatchedSeriesStateFromLocalItems(
    snapshot: ContinueWatchingSnapshot
) {
    val watchedItems = watchedItemsPreferences.getAllItems()
    val candidateStates = buildWatchedSeriesCandidateStates(
        watchedItems = watchedItems,
        snapshot = snapshot
    )
    if (candidateStates.isEmpty()) {
        discoveredNextUpEntriesByContentId.clear()
        lastSeriesNextUpDiscoverySignature = null
        watchedSeriesStateHolder.applyResolvedStates(
            watchedEntries = emptyList(),
            unwatchedEntries = watchedSeriesStateHolder.entries.value.map { it.ids }
        )
        publishContinueWatchingSnapshot(snapshot)
        return
    }

    val immediateUnwatched = candidateStates
        .filterNot { it.watched }
        .map { it.ids }
    if (immediateUnwatched.isNotEmpty()) {
        watchedSeriesStateHolder.applyResolvedStates(
            watchedEntries = emptyList(),
            unwatchedEntries = immediateUnwatched
        )
    }

    val lookupCandidates = buildSeriesNextUpLookupCandidates(
        watchedItems = watchedItems,
        snapshot = snapshot,
        currentEntries = watchedSeriesStateHolder.entries.value
    )
    val signature = lookupCandidates
        .joinToString("|") { candidate -> "${candidate.contentId}:${candidate.lastWatchedAtMs}" }
    if (signature.isBlank()) {
        if (discoveredNextUpEntriesByContentId.isNotEmpty()) {
            discoveredNextUpEntriesByContentId.clear()
            publishContinueWatchingSnapshot(snapshot)
        }
        lastSeriesNextUpDiscoverySignature = null
        val confirmedWatched = candidateStates
            .filter { it.watched }
            .map { it.ids }
        watchedSeriesStateHolder.applyResolvedStates(
            watchedEntries = confirmedWatched,
            unwatchedEntries = emptyList()
        )
        return
    }
    if (signature == lastSeriesNextUpDiscoverySignature) return

    lastSeriesNextUpDiscoverySignature = signature
    seriesNextUpDiscoveryJob?.cancel()
    seriesNextUpDiscoveryJob = viewModelScope.launch {
        val discovered = traktProgressService.lookupNextUpForSeriesCandidates(
            lookupCandidates.take(SERIES_NEXT_UP_LOOKUP_LIMIT)
        )
        val discoveredByAliases = discovered.associateBy { entry ->
            preferredAliasForSeriesContentId(entry.contentId)
        }
        val watchedEntries = lookupCandidates
            .filter { candidate ->
                preferredAliasForSeriesContentId(candidate.contentId) !in discoveredByAliases
            }
            .map { candidate -> expandSeriesContentIdAliases(listOf(candidate.contentId)) }
        val unwatchedEntries = discovered.map { entry ->
            expandSeriesContentIdAliases(listOf(entry.contentId))
        }

        watchedSeriesStateHolder.applyResolvedStates(
            watchedEntries = watchedEntries,
            unwatchedEntries = unwatchedEntries
        )

        val nowMs = System.currentTimeMillis()
        val airedDiscovered = discovered
            .filter { entry -> entry.firstAiredMs <= 0L || entry.firstAiredMs <= nowMs }
            .associateByTo(linkedMapOf()) { entry -> preferredAliasForSeriesContentId(entry.contentId) }
        discoveredNextUpEntriesByContentId.clear()
        discoveredNextUpEntriesByContentId.putAll(airedDiscovered)
        publishContinueWatchingSnapshot(lastContinueWatchingSnapshot)
    }
}

private fun HomeViewModel.publishContinueWatchingSnapshot(snapshot: ContinueWatchingSnapshot) {
    val injectedNextUpItems = buildList {
        val snapshotKeys = snapshot.nextUpItems.mapTo(linkedSetOf()) {
            preferredAliasForSeriesContentId(it.contentId)
        }
        addAll(snapshot.nextUpItems)
        discoveredNextUpEntriesByContentId.forEach { (key, entry) ->
            if (key !in snapshotKeys) add(entry)
        }
    }
        .distinctBy { preferredAliasForSeriesContentId(it.contentId) }
        .sortedByDescending { it.activityAtMs }
    val timeline = buildMixedContinueWatchingTimeline(
        resumeItems = snapshot.resumeItems,
        nextUpItems = injectedNextUpItems,
        resumeRef = ::resumeRefForContinueWatching,
        nextUpRef = ::nextUpRefForContinueWatching
    )
    val items = timeline.map { row ->
        when (row) {
            is ContinueWatchingTimelineRow.Resume -> row.value.toContinueWatchingInProgress(snapshot.displayMetadataByItemKey)
            is ContinueWatchingTimelineRow.NextUp -> row.value.toContinueWatchingNextUp(snapshot.displayMetadataByItemKey)
        }
    }
    val traktUpNextItems = snapshot.traktUpNextItems.map { entry ->
        entry.toContinueWatchingNextUp(snapshot.displayMetadataByItemKey)
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
        continueWatchingSnapshotService.removeShowOptimistically(targetId)
        viewModelScope.launch {
            runCatching {
                watchProgressRepository.clearShowProgress(targetId)
                continueWatchingSnapshotService.ensureFresh(force = true)
            }.onFailure { error ->
                Log.w(HomeViewModel.TAG, "Failed to clear show progress for $targetId", error)
            }
        }
        return
    }
    viewModelScope.launch {
        Log.d(
            HomeViewModel.TAG,
            "removeContinueWatching requested contentId=$contentId season=$season episode=$episode isNextUp=$isNextUp"
        )
        watchProgressRepository.removeProgress(
            contentId = contentId,
            season = season,
            episode = episode
        )
        continueWatchingSnapshotService.ensureFresh(force = true)
    }
}

internal fun HomeViewModel.markContinueWatchingAsWatchedPipeline(item: ContinueWatchingItem) {
    if (item is ContinueWatchingItem.NextUp) {
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
        continueWatchingSnapshotService.removeShowOptimistically(targetId)
    }

    viewModelScope.launch {
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
        val scrobbleItem = buildTraktScrobbleItemForContinueWatching(item)
        if (scrobbleItem == null) {
            Log.d(HomeViewModel.TAG, "Skipped Trakt check-in: missing/unsupported IDs for item=$item")
            return@launch
        }
        runCatching {
            traktScrobbleService.checkin(scrobbleItem)
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed Trakt check-in for continue-watching item", error)
        }
    }
}

private fun buildTraktScrobbleItemForContinueWatching(item: ContinueWatchingItem): TraktScrobbleItem? {
    return when (item) {
        is ContinueWatchingItem.InProgress -> {
            val ids = parseTraktIdsForContinueWatching(item.progress.contentId)
            if (!ids.hasAnyId()) return null
            if (
                (item.progress.contentType.equals("series", ignoreCase = true) ||
                    item.progress.contentType.equals("tv", ignoreCase = true)) &&
                item.progress.season != null &&
                item.progress.episode != null
            ) {
                TraktScrobbleItem.Episode(
                    showTitle = item.progress.name,
                    showYear = null,
                    showIds = ids,
                    season = item.progress.season,
                    number = item.progress.episode,
                    episodeTitle = item.progress.episodeTitle
                )
            } else {
                TraktScrobbleItem.Movie(
                    title = item.progress.name,
                    year = null,
                    ids = ids
                )
            }
        }

        is ContinueWatchingItem.NextUp -> {
            val ids = parseTraktIdsForContinueWatching(item.info.contentId)
            if (!ids.hasAnyId()) return null
            TraktScrobbleItem.Episode(
                showTitle = item.info.name,
                showYear = null,
                showIds = ids,
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
    entry: com.nexio.tv.data.repository.TraktProgressService.NextUpEntry
): ContinueWatchingNextUpRef {
    return ContinueWatchingNextUpRef(
        contentId = entry.contentId,
        activityAtMs = entry.activityAtMs,
        firstAiredMs = entry.firstAiredMs
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

private fun com.nexio.tv.data.repository.TraktProgressService.NextUpEntry.toContinueWatchingNextUp(
    displayMetadataByItemKey: Map<String, HomeDisplayMetadata>
): ContinueWatchingItem.NextUp {
    val releaseDate = parseEpisodeReleaseDate(firstAired)
    val hasAired = releaseDate?.let { !it.isAfter(LocalDate.now(ZoneId.systemDefault())) } ?: true
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
            airDateLabel = releaseDate
                ?.takeIf { !hasAired }
                ?.let(::formatEpisodeAirDateLabel),
            lastWatched = activityAtMs,
            imdbRating = displayMetadata?.imdbRating,
            genres = displayMetadata?.genres.orEmpty(),
            releaseInfo = displayMetadata?.releaseInfo
        )
    )
}

internal data class WatchedSeriesCandidateState(
    val ids: Set<String>,
    val watched: Boolean
)

internal fun buildWatchedSeriesCandidateStates(
    watchedItems: List<WatchedItem>,
    snapshot: ContinueWatchingSnapshot
): List<WatchedSeriesCandidateState> {
    val candidateAliasSets = mergeWatchedSeriesAliasSets(
        watchedItems
            .asSequence()
            .filter { it.season != null && it.episode != null }
            .map { item -> expandSeriesContentIdAliases(listOf(item.contentId)) }
            .toList()
    )
    if (candidateAliasSets.isEmpty()) return emptyList()

    val activeAliases = buildSet {
        snapshot.nextUpItems.forEach { entry ->
            addAll(expandSeriesContentIdAliases(listOf(entry.contentId)))
        }
        snapshot.resumeItems
            .filter { progress -> isSeriesTypeCW(progress.contentType) }
            .forEach { progress ->
                addAll(expandSeriesContentIdAliases(listOf(progress.contentId)))
            }
    }

    return candidateAliasSets.map { ids ->
        WatchedSeriesCandidateState(
            ids = ids,
            watched = ids.none { it in activeAliases }
        )
    }
}

private fun HomeViewModel.buildSeriesNextUpLookupCandidates(
    watchedItems: List<WatchedItem>,
    snapshot: ContinueWatchingSnapshot,
    currentEntries: List<WatchedSeriesEntry>
): List<TraktProgressService.SeriesNextUpLookupCandidate> {
    val activeAliases = buildSet {
        snapshot.nextUpItems.forEach { entry ->
            addAll(expandSeriesContentIdAliases(listOf(entry.contentId)))
        }
        snapshot.resumeItems
            .filter { progress -> isSeriesTypeCW(progress.contentType) }
            .forEach { progress ->
                addAll(expandSeriesContentIdAliases(listOf(progress.contentId)))
            }
    }
    val watchedByAliasSets = mergeWatchedSeriesAliasSets(
        watchedItems
            .asSequence()
            .filter { it.season != null && it.episode != null }
            .groupBy { item -> preferredAliasForSeriesContentId(item.contentId) }
            .values
            .map { grouped ->
                grouped.flatMapTo(linkedSetOf()) { item ->
                    expandSeriesContentIdAliases(listOf(item.contentId))
                }
            }
    )

    return watchedByAliasSets.mapNotNull { aliases ->
        if (aliases.any { it in activeAliases }) return@mapNotNull null
        val alreadyWatched = currentEntries.any { entry ->
            entry.ids.any { it in aliases }
        }
        val matchingItems = watchedItems.filter { item ->
            expandSeriesContentIdAliases(listOf(item.contentId)).any { it in aliases }
        }
        val latestItem = matchingItems.maxByOrNull { it.watchedAt } ?: return@mapNotNull null
        val preferredId = aliases
            .sortedWith(compareBy<String> { aliasPriority(it) }.thenBy { it })
            .firstOrNull()
            ?: return@mapNotNull null
        if (alreadyWatched && preferredAliasForSeriesContentId(preferredId) in discoveredNextUpEntriesByContentId) {
            return@mapNotNull null
        }
        TraktProgressService.SeriesNextUpLookupCandidate(
            contentId = preferredId,
            title = latestItem.title,
            lastWatchedAtMs = latestItem.watchedAt
        )
    }
}

private fun mergeWatchedSeriesAliasSets(aliasSets: List<Set<String>>): List<Set<String>> {
    val merged = mutableListOf<MutableSet<String>>()
    aliasSets.forEach { aliases ->
        if (aliases.isEmpty()) return@forEach
        val overlapping = merged.filter { current -> current.any { it in aliases } }
        if (overlapping.isEmpty()) {
            merged += aliases.toMutableSet()
        } else {
            val combined = aliases.toMutableSet()
            overlapping.forEach { combined += it }
            merged.removeAll(overlapping.toSet())
            merged += combined
        }
    }
    return merged.map { it.toSet() }
}

private fun preferredAliasForSeriesContentId(contentId: String): String {
    val aliases = expandSeriesContentIdAliases(listOf(contentId))
    return aliases
        .sortedWith(compareBy<String> { aliasPriority(it) }.thenBy { it })
        .firstOrNull()
        ?: contentId.trim()
}

private fun aliasPriority(contentId: String): Int {
    return when {
        contentId.startsWith("tt", ignoreCase = true) -> 0
        contentId.startsWith("tmdb:", ignoreCase = true) -> 1
        contentId.startsWith("trakt:", ignoreCase = true) -> 2
        contentId.toIntOrNull() != null -> 3
        else -> 4
    }
}

private fun isSeriesTypeCW(contentType: String): Boolean {
    return contentType.equals("series", ignoreCase = true) ||
        contentType.equals("tv", ignoreCase = true)
}

private fun parseTraktIdsForContinueWatching(contentId: String): TraktIdsDto {
    val raw = contentId.trim()
    if (raw.isBlank()) return TraktIdsDto()

    return when {
        raw.startsWith("tt", ignoreCase = true) -> TraktIdsDto(
            imdb = raw.substringBefore(':').lowercase()
        )

        raw.startsWith("tmdb:", ignoreCase = true) -> TraktIdsDto(
            tmdb = raw.substringAfter(':').toIntOrNull()
        )

        raw.startsWith("trakt:", ignoreCase = true) -> TraktIdsDto(
            trakt = raw.substringAfter(':').toIntOrNull()
        )

        else -> {
            val numeric = raw.substringBefore(':').toIntOrNull()
            if (numeric != null) {
                TraktIdsDto(trakt = numeric)
            } else {
                TraktIdsDto()
            }
        }
    }
}

private fun TraktIdsDto.hasAnyId(): Boolean {
    return trakt != null || !imdb.isNullOrBlank() || tmdb != null || tvdb != null || !slug.isNullOrBlank()
}
