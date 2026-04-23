package com.nexio.tv.data.integration.tmdb

import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbExternalIdsResponse
import com.nexio.tv.data.remote.api.TmdbFindResponse
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmdbExternalIdLookupProvider @Inject constructor(
    private val tmdbApi: TmdbApi
) {

    suspend fun findByExternalId(
        externalId: String,
        apiKey: String
    ): Response<TmdbFindResponse> {
        return tmdbApi.findByExternalId(
            externalId = externalId,
            apiKey = apiKey,
            externalSource = "imdb_id"
        )
    }

    suspend fun getMovieExternalIds(
        tmdbId: Int,
        apiKey: String
    ): Response<TmdbExternalIdsResponse> {
        return tmdbApi.getMovieExternalIds(movieId = tmdbId, apiKey = apiKey)
    }

    suspend fun getTvExternalIds(
        tmdbId: Int,
        apiKey: String
    ): Response<TmdbExternalIdsResponse> {
        return tmdbApi.getTvExternalIds(tvId = tmdbId, apiKey = apiKey)
    }
}

