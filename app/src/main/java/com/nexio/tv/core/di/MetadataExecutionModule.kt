package com.nexio.tv.core.di

import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataIdentityResolver
import com.nexio.tv.data.integration.metadata.KitsuMetadataProviderAdapter
import com.nexio.tv.data.integration.metadata.RuntimeMetadataIdentityLookup
import com.nexio.tv.data.integration.metadata.TmdbMetadataProviderAdapter
import com.nexio.tv.data.integration.metadata.TmdbRecommendationMetadataAdapter
import com.nexio.tv.data.integration.metadata.TmdbReviewMetadataAdapter
import com.nexio.tv.data.integration.metadata.TmdbTrailerMetadataAdapter
import com.nexio.tv.data.integration.metadata.TvdbMetadataProviderAdapter
import com.nexio.tv.data.integration.metadata.TvdbTrailerMetadataAdapter
import dagger.Binds
import dagger.Module
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
    abstract fun bindTmdbRecommendationAdapter(impl: TmdbRecommendationMetadataAdapter): MetadataProviderAdapter

    @Binds
    abstract fun bindIdentityLookup(impl: RuntimeMetadataIdentityLookup): MetadataIdentityResolver.Lookup
}
