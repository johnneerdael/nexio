package com.nexio.tv.core.di

import com.nexio.tv.core.tvdb.TvdbLoginGateway
import com.nexio.tv.data.integration.tmdb.DefaultTmdbExternalIdLookupProvider
import com.nexio.tv.data.integration.tmdb.TmdbExternalIdLookupProvider
import com.nexio.tv.data.integration.tvdb.TvdbLoginIntegrationProvider
import com.nexio.tv.data.repository.DefaultProviderSettingsRepository
import com.nexio.tv.data.repository.DefaultReviewsRepository
import com.nexio.tv.data.repository.ProviderSettingsRepository
import com.nexio.tv.data.repository.ReviewsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class IntegrationProviderModule {
    @Binds
    @Singleton
    abstract fun bindProviderSettingsRepository(
        impl: DefaultProviderSettingsRepository
    ): ProviderSettingsRepository

    @Binds
    @Singleton
    abstract fun bindReviewsRepository(
        impl: DefaultReviewsRepository
    ): ReviewsRepository

    @Binds
    @Singleton
    abstract fun bindTvdbLoginGateway(
        impl: TvdbLoginIntegrationProvider
    ): TvdbLoginGateway

    @Binds
    @Singleton
    abstract fun bindTmdbExternalIdLookupProvider(
        impl: DefaultTmdbExternalIdLookupProvider
    ): TmdbExternalIdLookupProvider
}
