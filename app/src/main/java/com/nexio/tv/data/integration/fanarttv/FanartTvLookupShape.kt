package com.nexio.tv.data.integration.fanarttv

import com.nexio.tv.core.artwork.fanarttv.FanartTvCallId
import com.nexio.tv.core.artwork.fanarttv.dto.FanartTvDocument
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface FanartTvLookupShape {
    @GET("v3.2/movies/{tmdbId}")
    suspend fun getMovie(
        @Path("tmdbId") tmdbId: String,
        @Query("api_key") apiKey: String
    ): FanartTvDocument

    @GET("v3.2/tv/{tvdbId}")
    suspend fun getTv(
        @Path("tvdbId") tvdbId: String,
        @Query("api_key") apiKey: String
    ): FanartTvDocument
}
