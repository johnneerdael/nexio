package com.nexio.tv.core.di

import android.content.Context
import androidx.room.Room
import com.nexio.tv.core.integration.DefaultIntegrationRuntime
import com.nexio.tv.core.integration.IntegrationCacheStore
import com.nexio.tv.core.integration.IntegrationPolicyRegistry
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.defaultIntegrationPolicyRegistry
import com.nexio.tv.data.local.integration.IntegrationBlobStore
import com.nexio.tv.data.local.integration.IntegrationCacheDao
import com.nexio.tv.data.local.integration.IntegrationCacheDatabase
import com.nexio.tv.data.local.integration.IntegrationProviderBackoffDao
import com.nexio.tv.data.local.integration.LocalIntegrationCacheStore
import com.nexio.tv.data.local.integration.MediaIdentityDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object IntegrationRuntimeModule {
    @Provides
    @Singleton
    fun provideIntegrationPolicyRegistry(): IntegrationPolicyRegistry =
        defaultIntegrationPolicyRegistry()

    @Provides
    @Singleton
    fun provideIntegrationCacheDatabase(
        @ApplicationContext context: Context
    ): IntegrationCacheDatabase =
        Room.databaseBuilder(
            context,
            IntegrationCacheDatabase::class.java,
            "integration-cache.db"
        ).fallbackToDestructiveMigration().build()

    @Provides
    fun provideIntegrationCacheDao(
        database: IntegrationCacheDatabase
    ): IntegrationCacheDao = database.cacheDao()

    @Provides
    fun provideIntegrationProviderBackoffDao(
        database: IntegrationCacheDatabase
    ): IntegrationProviderBackoffDao = database.backoffDao()

    @Provides
    fun provideMediaIdentityDao(
        database: IntegrationCacheDatabase
    ): MediaIdentityDao = database.mediaIdentityDao()

    @Provides
    @Singleton
    fun provideIntegrationCacheStore(
        impl: LocalIntegrationCacheStore
    ): IntegrationCacheStore = impl

    @Provides
    @Singleton
    fun provideIntegrationRuntime(
        impl: DefaultIntegrationRuntime
    ): IntegrationRuntime = impl
}
