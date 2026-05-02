package com.nexio.tv.data.integration.subtitles.wyzie.transport

import com.nexio.tv.data.remote.dto.WyzieSubtitleDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit binding for https://sub.wyzie.io.
 *
 * Only `/search` is wired; `/sources` is not used (per design Q3 the source list is hardcoded
 * client-side and we don't need upstream availability data).
 */
interface WyzieSubtitleApi {

    @GET("search")
    suspend fun search(
        @Query("id") id: String,
        @Query("source") source: String,
        @Query("format") format: String = "srt,ass,vtt",
        @Query("season") season: Int? = null,
        @Query("episode") episode: Int? = null,
    ): Response<List<WyzieSubtitleDto>>
}
