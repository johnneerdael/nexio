package com.nexio.tv.data.remote.dto.mdblist

import com.squareup.moshi.Json

data class MDBListSyncIdsDto(
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "tmdb") val tmdb: Int? = null,
    @Json(name = "tvdb") val tvdb: Int? = null,
    @Json(name = "trakt") val trakt: Int? = null,
    @Json(name = "mdblist") val mdblist: String? = null,
    @Json(name = "kitsu") val kitsu: Int? = null,
)

data class MDBListPlaybackResponseDto(
    @Json(name = "movies") val movies: List<MoviePlayback>? = null,
    @Json(name = "episodes") val episodes: List<EpisodePlayback>? = null,
) {
    data class MoviePlayback(
        @Json(name = "id") val id: Long? = null,
        @Json(name = "progress") val progress: Double? = null,
        @Json(name = "paused_at") val pausedAt: String? = null,
        @Json(name = "movie") val movie: Movie? = null,
    )

    data class EpisodePlayback(
        @Json(name = "id") val id: Long? = null,
        @Json(name = "progress") val progress: Double? = null,
        @Json(name = "paused_at") val pausedAt: String? = null,
        @Json(name = "episode") val episode: Episode? = null,
        @Json(name = "show") val show: Show? = null,
    )

    data class Movie(
        @Json(name = "title") val title: String? = null,
        @Json(name = "year") val year: Int? = null,
        @Json(name = "ids") val ids: MDBListSyncIdsDto? = null,
    )

    data class Episode(
        @Json(name = "season") val season: Int? = null,
        @Json(name = "number") val number: Int? = null,
        @Json(name = "name") val name: String? = null,
        @Json(name = "ids") val ids: MDBListSyncIdsDto? = null,
        @Json(name = "show") val show: Show? = null,
    )

    data class Show(
        @Json(name = "title") val title: String? = null,
        @Json(name = "year") val year: Int? = null,
        @Json(name = "ids") val ids: MDBListSyncIdsDto = MDBListSyncIdsDto(),
    )
}

data class MDBListWatchedResponseDto(
    @Json(name = "movies") val movies: List<MovieWatched>? = null,
    @Json(name = "shows") val shows: List<ShowWatched>? = null,
    @Json(name = "seasons") val seasons: List<SeasonWatched>? = null,
    @Json(name = "episodes") val episodes: List<EpisodeWatched>? = null,
)

data class MDBListWatchedSyncRequestDto(
    @Json(name = "movies") val movies: List<Movie>? = null,
    @Json(name = "shows") val shows: List<Show>? = null,
) {
    data class Movie(
        @Json(name = "title") val title: String? = null,
        @Json(name = "year") val year: Int? = null,
        @Json(name = "ids") val ids: MDBListSyncIdsDto,
        @Json(name = "watched_at") val watchedAt: String? = null,
    )

    data class Show(
        @Json(name = "title") val title: String? = null,
        @Json(name = "year") val year: Int? = null,
        @Json(name = "ids") val ids: MDBListSyncIdsDto,
        @Json(name = "seasons") val seasons: List<Season>? = null,
        @Json(name = "watched_at") val watchedAt: String? = null,
    )

    data class Season(
        @Json(name = "number") val number: Int,
        @Json(name = "episodes") val episodes: List<Episode>? = null,
        @Json(name = "watched_at") val watchedAt: String? = null,
    )

    data class Episode(
        @Json(name = "number") val number: Int,
        @Json(name = "watched_at") val watchedAt: String? = null,
    )
}

data class MDBListScrobbleClearRequestDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "movie") val movie: MDBListScrobbleMovieDto? = null,
    @Json(name = "show") val show: MDBListScrobbleShowDto? = null,
)

data class MDBListScrobbleClearResponseDto(
    @Json(name = "action") val action: String? = null,
    @Json(name = "deleted") val deleted: Boolean? = null,
)

data class MovieWatched(
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "movie") val movie: MDBListPlaybackResponseDto.Movie? = null,
)

data class ShowWatched(
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "show") val show: MDBListPlaybackResponseDto.Show? = null,
)

data class SeasonWatched(
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "season") val season: MDBListWatchedSeasonDto? = null,
)

data class EpisodeWatched(
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "episode") val episode: MDBListPlaybackResponseDto.Episode? = null,
)

data class MDBListWatchedSeasonDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "ids") val ids: MDBListSyncIdsDto? = null,
    @Json(name = "show") val show: MDBListPlaybackResponseDto.Show? = null,
)
