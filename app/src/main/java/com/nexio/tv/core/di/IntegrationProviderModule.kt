package com.nexio.tv.core.di

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
}
