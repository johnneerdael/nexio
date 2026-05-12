package com.nexio.tv.data.remote.dto.simkl

import com.google.gson.annotations.SerializedName
import com.squareup.moshi.Json

data class SimklLastActivitiesResponseDto(
    @Json(name = "all") val all: String? = null,
    @Json(name = "tv_shows") val tvShows: SimklActivityBucketDto? = null,
    @Json(name = "anime") val anime: SimklActivityBucketDto? = null,
    @Json(name = "movies") val movies: SimklActivityBucketDto? = null
)

data class SimklActivityBucketDto(
    @Json(name = "all") val all: String? = null,
    @Json(name = "playback") val playback: String? = null,
    @Json(name = "plantowatch") val planToWatch: String? = null,
    @Json(name = "watching") val watching: String? = null,
    @Json(name = "completed") val completed: String? = null,
    @Json(name = "hold") val hold: String? = null,
    @Json(name = "dropped") val dropped: String? = null,
    @Json(name = "removed_from_list") val removedFromList: String? = null,
    @Json(name = "rated_at") val ratedAt: String? = null
)

data class SimklIdsDto(
    @Json(name = "simkl") val simkl: Long? = null,
    @Json(name = "simkl_id") @SerializedName("simkl_id") val simklId: Long? = null,
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "tmdb") val tmdb: String? = null,
    @Json(name = "tvdb") val tvdb: String? = null,
    @Json(name = "mal") val mal: String? = null,
    @Json(name = "anilist") val anilist: String? = null,
    @Json(name = "kitsu") val kitsu: String? = null,
    @Json(name = "anidb") val anidb: String? = null
)

data class SimklEpisodeDto(
    @Json(name = "season") val season: Int? = null,
    @Json(name = "episode") val episode: Int? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "date") val date: String? = null,
    @Json(name = "ids") val ids: SimklIdsDto? = null,
    @Json(name = "tvdb_season") val tvdbSeason: Int? = null,
    @Json(name = "tvdb_number") val tvdbNumber: Int? = null
)

data class SimklMediaRefDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: SimklIdsDto? = null
)

data class SimklPlaybackItemDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "progress") val progress: Float? = null,
    @Json(name = "paused_at") val pausedAt: String? = null,
    @Json(name = "watched_at") val watchedAt: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "movie") val movie: SimklMediaRefDto? = null,
    @Json(name = "show") val show: SimklMediaRefDto? = null,
    @Json(name = "anime") val anime: SimklMediaRefDto? = null,
    @Json(name = "episode") val episode: SimklEpisodeDto? = null
)

data class SimklLibraryItemDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "last_updated_at") val lastUpdatedAt: String? = null,
    @Json(name = "date") val date: String? = null,
    @Json(name = "released") val released: String? = null,
    @Json(name = "poster") val poster: String? = null,
    @Json(name = "anime_type") val animeType: String? = null,
    @Json(name = "ids") val ids: SimklIdsDto? = null,
    @Json(name = "show") val show: SimklMediaRefDto? = null,
    @Json(name = "movie") val movie: SimklMediaRefDto? = null,
    @Json(name = "anime") val anime: SimklMediaRefDto? = null,
    @Json(name = "episode") val episode: SimklEpisodeDto? = null
)

data class SimklScrobbleRequestDto(
    @Json(name = "progress") val progress: Float? = null,
    @Json(name = "movie") val movie: SimklMediaRefDto? = null,
    @Json(name = "show") val show: SimklMediaRefDto? = null,
    @Json(name = "anime") val anime: SimklMediaRefDto? = null,
    @Json(name = "episode") val episode: SimklEpisodeDto? = null
)

data class SimklScrobbleResponseDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "action") val action: String? = null,
    @Json(name = "progress") val progress: Float? = null,
    @Json(name = "watched_at") val watchedAt: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "movie") val movie: SimklMediaRefDto? = null,
    @Json(name = "show") val show: SimklMediaRefDto? = null,
    @Json(name = "anime") val anime: SimklMediaRefDto? = null,
    @Json(name = "episode") val episode: SimklEpisodeDto? = null
)

data class SimklAddToListRequestDto(
    @Json(name = "to") val to: String,
    @Json(name = "movies") val movies: List<SimklMediaRefDto>? = null,
    @Json(name = "shows") val shows: List<SimklMediaRefDto>? = null,
    @Json(name = "anime") val anime: List<SimklMediaRefDto>? = null,
    @Json(name = "watched_at") val watchedAt: String? = null
)

data class SimklHistoryEpisodePayloadDto(
    @Json(name = "number") val number: Int,
    @Json(name = "watched_at") val watchedAt: String? = null
)

data class SimklHistorySeasonPayloadDto(
    @Json(name = "number") val number: Int,
    @Json(name = "episodes") val episodes: List<SimklHistoryEpisodePayloadDto>? = null
)

data class SimklHistoryShowPayloadDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "ids") val ids: SimklIdsDto? = null,
    @Json(name = "watched_at") val watchedAt: String? = null,
    @Json(name = "seasons") val seasons: List<SimklHistorySeasonPayloadDto>? = null
)

data class SimklHistoryAddRequestDto(
    @Json(name = "movies") val movies: List<SimklMediaRefDto>? = null,
    @Json(name = "shows") val shows: List<SimklHistoryShowPayloadDto>? = null,
    @Json(name = "anime") val anime: List<SimklHistoryShowPayloadDto>? = null,
    @Json(name = "episodes") val episodes: List<SimklEpisodeDto>? = null
)

data class SimklHistoryRemoveRequestDto(
    @Json(name = "movies") val movies: List<SimklMediaRefDto>? = null,
    @Json(name = "shows") val shows: List<SimklHistoryShowPayloadDto>? = null,
    @Json(name = "anime") val anime: List<SimklHistoryShowPayloadDto>? = null
)

data class SimklAddToListResponseDto(
    @Json(name = "result") val result: String? = null,
    @Json(name = "movies") val movies: List<SimklLibraryItemDto>? = null,
    @Json(name = "shows") val shows: List<SimklLibraryItemDto>? = null,
    @Json(name = "anime") val anime: List<SimklLibraryItemDto>? = null
)
