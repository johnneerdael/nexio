package com.nexio.tv.core.di

import com.nexio.tv.data.integration.kitsu.KitsuDiscoveryIntegrationProvider
import com.nexio.tv.data.integration.imdb.ImdbTitleSearchIntegrationRepository
import com.nexio.tv.data.repository.AddonRepositoryImpl
import com.nexio.tv.data.repository.CatalogRepositoryImpl
import com.nexio.tv.data.repository.LibraryRepositoryImpl
import com.nexio.tv.data.repository.MetaRepositoryImpl
import com.nexio.tv.data.repository.OpenSubtitlesSourceImpl
import com.nexio.tv.data.repository.StreamRepositoryImpl
import com.nexio.tv.data.repository.SubtitleRepositoryImpl
import com.nexio.tv.data.repository.SyncRepositoryImpl
import com.nexio.tv.data.repository.DefaultTrackingProgressService
import com.nexio.tv.data.repository.DefaultTrackingScrobbleService
import com.nexio.tv.data.repository.DefaultTrackingAccountScopeProvider
import com.nexio.tv.data.repository.ImdbTitleSearchRepository
import com.nexio.tv.data.repository.KitsuDiscoveryClient
import com.nexio.tv.data.repository.TmdbDiscoveryClient
import com.nexio.tv.data.repository.TrackingAccountScopeProvider
import com.nexio.tv.data.repository.TrackingProgressService
import com.nexio.tv.data.repository.TrackingScrobbleService
import com.nexio.tv.data.repository.TraktAuthService
import com.nexio.tv.data.repository.WatchProgressRepositoryImpl
import com.nexio.tv.data.repository.servicewrap.DebridAvailabilityResolver
import com.nexio.tv.data.repository.servicewrap.ServiceWrapResolver
import com.nexio.tv.data.repository.trakt.SeasonMarkBatcher
import com.nexio.tv.data.trakt.outbox.ProviderMutationOutboxCoordinator
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.domain.repository.LibraryRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.OpenSubtitlesSource
import com.nexio.tv.domain.repository.StreamRepository
import com.nexio.tv.domain.repository.SubtitleRepository
import com.nexio.tv.domain.repository.SyncRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAddonRepository(impl: AddonRepositoryImpl): AddonRepository

    @Binds
    @Singleton
    abstract fun bindCatalogRepository(impl: CatalogRepositoryImpl): CatalogRepository

    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    @Singleton
    abstract fun bindMetaRepository(impl: MetaRepositoryImpl): MetaRepository

    @Binds
    @Singleton
    abstract fun bindStreamRepository(impl: StreamRepositoryImpl): StreamRepository

    @Binds
    @Singleton
    abstract fun bindSubtitleRepository(impl: SubtitleRepositoryImpl): SubtitleRepository

    @Binds
    @Singleton
    abstract fun bindOpenSubtitlesSource(impl: OpenSubtitlesSourceImpl): OpenSubtitlesSource

    @Binds
    @Singleton
    abstract fun bindSyncRepository(impl: SyncRepositoryImpl): SyncRepository

    @Binds
    @Singleton
    abstract fun bindWatchProgressRepository(impl: WatchProgressRepositoryImpl): WatchProgressRepository

    @Binds
    @Singleton
    abstract fun bindTrackingProgressService(impl: DefaultTrackingProgressService): TrackingProgressService

    @Binds
    @Singleton
    abstract fun bindTrackingScrobbleService(impl: DefaultTrackingScrobbleService): TrackingScrobbleService

    @Binds
    @Singleton
    abstract fun bindTrackingAccountScopeProvider(
        impl: DefaultTrackingAccountScopeProvider
    ): TrackingAccountScopeProvider

    @Binds
    @Singleton
    abstract fun bindServiceWrapResolver(impl: DebridAvailabilityResolver): ServiceWrapResolver

    @Binds
    @Singleton
    abstract fun bindTmdbDiscoveryClient(impl: TmdbIntegrationProvider): TmdbDiscoveryClient

    @Binds
    @Singleton
    abstract fun bindKitsuDiscoveryClient(impl: KitsuDiscoveryIntegrationProvider): KitsuDiscoveryClient

    @Binds
    @Singleton
    abstract fun bindImdbTitleSearchRepository(
        impl: ImdbTitleSearchIntegrationRepository
    ): ImdbTitleSearchRepository

    companion object {
        @Provides
        @Singleton
        fun provideSeasonMarkBatcher(
            providerMutationOutboxCoordinator: ProviderMutationOutboxCoordinator,
            traktAuthService: TraktAuthService
        ): SeasonMarkBatcher {
            return SeasonMarkBatcher(
                traktMutationOutboxCoordinator = providerMutationOutboxCoordinator,
                traktAuthService = traktAuthService,
                ioDispatcher = Dispatchers.IO
            )
        }
    }
}
