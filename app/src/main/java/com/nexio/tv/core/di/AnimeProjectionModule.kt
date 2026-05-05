package com.nexio.tv.core.di

import com.nexio.tv.core.anime.projection.AnimeEpisodeCoordinateStore
import com.nexio.tv.core.anime.projection.AnimeSeasonProjectionResolver
import com.nexio.tv.core.anime.projection.DefaultAnimeSeasonProjectionResolver
import com.nexio.tv.core.anime.projection.InMemoryAnimeEpisodeCoordinateStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AnimeProjectionModule {
    @Binds
    abstract fun bindResolver(impl: DefaultAnimeSeasonProjectionResolver): AnimeSeasonProjectionResolver

    @Binds
    abstract fun bindStore(impl: InMemoryAnimeEpisodeCoordinateStore): AnimeEpisodeCoordinateStore
}
