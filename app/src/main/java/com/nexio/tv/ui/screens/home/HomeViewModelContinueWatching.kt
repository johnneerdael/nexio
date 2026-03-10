package com.nexio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.repository.TraktScrobbleItem
import com.nexio.tv.domain.model.WatchProgress
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

internal fun HomeViewModel.loadContinueWatchingPipeline() {
    viewModelScope.launch {
        continueWatchingSnapshotService.observeSnapshot().collectLatest { snapshot ->
            val items = buildList {
                snapshot.movieProgressItems.forEach { progress ->
                    add(ContinueWatchingItem.InProgress(progress = progress))
                }
                snapshot.nextUpItems.forEach { entry ->
                    val releaseDate = parseEpisodeReleaseDate(entry.firstAired)
                    val hasAired = releaseDate?.let { !it.isAfter(LocalDate.now(ZoneId.systemDefault())) } ?: true
                    add(
                        ContinueWatchingItem.NextUp(
                            NextUpInfo(
                                contentId = entry.contentId,
                                contentType = entry.contentType,
                                name = entry.name,
                                poster = entry.poster,
                                backdrop = entry.backdrop,
                                logo = entry.logo,
                                videoId = entry.videoId,
                                season = entry.season,
                                episode = entry.episode,
                                episodeTitle = entry.episodeTitle,
                                episodeDescription = null,
                                thumbnail = null,
                                released = entry.firstAired,
                                hasAired = hasAired,
                                airDateLabel = releaseDate
                                    ?.takeIf { !hasAired }
                                    ?.let(::formatEpisodeAirDateLabel),
                                lastWatched = entry.firstAiredMs,
                                imdbRating = null,
                                genres = emptyList(),
                                releaseInfo = null
                            )
                        )
                    )
                }
            }

            _uiState.update { state ->
                if (state.continueWatchingItems == items) {
                    state
                } else {
                    state.copy(continueWatchingItems = items)
                }
            }
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
        val dismissKey = nextUpDismissKey(contentId)
        _uiState.update { state ->
            state.copy(
                continueWatchingItems = state.continueWatchingItems.filterNot { item ->
                    when (item) {
                        is ContinueWatchingItem.NextUp ->
                            nextUpDismissKey(item.info.contentId) == dismissKey
                        is ContinueWatchingItem.InProgress -> false
                    }
                }
            )
        }
        viewModelScope.launch {
            traktSettingsDataStore.addDismissedNextUpKey(dismissKey)
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
            season = null,
            episode = null
        )
    }
}

internal fun HomeViewModel.markContinueWatchingAsWatchedPipeline(item: ContinueWatchingItem) {
    viewModelScope.launch {
        runCatching {
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
        }.onFailure { error ->
            Log.w(HomeViewModel.TAG, "Failed to mark continue-watching item as watched", error)
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
            TraktScrobbleItem.Movie(
                title = item.progress.name,
                year = null,
                ids = ids
            )
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
