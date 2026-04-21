package com.nexio.tv.core.integration

class IntegrationPolicyRegistry(
    private val policies: Map<IntegrationProvider, IntegrationProviderPolicy>
) {
    fun policyFor(provider: IntegrationProvider): IntegrationProviderPolicy = policies.getValue(provider)
}

fun defaultIntegrationPolicyRegistry(): IntegrationPolicyRegistry =
    IntegrationPolicyRegistry(
        policies = mapOf(
            IntegrationProvider.TRAKT to IntegrationProviderPolicy(allowDuringPlayback = true),
            IntegrationProvider.SIMKL to IntegrationProviderPolicy(allowDuringPlayback = true),
            IntegrationProvider.TMDB to IntegrationProviderPolicy(),
            IntegrationProvider.TVDB to IntegrationProviderPolicy(),
            IntegrationProvider.KITSU to IntegrationProviderPolicy(),
            IntegrationProvider.MDBLIST to IntegrationProviderPolicy(),
            IntegrationProvider.OMDB to IntegrationProviderPolicy(),
            IntegrationProvider.THEINTRODB to IntegrationProviderPolicy(),
            IntegrationProvider.ANISKIP to IntegrationProviderPolicy(),
            IntegrationProvider.ANIMESKIP to IntegrationProviderPolicy(),
            IntegrationProvider.ARM to IntegrationProviderPolicy(),
            IntegrationProvider.RPDB to IntegrationProviderPolicy(),
            IntegrationProvider.TOP_POSTERS to IntegrationProviderPolicy(),
            IntegrationProvider.REAL_DEBRID to IntegrationProviderPolicy(),
            IntegrationProvider.PREMIUMIZE to IntegrationProviderPolicy(),
            IntegrationProvider.TORBOX to IntegrationProviderPolicy(),
            IntegrationProvider.EASY_DEBRID to IntegrationProviderPolicy(),
            IntegrationProvider.GITHUB to IntegrationProviderPolicy()
        )
    )
