package com.nexio.tv.core.di

import android.content.Context
import com.nexio.tv.core.player.OpenSubtitlesHasher
import com.nexio.tv.data.remote.api.OpenSubtitlesApiClient
import com.nexio.tv.data.remote.api.OpenSubtitlesArchiveExtractor
import com.nexio.tv.data.remote.api.OpenSubtitlesRestApi
import com.nexio.tv.data.repository.OpenSubtitlesSourceImpl
import com.nexio.tv.domain.repository.OpenSubtitlesSource
import com.squareup.moshi.Moshi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

/**
 * Wires the native OpenSubtitles source: rest.opensubtitles.org Retrofit
 * client, the on-disk subtitle cache, and the hash-computation seam used by
 * [OpenSubtitlesSourceImpl] for moviehash searches.
 *
 * Kept in its own module so the rest.opensubtitles.org base URL and User-Agent
 * are easy to swap (e.g. for a self-hosted mirror) without touching the
 * shared NetworkModule.
 */
@Module
@InstallIn(SingletonComponent::class)
object OpenSubtitlesModule {

    private const val USER_AGENT = "Nexio/1.0"

    @Provides
    @Singleton
    @Named("openSubtitles")
    fun provideOpenSubtitlesRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("${OpenSubtitlesApiClient.DEFAULT_BASE_URL}/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideOpenSubtitlesRestApi(@Named("openSubtitles") retrofit: Retrofit): OpenSubtitlesRestApi =
        retrofit.create(OpenSubtitlesRestApi::class.java)

    @Provides
    @Singleton
    fun provideOpenSubtitlesApiClient(api: OpenSubtitlesRestApi): OpenSubtitlesApiClient =
        OpenSubtitlesApiClient(api = api, userAgent = USER_AGENT)

    @Provides
    @Singleton
    fun provideOpenSubtitlesArchiveExtractor(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient
    ): OpenSubtitlesArchiveExtractor = OpenSubtitlesArchiveExtractor(
        okHttpClient = okHttpClient,
        cacheDir = File(context.cacheDir, "opensubtitles"),
        userAgent = USER_AGENT
    )

    @Provides
    @Singleton
    fun provideHashComputer(): OpenSubtitlesSourceImpl.HashComputer =
        OpenSubtitlesSourceImpl.HashComputer { url, headers ->
            OpenSubtitlesHasher.compute(url, headers)
        }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class OpenSubtitlesBindingModule {

    @Binds
    @Singleton
    abstract fun bindOpenSubtitlesSource(impl: OpenSubtitlesSourceImpl): OpenSubtitlesSource
}
