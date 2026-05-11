package com.nexio.tv.core.di

import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataIdentityResolver
import com.nexio.tv.core.metadata.router.StableIdBundleResolver
import com.nexio.tv.data.integration.metadata.KitsuMetadataProviderAdapter
import com.nexio.tv.data.integration.metadata.RuntimeMetadataIdentityLookup
import com.nexio.tv.data.integration.metadata.TmdbMetadataProviderAdapter
import com.nexio.tv.data.integration.metadata.TmdbOrganizationPersonAdapter
import com.nexio.tv.data.integration.metadata.TmdbRecommendationMetadataAdapter
import com.nexio.tv.data.integration.metadata.TmdbReviewMetadataAdapter
import com.nexio.tv.data.integration.metadata.TmdbTrailerMetadataAdapter
import com.nexio.tv.data.integration.metadata.TraktReviewMetadataAdapter
import com.nexio.tv.data.integration.metadata.TvdbMetadataProviderAdapter
import com.nexio.tv.data.integration.metadata.TvdbOrganizationPersonAdapter
import com.nexio.tv.data.integration.metadata.TvdbTrailerMetadataAdapter
import com.nexio.tv.data.integration.posters.RpdbMetadataProviderAdapter
import com.nexio.tv.data.integration.posters.TopPostersMetadataProviderAdapter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class MetadataExecutionModule {
    @Binds
    @IntoSet
    abstract fun bindTmdbAdapter(impl: TmdbMetadataProviderAdapter): MetadataProviderAdapter

    @Binds
    @IntoSet
    abstract fun bindTvdbAdapter(impl: TvdbMetadataProviderAdapter): MetadataProviderAdapter

    @Binds
    @IntoSet
    abstract fun bindKitsuAdapter(impl: KitsuMetadataProviderAdapter): MetadataProviderAdapter

    @Binds
    @IntoSet
    abstract fun bindTmdbTrailerAdapter(impl: TmdbTrailerMetadataAdapter): MetadataProviderAdapter

    @Binds
    @IntoSet
    abstract fun bindTvdbTrailerAdapter(impl: TvdbTrailerMetadataAdapter): MetadataProviderAdapter

    @Binds
    @IntoSet
    abstract fun bindTmdbReviewAdapter(impl: TmdbReviewMetadataAdapter): MetadataProviderAdapter

    @Binds
    @IntoSet
    abstract fun bindTraktReviewAdapter(impl: TraktReviewMetadataAdapter): MetadataProviderAdapter

    @Binds
    @IntoSet
    abstract fun bindTmdbRecommendationAdapter(impl: TmdbRecommendationMetadataAdapter): MetadataProviderAdapter

    @Binds
    @IntoSet
    abstract fun bindTmdbOrganizationPersonAdapter(impl: TmdbOrganizationPersonAdapter): MetadataProviderAdapter

    @Binds
    @IntoSet
    abstract fun bindTvdbOrganizationPersonAdapter(impl: TvdbOrganizationPersonAdapter): MetadataProviderAdapter

    @Binds
    @IntoSet
    abstract fun bindRpdbPosterAdapter(impl: RpdbMetadataProviderAdapter): MetadataProviderAdapter

    @Binds
    @IntoSet
    abstract fun bindTopPostersPosterAdapter(impl: TopPostersMetadataProviderAdapter): MetadataProviderAdapter

    @Binds
    abstract fun bindIdentityLookup(impl: RuntimeMetadataIdentityLookup): MetadataIdentityResolver.Lookup

    @Binds
    abstract fun bindStableIdBundleLookup(impl: RuntimeMetadataIdentityLookup): StableIdBundleResolver.Lookup

    companion object {
        @Provides
        fun provideNowEpochMs(): () -> Long = { System.currentTimeMillis() }
    }
}
