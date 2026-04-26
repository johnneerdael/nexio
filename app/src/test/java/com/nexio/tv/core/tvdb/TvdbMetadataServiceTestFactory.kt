package com.nexio.tv.core.tvdb

import com.nexio.tv.core.integration.IntegrationCacheOwnershipFactory
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TvdbMergeAliasStore
import com.nexio.tv.data.remote.api.TvdbApi

fun TvdbMetadataService(
    tvdbApi: TvdbApi,
    authService: TvdbAuthService,
    posterRatingsUrlResolver: PosterRatingsUrlResolver,
    metadataDiskCacheStore: MetadataDiskCacheStore,
    seasonOrderMapper: TvdbSeasonOrderMapper,
    advancedMetadataMapper: TvdbAdvancedMetadataMapper,
    mergeAliasStore: TvdbMergeAliasStore,
    credentialHealth: TvdbCredentialHealth,
    diagnosticsRecorder: TvdbDiagnosticsRecorder,
    integrationRuntime: IntegrationRuntime,
    ownershipFactory: IntegrationCacheOwnershipFactory = IntegrationCacheOwnershipFactory(
        RailMediaIdentityResolver()
    )
): TvdbMetadataService = TvdbMetadataService(
    provider = TvdbIntegrationProvider(
        runtime = integrationRuntime,
        tvdbApi = tvdbApi,
        tvdbAuthService = authService
    ),
    posterRatingsUrlResolver = posterRatingsUrlResolver,
    metadataDiskCacheStore = metadataDiskCacheStore,
    seasonOrderMapper = seasonOrderMapper,
    advancedMetadataMapper = advancedMetadataMapper,
    mergeAliasStore = mergeAliasStore,
    credentialHealth = credentialHealth,
    diagnosticsRecorder = diagnosticsRecorder
)
