package com.nexio.tv.data.remote.api

import com.nexio.tv.data.remote.dto.debrid.PremiumizeAccountInfoDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeItemDetailsDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeListAllDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface PremiumizeApi {
    @GET("account/info")
    suspend fun getAccountInfo(
        @Query("apikey") apiKey: String
    ): Response<PremiumizeAccountInfoDto>

    @GET("item/listall")
    suspend fun listAllItems(
        @Query("apikey") apiKey: String
    ): Response<PremiumizeListAllDto>

    @GET("item/details")
    suspend fun getItemDetails(
        @Query("apikey") apiKey: String,
        @Query("id") id: String
    ): Response<PremiumizeItemDetailsDto>
}
