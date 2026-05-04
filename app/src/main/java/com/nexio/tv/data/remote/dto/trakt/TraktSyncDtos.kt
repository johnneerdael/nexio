package com.nexio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktLastActivitiesResponseDto(
    @Json(name = "all") val all: String? = null,
    @Json(name = "movies") val movies: TraktLastActivitiesMediaDto? = null,
    @Json(name = "episodes") val episodes: TraktLastActivitiesMediaDto? = null,
    @Json(name = "shows") val shows: TraktLastActivitiesMediaDto? = null,
    @Json(name = "seasons") val seasons: TraktLastActivitiesMediaDto? = null,
    @Json(name = "lists") val lists: TraktLastActivitiesListsDto? = null,
    @Json(name = "watchlist") val watchlist: TraktLastActivitiesUpdatedDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktLastActivitiesMediaDto(
    @Json(name = "watched_at") val watchedAt: String? = null,
    @Json(name = "collected_at") val collectedAt: String? = null,
    @Json(name = "rated_at") val ratedAt: String? = null,
    @Json(name = "watchlisted_at") val watchlistedAt: String? = null,
    @Json(name = "favorited_at") val favoritedAt: String? = null,
    @Json(name = "commented_at") val commentedAt: String? = null,
    @Json(name = "paused_at") val pausedAt: String? = null,
    @Json(name = "hidden_at") val hiddenAt: String? = null,
    @Json(name = "dropped_at") val droppedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktLastActivitiesListsDto(
    @Json(name = "liked_at") val likedAt: String? = null,
    @Json(name = "reacted_at") val reactedAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "commented_at") val commentedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktLastActivitiesUpdatedDto(
    @Json(name = "updated_at") val updatedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktPlaybackItemDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "progress") val progress: Float? = null,
    @Json(name = "paused_at") val pausedAt: String? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "episode") val episode: TraktEpisodeDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktWatchedMovieItemDto(
    @Json(name = "plays") val plays: Int? = null,
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "last_updated_at") val lastUpdatedAt: String? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktWatchedShowItemDto(
    @Json(name = "plays") val plays: Int? = null,
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "last_updated_at") val lastUpdatedAt: String? = null,
    @Json(name = "reset_at") val resetAt: String? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "seasons") val seasons: List<TraktWatchedSeasonDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktWatchedSeasonDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "episodes") val episodes: List<TraktWatchedEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktWatchedEpisodeDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "plays") val plays: Int? = null,
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktHiddenItemDto(
    @Json(name = "hidden_at") val hiddenAt: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "season") val season: TraktSeasonDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktSeasonDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktShowSeasonWithEpisodesDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "episodes") val episodes: List<TraktEpisodeSummaryDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktUserEpisodeHistoryItemDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "watched_at") val watchedAt: String? = null,
    @Json(name = "action") val action: String? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "episode") val episode: TraktEpisodeDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktShowProgressResponseDto(
    @Json(name = "aired") val aired: Int? = null,
    @Json(name = "completed") val completed: Int? = null,
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "reset_at") val resetAt: String? = null,
    @Json(name = "seasons") val seasons: List<TraktShowSeasonProgressDto>? = null,
    @Json(name = "hidden_seasons") val hiddenSeasons: List<TraktSeasonDto>? = null,
    @Json(name = "next_episode") val nextEpisode: TraktEpisodeDto? = null,
    @Json(name = "last_episode") val lastEpisode: TraktEpisodeDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktShowSeasonProgressDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "aired") val aired: Int? = null,
    @Json(name = "completed") val completed: Int? = null,
    @Json(name = "episodes") val episodes: List<TraktShowEpisodeProgressDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktShowEpisodeProgressDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "completed") val completed: Boolean? = null,
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktEpisodeSummaryDto(
    @Json(name = "season") val season: Int? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "first_aired") val firstAired: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "rating") val rating: Double? = null,
    @Json(name = "votes") val votes: Int? = null,
    @Json(name = "comment_count") val commentCount: Int? = null,
    @Json(name = "runtime") val runtime: Int? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryRemoveRequestDto(
    @Json(name = "movies") val movies: List<TraktMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktHistoryShowRemoveDto>? = null,
    @Json(name = "episodes") val episodes: List<TraktEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryShowRemoveDto(
    @Json(name = "ids") val ids: TraktIdsDto,
    @Json(name = "seasons") val seasons: List<TraktHistorySeasonRemoveDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistorySeasonRemoveDto(
    @Json(name = "number") val number: Int,
    @Json(name = "episodes") val episodes: List<TraktHistoryEpisodeRemoveDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryEpisodeRemoveDto(
    @Json(name = "number") val number: Int
)

@JsonClass(generateAdapter = true)
data class TraktHistoryRemoveResponseDto(
    @Json(name = "deleted") val deleted: TraktHistoryRemoveCountDto? = null,
    @Json(name = "not_found") val notFound: TraktHistoryRemoveNotFoundDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryRemoveCountDto(
    @Json(name = "movies") val movies: Int? = null,
    @Json(name = "episodes") val episodes: Int? = null,
    @Json(name = "shows") val shows: Int? = null,
    @Json(name = "seasons") val seasons: Int? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryRemoveNotFoundDto(
    @Json(name = "movies") val movies: List<TraktMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktShowDto>? = null,
    @Json(name = "seasons") val seasons: List<Map<String, Any?>>? = null,
    @Json(name = "episodes") val episodes: List<TraktEpisodeDto>? = null,
    @Json(name = "ids") val ids: List<Long>? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryAddRequestDto(
    @Json(name = "movies") val movies: List<TraktHistoryMovieAddDto>? = null,
    @Json(name = "shows") val shows: List<TraktHistoryShowAddDto>? = null,
    @Json(name = "seasons") val seasons: List<TraktHistorySeasonAddDto>? = null,
    @Json(name = "episodes") val episodes: List<TraktHistoryEpisodeAddDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryMovieAddDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "watched_at") val watchedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryShowAddDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "seasons") val seasons: List<TraktHistorySeasonAddDto>? = null,
    @Json(name = "watched_at") val watchedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistorySeasonAddDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "episodes") val episodes: List<TraktHistoryEpisodeAddDto>? = null,
    @Json(name = "watched_at") val watchedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryEpisodeAddDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "watched_at") val watchedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryAddResponseDto(
    @Json(name = "added") val added: TraktHistoryRemoveCountDto? = null,
    @Json(name = "not_found") val notFound: TraktHistoryAddNotFoundDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryAddNotFoundDto(
    @Json(name = "movies") val movies: List<TraktMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktShowDto>? = null,
    @Json(name = "seasons") val seasons: List<TraktHistorySeasonAddDto>? = null,
    @Json(name = "episodes") val episodes: List<TraktEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktHistoryItemDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "watched_at") val watchedAt: String? = null,
    @Json(name = "action") val action: String? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "episode") val episode: TraktEpisodeDto? = null
)
