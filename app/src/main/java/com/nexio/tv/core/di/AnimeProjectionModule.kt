package com.nexio.tv.core.di

import com.nexio.tv.core.anime.projection.AnimeEpisodeCoordinateStore
import com.nexio.tv.core.anime.projection.AnimeSeasonDetailRepository
import com.nexio.tv.core.anime.projection.AnimeSeasonPresentationCache
import com.nexio.tv.core.anime.projection.AnimeSeasonProjectionResolver
import com.nexio.tv.core.anime.projection.DefaultAnimeSeasonDetailRepository
import com.nexio.tv.core.anime.projection.DefaultAnimeSeasonProjectionResolver
import com.nexio.tv.core.anime.projection.InMemoryAnimeEpisodeCoordinateStore
import com.nexio.tv.core.anime.projection.InMemoryAnimeSeasonPresentationCache
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

    @Binds
    abstract fun bindPresentationCache(impl: InMemoryAnimeSeasonPresentationCache): AnimeSeasonPresentationCache

    @Binds
    abstract fun bindDetailRepository(impl: DefaultAnimeSeasonDetailRepository): AnimeSeasonDetailRepository
}
