package com.nexio.tv.data.remote.api

import com.nexio.tv.data.remote.dto.debrid.EasyDebridGenerateDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridGenerateRequestDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridLookupDetailsDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridLookupDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridLookupRequestDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridUserDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface EasyDebridApi {
    @GET("user/details")
    suspend fun getUserDetails(
        @Header("Authorization") authorization: String
    ): Response<EasyDebridUserDto>

    @POST("link/lookup")
    suspend fun lookup(
        @Header("Authorization") authorization: String,
        @Body body: EasyDebridLookupRequestDto
    ): Response<EasyDebridLookupDto>

    @POST("link/lookupdetails")
    suspend fun lookupDetails(
        @Header("Authorization") authorization: String,
        @Body body: EasyDebridLookupRequestDto
    ): Response<EasyDebridLookupDetailsDto>

    @POST("link/generate")
    suspend fun generate(
        @Header("Authorization") authorization: String,
        @Body body: EasyDebridGenerateRequestDto
    ): Response<EasyDebridGenerateDto>
}
