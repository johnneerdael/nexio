package com.nexio.tv.data.remote.api

import com.nexio.tv.data.remote.dto.AddonManifestDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

interface AddonManifestApi {
    @GET
    suspend fun getManifest(@Url manifestUrl: String): Response<AddonManifestDto>
}
