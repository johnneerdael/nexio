package com.nexio.tv.core.tvdb

import android.util.Log
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.remote.api.TvdbCharacterRecord
import com.nexio.tv.data.remote.api.TvdbPersonExtendedRecord
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PersonDetail
import com.nexio.tv.domain.model.PosterShape
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "TvdbPersonService"
private const val UNKNOWN_TMDB_ID = 0

@Singleton
class TvdbPersonService @Inject constructor(
    private val provider: TvdbIntegrationProvider
) {
    suspend fun fetchPersonDetail(
        peopleId: Int,
        preferredLanguage: String? = "eng"
    ): PersonDetail? = withContext(Dispatchers.IO) {
        if (peopleId <= 0) return@withContext null

        val record = provider.fetchPersonExtended(peopleId)
            ?: return@withContext null

        record.toPersonDetail(preferredLanguage)
    }

    private fun TvdbPersonExtendedRecord.toPersonDetail(preferredLanguage: String?): PersonDetail? {
        val id = id ?: return null
        val name = name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val biography = biographies.orEmpty()
            .firstOrNull { it.language.equals(preferredLanguage, ignoreCase = true) }
            ?.biography
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: biographies.orEmpty()
                .firstNotNullOfOrNull { it.biography?.trim()?.takeIf { bio -> bio.isNotBlank() } }
        val seriesCredits = characters.orEmpty().mapNotNull { it.toSeriesPreview() }.distinctBy { it.id }
        val movieCredits = characters.orEmpty().mapNotNull { it.toMoviePreview() }.distinctBy { it.id }

        return PersonDetail(
            tmdbId = UNKNOWN_TMDB_ID,
            name = name,
            biography = biography,
            birthday = birth?.trim()?.takeIf { it.isNotBlank() },
            deathday = death?.trim()?.takeIf { it.isNotBlank() },
            placeOfBirth = birthPlace?.trim()?.takeIf { it.isNotBlank() },
            profilePhoto = image?.trim()?.takeIf { it.isNotBlank() },
            knownFor = null,
            movieCredits = movieCredits,
            tvCredits = seriesCredits
        )
    }

    private fun TvdbCharacterRecord.toSeriesPreview(): MetaPreview? {
        val id = seriesId ?: return null
        val title = series?.name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return MetaPreview(
            id = "tvdb:$id",
            type = ContentType.SERIES,
            name = title,
            poster = series.image?.trim()?.takeIf { it.isNotBlank() },
            posterShape = PosterShape.POSTER,
            background = series.image?.trim()?.takeIf { it.isNotBlank() },
            logo = null,
            description = name?.trim()?.takeIf { it.isNotBlank() },
            releaseInfo = series.year?.trim()?.takeIf { it.isNotBlank() },
            imdbRating = null,
            genres = emptyList()
        )
    }

    private fun TvdbCharacterRecord.toMoviePreview(): MetaPreview? {
        val id = movieId ?: return null
        val title = movie?.name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return MetaPreview(
            id = "tvdb-movie:$id",
            type = ContentType.MOVIE,
            name = title,
            poster = movie.image?.trim()?.takeIf { it.isNotBlank() },
            posterShape = PosterShape.POSTER,
            background = movie.image?.trim()?.takeIf { it.isNotBlank() },
            logo = null,
            description = name?.trim()?.takeIf { it.isNotBlank() },
            releaseInfo = movie.year?.trim()?.takeIf { it.isNotBlank() },
            imdbRating = null,
            genres = emptyList()
        )
    }
}
