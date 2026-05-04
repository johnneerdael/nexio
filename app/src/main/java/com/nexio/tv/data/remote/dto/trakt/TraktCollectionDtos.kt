package com.nexio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ── GET /sync/collection/movies ──────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class TraktCollectionMovieItemDto(
    @Json(name = "collected_at") val collectedAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "metadata") val metadata: TraktCollectionMetadataDto? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null
)

// ── GET /sync/collection/shows ────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class TraktCollectionShowItemDto(
    @Json(name = "last_collected_at") val lastCollectedAt: String? = null,
    @Json(name = "last_updated_at") val lastUpdatedAt: String? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "seasons") val seasons: List<TraktCollectionSeasonDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionSeasonDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "episodes") val episodes: List<TraktCollectionEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionEpisodeDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "collected_at") val collectedAt: String? = null,
    @Json(name = "metadata") val metadata: TraktCollectionMetadataDto? = null
)

// ── Metadata object (returned by ?extended=metadata) ─────────────────────────
// Field `3d` uses @Json alias because `3d` is not a valid Kotlin identifier.

@JsonClass(generateAdapter = true)
data class TraktCollectionMetadataDto(
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "resolution") val resolution: String? = null,
    @Json(name = "hdr") val hdr: String? = null,
    @Json(name = "audio") val audio: String? = null,
    @Json(name = "audio_channels") val audioChannels: String? = null,
    @Json(name = "3d") val threeD: Boolean? = null
)

// ── POST /sync/collection (Add) ───────────────────────────────────────────────
// Per trakt.apib the add-request encodes media metadata as flat fields on the
// movie / episode object, not nested inside a `metadata` wrapper.

@JsonClass(generateAdapter = true)
data class TraktCollectionAddRequestDto(
    @Json(name = "movies") val movies: List<TraktCollectionAddMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktCollectionAddShowDto>? = null,
    @Json(name = "seasons") val seasons: List<TraktCollectionAddSeasonDto>? = null,
    @Json(name = "episodes") val episodes: List<TraktCollectionAddEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionAddMovieDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "collected_at") val collectedAt: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "resolution") val resolution: String? = null,
    @Json(name = "hdr") val hdr: String? = null,
    @Json(name = "audio") val audio: String? = null,
    @Json(name = "audio_channels") val audioChannels: String? = null,
    @Json(name = "3d") val threeD: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionAddShowDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "collected_at") val collectedAt: String? = null,
    @Json(name = "seasons") val seasons: List<TraktCollectionAddSeasonDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionAddSeasonDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "episodes") val episodes: List<TraktCollectionAddEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionAddEpisodeDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "collected_at") val collectedAt: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "resolution") val resolution: String? = null,
    @Json(name = "hdr") val hdr: String? = null,
    @Json(name = "audio") val audio: String? = null,
    @Json(name = "audio_channels") val audioChannels: String? = null,
    @Json(name = "3d") val threeD: Boolean? = null
)

// ── POST /sync/collection (Add) response ─────────────────────────────────────
// apib shows: added, updated, existing, not_found — plan snippet omitted `updated`.

@JsonClass(generateAdapter = true)
data class TraktCollectionAddResponseDto(
    @Json(name = "added") val added: TraktCollectionWriteCountsDto? = null,
    @Json(name = "updated") val updated: TraktCollectionWriteCountsDto? = null,
    @Json(name = "existing") val existing: TraktCollectionWriteCountsDto? = null,
    @Json(name = "not_found") val notFound: TraktCollectionWriteNotFoundDto? = null
)

// ── POST /sync/collection/remove ─────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class TraktCollectionRemoveRequestDto(
    @Json(name = "movies") val movies: List<TraktCollectionRemoveMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktCollectionRemoveShowDto>? = null,
    @Json(name = "seasons") val seasons: List<TraktCollectionRemoveSeasonDto>? = null,
    @Json(name = "episodes") val episodes: List<TraktCollectionRemoveEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionRemoveMovieDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionRemoveShowDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "seasons") val seasons: List<TraktCollectionRemoveSeasonDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionRemoveSeasonDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "episodes") val episodes: List<TraktCollectionRemoveEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktCollectionRemoveEpisodeDto(
    @Json(name = "number") val number: Int? = null
)

// ── POST /sync/collection/remove response ────────────────────────────────────

@JsonClass(generateAdapter = true)
data class TraktCollectionRemoveResponseDto(
    @Json(name = "deleted") val deleted: TraktCollectionWriteCountsDto? = null,
    @Json(name = "not_found") val notFound: TraktCollectionWriteNotFoundDto? = null
)

// ── Shared write-response supporting types ────────────────────────────────────

@JsonClass(generateAdapter = true)
data class TraktCollectionWriteCountsDto(
    @Json(name = "movies") val movies: Int? = null,
    @Json(name = "episodes") val episodes: Int? = null
)

// Used by both add and remove responses; per apib includes movies, shows, seasons, episodes.
@JsonClass(generateAdapter = true)
data class TraktCollectionWriteNotFoundDto(
    @Json(name = "movies") val movies: List<TraktCollectionRemoveMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktCollectionRemoveShowDto>? = null,
    @Json(name = "seasons") val seasons: List<TraktCollectionRemoveSeasonDto>? = null,
    @Json(name = "episodes") val episodes: List<TraktCollectionRemoveEpisodeDto>? = null
)
