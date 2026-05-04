package com.nexio.tv.core.di

import android.content.Context
import androidx.room.Room
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.InMemoryArtworkDecisionCache
import com.nexio.tv.core.integration.DefaultIntegrationRuntime
import com.nexio.tv.core.integration.DefaultIntegrationHydrationCoordinator
import com.nexio.tv.core.integration.IntegrationCacheStore
import com.nexio.tv.core.integration.IntegrationAuditSink
import com.nexio.tv.core.integration.IntegrationHydrationCoordinator
import com.nexio.tv.core.integration.IntegrationPolicyRegistry
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.NoOpIntegrationAuditSink
import com.nexio.tv.core.integration.defaultIntegrationPolicyRegistry
import com.nexio.tv.ui.screens.home.DefaultHomeRailHydrationExecutor
import com.nexio.tv.ui.screens.home.HomeRailHydrationExecutor
import com.nexio.tv.data.local.integration.IntegrationBlobStore
import com.nexio.tv.data.local.integration.IntegrationCacheDao
import com.nexio.tv.data.local.integration.IntegrationCacheDatabase
import com.nexio.tv.data.local.integration.IntegrationProviderBackoffDao
import com.nexio.tv.data.local.integration.LocalIntegrationCacheStore
import com.nexio.tv.data.local.integration.MediaIdentityDao
import com.nexio.tv.data.local.integration.RailStoreDao
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
    fun provideRailStoreDao(
        database: IntegrationCacheDatabase
    ): RailStoreDao = database.railStoreDao()

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
    fun provideIntegrationAuditSink(): IntegrationAuditSink = NoOpIntegrationAuditSink

    @Provides
    @Singleton
    fun provideIntegrationRuntime(
        impl: DefaultIntegrationRuntime
    ): IntegrationRuntime = impl

    @Provides
    @Singleton
    fun provideArtworkDecisionCache(): ArtworkDecisionCache = InMemoryArtworkDecisionCache()

    @Provides
    @Singleton
    fun provideIntegrationHydrationCoordinator(
        impl: DefaultIntegrationHydrationCoordinator
    ): IntegrationHydrationCoordinator = impl

    @Provides
    @Singleton
    fun provideHomeRailHydrationExecutor(
        impl: DefaultHomeRailHydrationExecutor
    ): HomeRailHydrationExecutor = impl
}
