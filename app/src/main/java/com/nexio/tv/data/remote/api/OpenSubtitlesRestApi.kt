package com.nexio.tv.data.remote.api

import com.nexio.tv.data.remote.dto.OpenSubtitlesRestSubtitleDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

interface OpenSubtitlesRestApi {
    @GET
    suspend fun search(
        @Url url: String,
        @Header("User-Agent") userAgent: String
    ): Response<List<OpenSubtitlesRestSubtitleDto>>
}
