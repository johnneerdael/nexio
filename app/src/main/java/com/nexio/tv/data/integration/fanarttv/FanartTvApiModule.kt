package com.nexio.tv.data.integration.fanarttv

import com.nexio.tv.BuildConfig
import com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolver
import com.nexio.tv.core.artwork.fanarttv.FanartTvAvailability
import com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGenerator
import com.nexio.tv.core.artwork.fanarttv.FanartTvIdSelector
import com.nexio.tv.core.artwork.fanarttv.FanartTvImagePicker
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
    abstract fun bindFanartTvLookup(impl: FanartTvIntegrationProvider): FanartTvLookup

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

        @Provides
        @Singleton
        fun provideFanartTvCandidateGenerator(
            lookup: FanartTvLookup,
            capabilityResolver: ArtworkProviderCapabilityResolver
        ): FanartTvCandidateGenerator =
            FanartTvCandidateGenerator(
                availabilityProvider = {
                    FanartTvAvailability.from(BuildConfig.FANARTTV_API_KEY)
                },
                idSelector = FanartTvIdSelector(),
                picker = FanartTvImagePicker(),
                lookup = lookup,
                capabilityResolver = capabilityResolver
            )
    }
}
