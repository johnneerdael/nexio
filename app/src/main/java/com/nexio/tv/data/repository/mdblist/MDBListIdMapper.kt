package com.nexio.tv.data.repository.mdblist

import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleEpisodeDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleIdsDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleMovieDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleRequestDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleSeasonDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleShowDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchlistItemIdsDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchlistMutationRequestDto
import com.nexio.tv.data.repository.TrackingScrobbleItem
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.ProviderIds

object MDBListIdMapper {
    fun watchlistPayloadFor(item: LibraryEntryInput): MDBListWatchlistMutationRequestDto {
        val ids = MDBListWatchlistItemIdsDto(
            imdb = item.imdbId?.takeIf { it.isNotBlank() },
            tmdb = item.tmdbId,
        )
        return if (item.itemType.equals("movie", ignoreCase = true)) {
            MDBListWatchlistMutationRequestDto(movies = listOf(ids))
        } else {
            MDBListWatchlistMutationRequestDto(shows = listOf(ids))
        }
    }

    fun scrobblePayloadFor(item: TrackingScrobbleItem, progressPercent: Float): MDBListScrobbleRequestDto {
        val progress = progressPercent.coerceIn(0f, 100f).toDouble()
        return when (item) {
            is TrackingScrobbleItem.Movie -> MDBListScrobbleRequestDto(
                movie = MDBListScrobbleMovieDto(ids = scrobbleIdsFor(item)),
                progress = progress,
            )
            is TrackingScrobbleItem.Episode -> MDBListScrobbleRequestDto(
                show = MDBListScrobbleShowDto(
                    ids = scrobbleIdsFor(item),
                    season = MDBListScrobbleSeasonDto(
                        number = item.season,
                        episode = MDBListScrobbleEpisodeDto(number = item.number),
                    ),
                ),
                progress = progress,
            )
        }
    }

    fun idsFrom(imdb: String?, tmdb: Int?, tvdb: Int?): ProviderIds = ProviderIds(
        imdb = imdb?.takeIf { it.isNotBlank() },
        tmdb = tmdb?.toString(),
        tvdb = tvdb?.toString(),
    )

    private fun scrobbleIdsFor(item: TrackingScrobbleItem): MDBListScrobbleIdsDto {
        val ids = item.hydratedIds
        val parsed = parseContentId(item.contentId)
        return MDBListScrobbleIdsDto(
            tmdb = ids?.tmdb?.toIntOrNull() ?: parsed.tmdb,
            imdb = ids?.imdb?.takeIf { it.isNotBlank() } ?: parsed.imdb,
            tvdb = ids?.tvdb?.toIntOrNull(),
        )
    }

    private fun parseContentId(contentId: String): ParsedIds {
        val trimmed = contentId.trim()
        return when {
            trimmed.startsWith("tt") -> ParsedIds(imdb = trimmed)
            trimmed.startsWith("imdb:", ignoreCase = true) -> ParsedIds(imdb = trimmed.substringAfter(':').takeIf { it.isNotBlank() })
            trimmed.startsWith("tmdb:", ignoreCase = true) -> ParsedIds(tmdb = trimmed.substringAfter(':').toIntOrNull())
            else -> ParsedIds()
        }
    }

    private data class ParsedIds(
        val imdb: String? = null,
        val tmdb: Int? = null,
    )
}
