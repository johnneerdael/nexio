package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkRouterTest {
    private val router = ArtworkRouter(capabilityResolver = ArtworkProviderCapabilityResolver())

    @Test
    fun `premium poster wins over primary poster when supported`() {
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                    role = ArtworkSourceRole.PRIMARY,
                    priority = 20
                ),
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                    role = ArtworkSourceRole.PREMIUM,
                    priority = 10
                )
            ),
            policy = ArtworkRoutingPolicy(
                activePremiumProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS)
            )
        )

        assertEquals("TOP_POSTERS", decision.selectedCandidate.provider?.key)
        assertEquals("premium artwork provider has precedence", decision.rejectedCandidates.single().reason)
    }

    @Test
    fun `current addon preview beats other rail preview when primary missing`() {
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RailPreview,
                    role = ArtworkSourceRole.OTHER_PREVIEW,
                    priority = 30
                ),
                candidate(
                    provider = ArtworkProviderId.AddonPreview,
                    role = ArtworkSourceRole.CURRENT_PREVIEW,
                    priority = 25
                )
            ),
            policy = ArtworkRoutingPolicy(activePremiumProvider = null)
        )

        assertEquals("ADDON_PREVIEW", decision.selectedCandidate.provider?.key)
    }

    @Test
    fun `unsupported premium candidate does not beat primary`() {
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                    role = ArtworkSourceRole.PREMIUM,
                    priority = 10,
                    providerIds = ProviderIds(kitsu = "7442")
                ),
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.KITSU),
                    role = ArtworkSourceRole.PRIMARY,
                    priority = 20,
                    providerIds = ProviderIds(kitsu = "7442")
                )
            ),
            policy = ArtworkRoutingPolicy(activePremiumProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB))
        )

        assertEquals("KITSU", decision.selectedCandidate.provider?.key)
        assertEquals("UNSUPPORTED_ID_TYPE", decision.rejectedCandidates.single().reason)
    }

    @Test
    fun `inactive premium candidate does not beat primary`() {
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                    role = ArtworkSourceRole.PREMIUM,
                    priority = 10
                ),
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                    role = ArtworkSourceRole.PRIMARY,
                    priority = 20
                )
            ),
            policy = ArtworkRoutingPolicy(activePremiumProvider = null)
        )

        assertEquals("TMDB", decision.selectedCandidate.provider?.key)
        assertEquals("inactive premium artwork provider", decision.rejectedCandidates.single().reason)
    }

    @Test
    fun `unsupported premium falls back to placeholder when no primary exists`() {
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                    role = ArtworkSourceRole.PREMIUM,
                    priority = 10,
                    providerIds = ProviderIds(kitsu = "7442")
                ),
                candidate(
                    provider = ArtworkProviderId.Placeholder,
                    role = ArtworkSourceRole.PLACEHOLDER,
                    priority = 100,
                    providerIds = ProviderIds(kitsu = "7442")
                )
            ),
            policy = ArtworkRoutingPolicy(activePremiumProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB))
        )

        assertEquals(ArtworkSourceRole.PLACEHOLDER, decision.selectedCandidate.sourceRole)
        assertEquals("PLACEHOLDER", decision.selectedCandidate.provider?.key)
        assertEquals("UNSUPPORTED_ID_TYPE", decision.rejectedCandidates.single().reason)
    }

    private fun candidate(
        provider: ArtworkProviderId,
        role: ArtworkSourceRole,
        priority: Int,
        providerIds: ProviderIds = ProviderIds(imdb = "tt0137523")
    ): ArtworkCandidate =
        ArtworkCandidate(
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            canonicalContentId = "imdb:tt0137523",
            providerIds = providerIds,
            imageType = ArtworkType.POSTER,
            provider = provider,
            sourceRole = role,
            source = if (role == ArtworkSourceRole.PLACEHOLDER) {
                ArtworkSource.Placeholder(PlaceholderType.POSTER)
            } else {
                ArtworkSource.ProviderTemplate(
                    provider = provider,
                    idType = "imdb",
                    mediaId = "tt0137523",
                    providerPathHash = null,
                    settingsHash = null,
                    credentialHash = null
                )
            },
            priority = priority,
            requiresRuntimeFetch = true
        )
}
