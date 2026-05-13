package com.nexio.tv.data.integration.fanarttv

import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

// TODO(T4.4): Once RuntimeFanartTvLookup is implemented, convert this to an abstract class,
//             add the @Binds abstract fun, and re-enable FanartTvLookup binding:
//
// import com.nexio.tv.core.artwork.fanarttv.FanartTvLookup
// import dagger.Binds
// abstract class FanartTvApiModule {
//     @Binds @Singleton
//     abstract fun bindFanartTvLookup(impl: RuntimeFanartTvLookup): FanartTvLookup
//     companion object { ... providers below ... }
// }

@Module
@InstallIn(SingletonComponent::class)
object FanartTvApiModule {

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
