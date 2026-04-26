package com.nexio.tv.core.di

import com.nexio.tv.core.metadata.router.AnimeIdentityIndex
import com.nexio.tv.core.metadata.router.AssetAnimeIdentityIndex
import com.nexio.tv.core.metadata.router.IdMappingStore
import com.nexio.tv.core.metadata.router.LocalIdMappingStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MetadataRouterModule {
    @Binds
    @Singleton
    abstract fun bindIdMappingStore(
        impl: LocalIdMappingStore
    ): IdMappingStore

    @Binds
    @Singleton
    abstract fun bindAnimeIdentityIndex(
        impl: AssetAnimeIdentityIndex
    ): AnimeIdentityIndex
}
