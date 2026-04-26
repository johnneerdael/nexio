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
        identityResolver = MetadataIdentityResolver(object : MetadataIdentityResolver.Lookup {
            override suspend fun tmdbToTvdb(tmdbId: String): String? = null
            override suspend fun tvdbToTmdb(tvdbId: String): String? = null
        }),
        providerPlanRunner = ProviderPlanRunner(
            MetadataPrimaryProvider.entries
                .map { provider -> TestMetadataProviderAdapter(provider) }
                .toSet()
        ),
        fieldResolver = FieldResolver()
    )

private class TestMetadataProviderAdapter(
    override val provider: MetadataPrimaryProvider
) : MetadataProviderAdapter {
    override fun supports(step: ProviderPlanStep): Boolean = true

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
        ProviderStepResult(
            step = step,
            candidate = MetadataCandidate(
                provider = route.provider,
                fields = mapOf(
                    ResolvedField.TITLE to FieldValue("Test title", FieldOwner.PRIMARY)
                )
            )
        )
}
