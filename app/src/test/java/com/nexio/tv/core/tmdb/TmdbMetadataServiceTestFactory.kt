package com.nexio.tv.core.tmdb

import android.content.Context
import com.nexio.tv.core.integration.IntegrationCacheOwnershipFactory
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.remote.api.TmdbApi

fun TmdbMetadataService(
    appContext: Context,
    tmdbApi: TmdbApi,
    posterRatingsUrlResolver: PosterRatingsUrlResolver,
    tmdbCredentialProvider: suspend () -> MetadataProviderCredential,
    metadataDiskCacheStore: MetadataDiskCacheStore,
    integrationRuntime: IntegrationRuntime,
    ownershipFactory: IntegrationCacheOwnershipFactory,
    tmdbIntegrationProvider: TmdbIntegrationProvider = TmdbIntegrationProvider(
        runtime = integrationRuntime,
        tmdbApi = tmdbApi,
        tmdbCredentialProvider = tmdbCredentialProvider
    )
): TmdbMetadataService = TmdbMetadataService(
    appContext = appContext,
    posterRatingsUrlResolver = posterRatingsUrlResolver,
    metadataDiskCacheStore = metadataDiskCacheStore,
    tmdbIntegrationProvider = tmdbIntegrationProvider
)
