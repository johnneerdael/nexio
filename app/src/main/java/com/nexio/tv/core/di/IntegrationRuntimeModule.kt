package com.nexio.tv.core.di

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.nexio.tv.core.artwork.ArtworkAssetDiskCache
import com.nexio.tv.core.artwork.ArtworkAssetRecordStore
import com.nexio.tv.core.artwork.ArtworkByteLoader
import com.nexio.tv.core.artwork.ArtworkCredentialResolver
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkPosterTransport
import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import com.nexio.tv.core.artwork.ArtworkReferenceIntegrityValidator
import com.nexio.tv.core.artwork.ArtworkRemoteSourceStore
import com.nexio.tv.core.artwork.ArtworkRouter
import com.nexio.tv.core.artwork.ArtworkSourceMaterializer
import com.nexio.tv.core.artwork.DefaultArtworkByteLoader
import com.nexio.tv.core.artwork.DefaultArtworkReferenceIntegrityValidator
import com.nexio.tv.core.artwork.DefaultArtworkPosterTransport
import com.nexio.tv.core.artwork.DurableArtworkAssetRecordStore
import com.nexio.tv.core.artwork.DurableArtworkDecisionCache
import com.nexio.tv.core.artwork.FileBackedArtworkRemoteSourceStore
import com.nexio.tv.core.artwork.PosterRatingsArtworkCredentialResolver
import com.nexio.tv.core.artwork.PosterRatingsArtworkProviderSettingsSource
import com.nexio.tv.core.integration.DefaultIntegrationRuntime
import com.nexio.tv.core.integration.DefaultIntegrationHydrationCoordinator
import com.nexio.tv.core.integration.IntegrationCacheStore
import com.nexio.tv.core.integration.IntegrationAuditSink
import com.nexio.tv.core.integration.IntegrationHydrationCoordinator
import com.nexio.tv.core.integration.IntegrationPolicyRegistry
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.NoOpIntegrationAuditSink
import com.nexio.tv.core.integration.defaultIntegrationPolicyRegistry
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.ui.screens.home.DefaultHomeRailHydrationExecutor
import com.nexio.tv.ui.screens.home.HomeRailHydrationExecutor
import com.nexio.tv.data.local.integration.IntegrationBlobStore
import com.nexio.tv.data.local.integration.IntegrationCacheDao
import com.nexio.tv.data.local.integration.IntegrationCacheDatabase
import com.nexio.tv.data.local.integration.IntegrationProviderBackoffDao
import com.nexio.tv.data.local.integration.LocalIntegrationCacheStore
import com.nexio.tv.data.local.integration.MediaIdentityDao
import com.nexio.tv.data.local.integration.RailStoreDao
import com.nexio.tv.data.local.artwork.ArtworkAssetRecordDao
import com.nexio.tv.data.local.artwork.ArtworkCacheDatabase
import com.nexio.tv.data.local.artwork.ArtworkCacheMigrationDao
import com.nexio.tv.data.local.artwork.ArtworkDecisionDao
import com.nexio.tv.data.local.artwork.RoomArtworkAssetRecordStore
import com.nexio.tv.data.local.artwork.RoomArtworkDecisionCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
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
    @Singleton
    fun provideArtworkCacheDatabase(
        @ApplicationContext context: Context
    ): ArtworkCacheDatabase =
        Room.databaseBuilder(
            context,
            ArtworkCacheDatabase::class.java,
            "artwork-cache.db"
        ).fallbackToDestructiveMigration().build()

    @Provides
    fun provideArtworkDecisionDao(
        database: ArtworkCacheDatabase
    ): ArtworkDecisionDao = database.decisionDao()

    @Provides
    fun provideArtworkAssetRecordDao(
        database: ArtworkCacheDatabase
    ): ArtworkAssetRecordDao = database.assetRecordDao()

    @Provides
    fun provideArtworkCacheMigrationDao(
        database: ArtworkCacheDatabase
    ): ArtworkCacheMigrationDao = database.migrationDao()

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
    fun provideArtworkDecisionCache(
        @ApplicationContext context: Context,
        gson: Gson,
        traceSink: RuntimeTraceSink,
        artworkDecisionDao: ArtworkDecisionDao,
        artworkCacheMigrationDao: ArtworkCacheMigrationDao
    ): ArtworkDecisionCache =
        RoomArtworkDecisionCache(
            dao = artworkDecisionDao,
            migrationDao = artworkCacheMigrationDao,
            legacyFile = File(context.filesDir, "artwork-decisions-v1.json"),
            gson = gson,
            traceSink = traceSink,
            fallback = DurableArtworkDecisionCache(
                file = File(context.filesDir, "artwork-decisions-v1.json"),
                gson = gson,
                traceSink = traceSink,
                thumbnailWriteDebounceMs = 1_500L
            )
        )

    @Provides
    @Singleton
    fun provideArtworkAssetDiskCache(
        @ApplicationContext context: Context
    ): ArtworkAssetDiskCache = ArtworkAssetDiskCache(context.cacheDir)

    @Provides
    @Singleton
    fun provideArtworkAssetRecordStore(
        @ApplicationContext context: Context,
        gson: Gson,
        artworkAssetRecordDao: ArtworkAssetRecordDao,
        artworkCacheMigrationDao: ArtworkCacheMigrationDao
    ): ArtworkAssetRecordStore =
        RoomArtworkAssetRecordStore(
            dao = artworkAssetRecordDao,
            migrationDao = artworkCacheMigrationDao,
            legacyFile = File(context.filesDir, "artwork-asset-records-v1.json"),
            gson = gson,
            fallback = DurableArtworkAssetRecordStore(
                file = File(context.filesDir, "artwork-asset-records-v1.json"),
                gson = gson
            )
        )

    @Provides
    @Singleton
    fun provideArtworkReferenceIntegrityValidator(
        impl: DefaultArtworkReferenceIntegrityValidator
    ): ArtworkReferenceIntegrityValidator = impl

    @Provides
    @Singleton
    fun provideArtworkRemoteSourceStore(
        @ApplicationContext context: Context,
        gson: Gson
    ): ArtworkRemoteSourceStore =
        FileBackedArtworkRemoteSourceStore(
            file = File(context.filesDir, "artwork-remote-sources-v1.json"),
            gson = gson,
            writeDebounceMs = 1_500L
        )

    @Provides
    @Singleton
    fun provideArtworkRouter(
        remoteSourceStore: ArtworkRemoteSourceStore
    ): ArtworkRouter =
        ArtworkRouter(remoteSourceStore = remoteSourceStore)

    @Provides
    @Singleton
    fun provideArtworkSourceMaterializer(
        remoteSourceStore: ArtworkRemoteSourceStore
    ): ArtworkSourceMaterializer =
        ArtworkSourceMaterializer(
            remoteSourcesByHash = emptyMap(),
            remoteSourceStore = remoteSourceStore
        )

    @Provides
    @Singleton
    fun provideArtworkPosterTransport(
        impl: DefaultArtworkPosterTransport
    ): ArtworkPosterTransport = impl

    @Provides
    @Singleton
    fun provideArtworkProviderSettingsSource(
        impl: PosterRatingsArtworkProviderSettingsSource
    ): ArtworkProviderSettingsSource = impl

    @Provides
    @Singleton
    fun provideArtworkCredentialResolver(
        impl: PosterRatingsArtworkCredentialResolver
    ): ArtworkCredentialResolver = impl

    @Provides
    @Singleton
    fun provideArtworkByteLoader(
        impl: DefaultArtworkByteLoader
    ): ArtworkByteLoader = impl

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
