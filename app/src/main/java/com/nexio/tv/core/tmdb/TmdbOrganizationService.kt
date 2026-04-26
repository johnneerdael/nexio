package com.nexio.tv.core.tmdb

import android.util.Log
import com.nexio.tv.data.remote.api.TmdbDiscoverResult
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaCompanyKind
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.OrganizationDiscoverType
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TmdbOrganizationDetail
import com.nexio.tv.data.integration.tmdb.DefaultTmdbOrganizationProvider
import com.nexio.tv.data.integration.tmdb.TmdbOrganizationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbOrganizationSvc"

@Singleton
class TmdbOrganizationService(
    private val tmdbOrganizationProvider: TmdbOrganizationProvider
) {
    @Inject
    constructor(
        defaultTmdbOrganizationProvider: DefaultTmdbOrganizationProvider
    ) : this(
        tmdbOrganizationProvider = defaultTmdbOrganizationProvider
    )

    suspend fun fetchOrganizationDetail(
        entityId: Int,
        kind: MetaCompanyKind,
        discoverType: OrganizationDiscoverType,
        language: String? = "en-US",
        maxItems: Int = 20
    ): TmdbOrganizationDetail? = withContext(Dispatchers.IO) {
        val normalizedLanguage = language?.trim()?.takeIf { it.isNotBlank() } ?: "en-US"

        try {
            when (kind) {
                MetaCompanyKind.COMPANY -> {
                    val detail = tmdbOrganizationProvider.getCompanyDetails(entityId) ?: return@withContext null
                    val discover = when (discoverType) {
                        OrganizationDiscoverType.MOVIE_COMPANY ->
                            tmdbOrganizationProvider.discoverMoviesByCompany(entityId, normalizedLanguage)
                        OrganizationDiscoverType.TV_COMPANY ->
                            tmdbOrganizationProvider.discoverTvByCompany(entityId, normalizedLanguage)
                        OrganizationDiscoverType.TV_NETWORK -> return@withContext null
                    }
                    TmdbOrganizationDetail(
                        tmdbId = detail.id,
                        kind = kind,
                        name = detail.name.orEmpty(),
                        description = detail.description?.takeIf { it.isNotBlank() },
                        headquarters = detail.headquarters?.takeIf { it.isNotBlank() },
                        homepage = detail.homepage?.takeIf { it.isNotBlank() },
                        originCountry = detail.originCountry?.takeIf { it.isNotBlank() },
                        logo = buildImageUrl(detail.logoPath, "w300"),
                        parentCompanyName = detail.parentCompany?.name?.takeIf { it.isNotBlank() },
                        titles = discover.orEmpty().results.orEmpty().take(maxItems).map {
                            it.toMetaPreview(
                                if (discoverType == OrganizationDiscoverType.MOVIE_COMPANY) {
                                    ContentType.MOVIE
                                } else {
                                    ContentType.SERIES
                                }
                            )
                        },
                        totalResults = discover?.totalResults ?: 0
                    )
                }

                MetaCompanyKind.NETWORK -> {
                    val detail = tmdbOrganizationProvider.getNetworkDetails(entityId) ?: return@withContext null
                    val discover = tmdbOrganizationProvider.discoverTvByNetwork(entityId, normalizedLanguage)
                    TmdbOrganizationDetail(
                        tmdbId = detail.id,
                        kind = kind,
                        name = detail.name.orEmpty(),
                        description = null,
                        headquarters = detail.headquarters?.takeIf { it.isNotBlank() },
                        homepage = detail.homepage?.takeIf { it.isNotBlank() },
                        originCountry = detail.originCountry?.takeIf { it.isNotBlank() },
                        logo = buildImageUrl(detail.logoPath, "w300"),
                        parentCompanyName = null,
                        titles = discover.orEmpty().results.orEmpty().take(maxItems).map {
                            it.toMetaPreview(ContentType.SERIES)
                        },
                        totalResults = discover?.totalResults ?: 0
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch organization detail: ${e.message}", e)
            null
        }
    }

    private fun buildImageUrl(path: String?, size: String): String? {
        val clean = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "https://image.tmdb.org/t/p/$size$clean"
    }

    private fun TmdbDiscoverResult.toMetaPreview(contentType: ContentType): MetaPreview {
        return MetaPreview(
            id = "tmdb:$id",
            type = contentType,
            name = title ?: name.orEmpty(),
            poster = buildImageUrl(posterPath, "w500"),
            posterShape = PosterShape.POSTER,
            background = buildImageUrl(backdropPath, "w1280"),
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = (releaseDate ?: firstAirDate)?.take(4),
            imdbRating = voteAverage?.toFloat(),
            genres = emptyList()
        )
    }
}

private fun com.nexio.tv.data.remote.api.TmdbDiscoverResponse?.orEmpty() =
    this ?: com.nexio.tv.data.remote.api.TmdbDiscoverResponse(results = emptyList(), totalResults = 0)
