package com.nexio.tv.data.integration.fanarttv

import com.nexio.tv.core.artwork.fanarttv.FanartTvLookup
import com.squareup.moshi.Moshi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@Module
@InstallIn(SingletonComponent::class)
abstract class FanartTvApiModule {

    @Binds
    @Singleton
    abstract fun bindFanartTvLookup(impl: FanartTvLookupShape): FanartTvLookup

    companion object {
        @Provides
        @Singleton
        @Named("fanartTv")
        fun provideFanartTvRetrofit(
            okHttpClient: OkHttpClient,
            moshi: Moshi
        ): Retrofit = Retrofit.Builder()
            .baseUrl("https://webservice.fanart.tv/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        @Provides
        @Singleton
        fun provideFanartTvApi(
            @Named("fanartTv") retrofit: Retrofit
        ): FanartTvApi = retrofit.create(FanartTvApi::class.java)
    }
}
