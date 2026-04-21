package com.nexio.tv.data.remote.api

import com.nexio.tv.data.remote.dto.OpenSubtitlesRestSubtitleDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

/**
 * Retrofit binding for the legacy `rest.opensubtitles.org` JSON search endpoint.
 *
 * The endpoint is open (no auth) but requires a User-Agent — pass one explicitly
 * via the [userAgent] header so callers can register their own.
 *
 * Path is supplied as a full URL because the rest.opensubtitles.org filter
 * ordering is positional and hand-built by [com.nexio.tv.data.remote.api.OpenSubtitlesApiClient].
 */
interface OpenSubtitlesRestApi {

    @GET
    suspend fun search(
        @Url url: String,
        @Header("User-Agent") userAgent: String
    ): Response<List<OpenSubtitlesRestSubtitleDto>>
}
