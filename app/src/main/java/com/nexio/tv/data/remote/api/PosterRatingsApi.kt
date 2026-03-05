package com.nexio.tv.data.remote.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface RpdbApi {
    @GET("{apiKey}/isValid")
    suspend fun verifyApiKey(
        @Path("apiKey", encoded = true) apiKey: String
    ): Response<ResponseBody>
}

interface TopPostersApi {
    @GET("auth/verify/{apiKey}")
    suspend fun verifyApiKey(
        @Path("apiKey", encoded = true) apiKey: String
    ): Response<ResponseBody>
}

