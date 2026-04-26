package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.tvdb.ProviderMetadataRouter

fun testMetadataRouterFacade(providerMetadataRouter: ProviderMetadataRouter): MetadataRouterFacade =
    MetadataRouterFacade(
        router = MetadataRouter(
            normalizer = MetadataRequestNormalizer(),
            animeIdentityIndex = InMemoryAnimeIdentityIndex(),
            idMappingStore = InMemoryIdMappingStore()
        ),
        providerPlanExecutor = ProviderPlanExecutor(),
        resolverOrchestrator = ResolverOrchestrator(),
        providerMetadataRouter = providerMetadataRouter
    )
