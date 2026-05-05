package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
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
                activePremiumProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                artworkProviderSettings = topPostersPosterSettings()
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
            policy = ArtworkRoutingPolicy(
                activePremiumProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                artworkProviderSettings = rpdbPosterSettings()
            )
        )

        assertEquals("KITSU", decision.selectedCandidate.provider?.key)
        assertEquals("missing_supported_provider_id", decision.rejectedCandidates.single().reason)
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
            policy = ArtworkRoutingPolicy(
                activePremiumProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                artworkProviderSettings = rpdbPosterSettings()
            )
        )

        assertEquals(ArtworkSourceRole.PLACEHOLDER, decision.selectedCandidate.sourceRole)
        assertEquals("PLACEHOLDER", decision.selectedCandidate.provider?.key)
        assertEquals("missing_supported_provider_id", decision.rejectedCandidates.single().reason)
    }

    @Test
    fun `active premium provider not selected in settings rejects and primary wins`() {
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                    role = ArtworkSourceRole.PREMIUM,
                    priority = 10
                ),
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                    role = ArtworkSourceRole.PRIMARY,
                    priority = 20
                )
            ),
            policy = ArtworkRoutingPolicy(
                activePremiumProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                artworkProviderSettings = ArtworkProviderSettings(
                    topPostersApiKey = "top-key",
                    selection = ArtworkProviderSelectionSettings(
                        posterProvider = ArtworkProviderChoiceKey.DEFAULT
                    )
                )
            )
        )

        assertEquals("TMDB", decision.selectedCandidate.provider?.key)
        assertEquals(
            "provider_not_selected_for_artwork_type",
            decision.rejectedCandidates.single().reason
        )
    }

    @Test
    fun `top posters thumbnail selected without entitlement rejects and primary wins`() {
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                    role = ArtworkSourceRole.PREMIUM,
                    priority = 10,
                    imageType = ArtworkType.THUMBNAIL
                ),
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                    role = ArtworkSourceRole.PRIMARY,
                    priority = 20,
                    imageType = ArtworkType.THUMBNAIL
                )
            ),
            policy = ArtworkRoutingPolicy(
                activePremiumProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                artworkProviderSettings = ArtworkProviderSettings(
                    topPostersApiKey = "top-key",
                    selection = ArtworkProviderSelectionSettings(
                        thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS
                    ),
                    topPostersEntitlement = null
                )
            )
        )

        assertEquals("TMDB", decision.selectedCandidate.provider?.key)
        assertEquals("topposters_entitlement_missing", decision.rejectedCandidates.single().reason)
    }

    private fun candidate(
        provider: ArtworkProviderId,
        role: ArtworkSourceRole,
        priority: Int,
        providerIds: ProviderIds = ProviderIds(imdb = "tt0137523"),
        imageType: ArtworkType = ArtworkType.POSTER
    ): ArtworkCandidate =
        ArtworkCandidate(
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            canonicalContentId = "imdb:tt0137523",
            providerIds = providerIds,
            imageType = imageType,
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

    private fun rpdbPosterSettings(): ArtworkProviderSettings =
        ArtworkProviderSettings(
            rpdbApiKey = "rpdb-key",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        )

    private fun topPostersPosterSettings(): ArtworkProviderSettings =
        ArtworkProviderSettings(
            topPostersApiKey = "top-key",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.TOP_POSTERS
            )
        )
}
