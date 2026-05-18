package com.nexio.tv.core.di

import com.nexio.tv.data.repository.DefaultTvEpisodeOrderResolver
import com.nexio.tv.data.repository.FileTvEpisodeOrderOverrideRepository
import com.nexio.tv.data.repository.TvEpisodeOrderOverrideRepository
import com.nexio.tv.data.repository.TvEpisodeOrderResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TvEpisodeOrderModule {

    @Binds
    @Singleton
    abstract fun bindTvEpisodeOrderOverrideRepository(
        impl: FileTvEpisodeOrderOverrideRepository
    ): TvEpisodeOrderOverrideRepository

    @Binds
    @Singleton
    abstract fun bindTvEpisodeOrderResolver(
        impl: DefaultTvEpisodeOrderResolver
    ): TvEpisodeOrderResolver
}
