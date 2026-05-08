package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TopPostersEntitlementSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            policy = policy(topPostersPosterSettings())
        )

        assertEquals("TOP_POSTERS", decision.selectedCandidate.provider?.key)
        assertEquals("premium_artwork_provider_precedence", decision.rejectedCandidates.single().reason)
    }

    @Test
    fun `rejected primary candidate records persisted fallback source material`() {
        val primarySource = ArtworkSource.RemoteUrl.of(
            rawUrl = SensitiveArtworkUrl.of("https://image.tmdb.org/t/p/w500/fallback.jpg"),
            normalizedUrlHash = "fallbacksourcehash"
        )
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                    role = ArtworkSourceRole.PRIMARY,
                    priority = 20,
                    source = primarySource
                ),
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                    role = ArtworkSourceRole.PREMIUM,
                    priority = 10
                )
            ),
            policy = policy(topPostersPosterSettings())
        )

        val rejected = decision.rejectedCandidates.single()

        assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB), rejected.provider)
        assertEquals(ArtworkSourceRole.PRIMARY, rejected.sourceRole)
        assertEquals("fallbacksourcehash", rejected.sourceHash)
        assertEquals("https://image.tmdb.org/t/p/w500/<redacted>", rejected.redactedSourceForTrace)
        assertNull(rejected.providerTemplate)
        assertEquals(20, rejected.priority)
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
            policy = policy()
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
            policy = policy(rpdbPosterSettings())
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
            policy = policy()
        )

        assertEquals("TMDB", decision.selectedCandidate.provider?.key)
        assertEquals("inactive_premium_artwork_provider_for_poster", decision.rejectedCandidates.single().reason)
    }

    @Test
    fun `selected rpdb poster without api key rejects with configuration reason and primary wins`() {
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
            policy = policy(
                ArtworkProviderSettings(
                    selection = ArtworkProviderSelectionSettings(
                        posterProvider = ArtworkProviderChoiceKey.RPDB
                    )
                )
            )
        )

        assertEquals("TMDB", decision.selectedCandidate.provider?.key)
        assertEquals("rpdb_not_configured", decision.rejectedCandidates.single().reason)
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
            policy = policy(rpdbPosterSettings())
        )

        assertEquals(ArtworkSourceRole.PLACEHOLDER, decision.selectedCandidate.sourceRole)
        assertEquals("PLACEHOLDER", decision.selectedCandidate.provider?.key)
        assertEquals("missing_supported_provider_id", decision.rejectedCandidates.single().reason)
    }

    @Test
    fun `selected poster provider wins only for poster when another provider is selected for thumbnail`() {
        val posterDecision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                    role = ArtworkSourceRole.PRIMARY,
                    priority = 20,
                    imageType = ArtworkType.POSTER
                ),
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                    role = ArtworkSourceRole.PREMIUM,
                    priority = 10,
                    imageType = ArtworkType.POSTER
                )
            ),
            policy = policy(
                ArtworkProviderSettings(
                    topPostersApiKey = "top-key",
                    rpdbApiKey = "rpdb-key",
                    selection = ArtworkProviderSelectionSettings(
                        posterProvider = ArtworkProviderChoiceKey.TOP_POSTERS,
                        thumbnailProvider = ArtworkProviderChoiceKey.RPDB
                    )
                )
            )
        )

        val thumbnailDecision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                    role = ArtworkSourceRole.PRIMARY,
                    priority = 20,
                    imageType = ArtworkType.THUMBNAIL
                ),
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                    role = ArtworkSourceRole.PREMIUM,
                    priority = 10,
                    imageType = ArtworkType.THUMBNAIL
                )
            ),
            policy = policy(
                ArtworkProviderSettings(
                    topPostersApiKey = "top-key",
                    rpdbApiKey = "rpdb-key",
                    selection = ArtworkProviderSelectionSettings(
                        posterProvider = ArtworkProviderChoiceKey.TOP_POSTERS,
                        thumbnailProvider = ArtworkProviderChoiceKey.RPDB
                    )
                )
            )
        )

        assertEquals("TOP_POSTERS", posterDecision.selectedCandidate.provider?.key)
        assertEquals("TMDB", thumbnailDecision.selectedCandidate.provider?.key)
        assertEquals(
            "inactive_premium_artwork_provider_for_thumbnail",
            thumbnailDecision.rejectedCandidates.single().reason
        )
    }

    @Test
    fun `unselected premium poster is rejected with type-specific inactive reason and primary wins`() {
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
            policy = policy(
                ArtworkProviderSettings(
                    topPostersApiKey = "top-key",
                    selection = ArtworkProviderSelectionSettings(
                        posterProvider = ArtworkProviderChoiceKey.DEFAULT
                    )
                )
            )
        )

        assertEquals("TMDB", decision.selectedCandidate.provider?.key)
        assertEquals(
            "inactive_premium_artwork_provider_for_poster",
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
            policy = policy(
                ArtworkProviderSettings(
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

    @Test
    fun `top posters thumbnail selected with inactive entitlement rejects and primary wins`() {
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
            policy = policy(
                ArtworkProviderSettings(
                    topPostersApiKey = "top-key",
                    selection = ArtworkProviderSelectionSettings(
                        thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS
                    ),
                    topPostersEntitlement = TopPostersEntitlementSnapshot(
                        valid = true,
                        isActive = false,
                        tier = 1,
                        tierName = "Premium",
                        episodeThumbnails = true,
                        verifiedAtMs = 1_000L,
                        expiresAtMs = System.currentTimeMillis() + 86_400_000L
                    )
                )
            )
        )

        assertEquals("TMDB", decision.selectedCandidate.provider?.key)
        assertEquals("topposters_entitlement_inactive", decision.rejectedCandidates.single().reason)
    }

    @Test
    fun `top posters thumbnail selected with non premium tier rejects and primary wins`() {
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
            policy = policy(topPostersThumbnailSettings(tier = 0))
        )

        assertEquals("TMDB", decision.selectedCandidate.provider?.key)
        assertEquals("topposters_tier_not_premium", decision.rejectedCandidates.single().reason)
    }

    @Test
    fun `top posters thumbnail selected without episode thumbnail entitlement rejects and primary wins`() {
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
            policy = policy(topPostersThumbnailSettings(episodeThumbnails = false))
        )

        assertEquals("TMDB", decision.selectedCandidate.provider?.key)
        assertEquals("topposters_episode_thumbnails_not_enabled", decision.rejectedCandidates.single().reason)
    }

    @Test
    fun `top posters thumbnail selected with unsupported id rejects and primary wins`() {
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                    role = ArtworkSourceRole.PREMIUM,
                    priority = 10,
                    providerIds = ProviderIds(tmdb = "not-numeric"),
                    imageType = ArtworkType.THUMBNAIL
                ),
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                    role = ArtworkSourceRole.PRIMARY,
                    priority = 20,
                    providerIds = ProviderIds(tmdb = "not-numeric"),
                    imageType = ArtworkType.THUMBNAIL
                )
            ),
            policy = policy(topPostersThumbnailSettings())
        )

        assertEquals("TMDB", decision.selectedCandidate.provider?.key)
        assertEquals("missing_supported_provider_id", decision.rejectedCandidates.single().reason)
    }

    @Test
    fun `top posters thumbnail selected with expired entitlement rejects and primary wins`() {
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
            policy = policy(
                ArtworkProviderSettings(
                    topPostersApiKey = "top-key",
                    selection = ArtworkProviderSelectionSettings(
                        thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS
                    ),
                    topPostersEntitlement = TopPostersEntitlementSnapshot(
                        valid = true,
                        isActive = true,
                        tier = 1,
                        tierName = "Premium",
                        episodeThumbnails = true,
                        verifiedAtMs = 1_000L,
                        expiresAtMs = System.currentTimeMillis() - 1_000L
                    )
                )
            )
        )

        assertEquals("TMDB", decision.selectedCandidate.provider?.key)
        assertEquals("topposters_entitlement_inactive", decision.rejectedCandidates.single().reason)
    }

    @Test
    fun `top posters thumbnail selected with premium entitlement wins over primary`() {
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                    role = ArtworkSourceRole.PRIMARY,
                    priority = 20,
                    imageType = ArtworkType.THUMBNAIL
                ),
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                    role = ArtworkSourceRole.PREMIUM,
                    priority = 10,
                    imageType = ArtworkType.THUMBNAIL
                )
            ),
            policy = policy(topPostersThumbnailSettings())
        )

        assertEquals("TOP_POSTERS", decision.selectedCandidate.provider?.key)
        assertEquals("premium_artwork_provider_precedence", decision.rejectedCandidates.single().reason)
    }

    @Test
    fun `only unsupported premium candidate returns no selected candidate with rejection trace`() {
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                    role = ArtworkSourceRole.PREMIUM,
                    priority = 10,
                    imageType = ArtworkType.THUMBNAIL
                )
            ),
            policy = policy(
                ArtworkProviderSettings(
                    topPostersApiKey = "top-key",
                    selection = ArtworkProviderSelectionSettings(
                        thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS
                    ),
                    topPostersEntitlement = null
                )
            )
        )

        assertNull(decision.selectedCandidateOrNull)
        assertEquals("topposters_entitlement_missing", decision.rejectedCandidates.single().reason)
    }

    @Test
    fun `premium poster wins over tvdb poster for tv`() {
        val tvProviderIds = ProviderIds(
            trakt = "171028",
            tmdb = "76479",
            tvdb = "355567",
            imdb = "tt1190634"
        )
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB),
                    role = ArtworkSourceRole.PRIMARY,
                    priority = 20,
                    providerIds = tvProviderIds
                ),
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                    role = ArtworkSourceRole.PREMIUM,
                    priority = 10,
                    providerIds = tvProviderIds
                )
            ),
            policy = policy(rpdbPosterSettings())
        )

        assertEquals("RPDB", decision.selectedCandidate.provider?.key)
        val rejected = decision.rejectedCandidates.single()
        assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB), rejected.provider)
        assertEquals("premium_artwork_provider_precedence", rejected.reason)
    }

    @Test
    fun `premium poster rejected with trace when no supported tv id`() {
        // Trakt-only IDs: RPDB does not support trakt for TV poster generation.
        val unsupportedTvIds = ProviderIds(trakt = "171028")
        val decision = router.select(
            candidates = listOf(
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                    role = ArtworkSourceRole.PREMIUM,
                    priority = 10,
                    providerIds = unsupportedTvIds
                ),
                candidate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB),
                    role = ArtworkSourceRole.PRIMARY,
                    priority = 20,
                    providerIds = unsupportedTvIds
                )
            ),
            policy = policy(rpdbPosterSettings())
        )

        assertEquals("TVDB", decision.selectedCandidate.provider?.key)
        val rejected = decision.rejectedCandidates.single()
        assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB), rejected.provider)
        // Production rejection vocabulary uses `missing_supported_provider_id` for the
        // "no supported id" case (plan calls this `NO_SUPPORTED_ID`).
        assertEquals("missing_supported_provider_id", rejected.reason)
    }

    @Test
    fun `router rejection reasons are stable codes without spaces`() {
        val decisions = listOf(
            router.select(
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
                policy = policy(topPostersPosterSettings())
            ),
            router.select(
                candidates = listOf(
                    candidate(
                        provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                        role = ArtworkSourceRole.PRIMARY,
                        priority = 10
                    ),
                    candidate(
                        provider = ArtworkProviderId.AddonPreview,
                        role = ArtworkSourceRole.CURRENT_PREVIEW,
                        priority = 20
                    )
                ),
                policy = policy()
            ),
            router.select(
                candidates = listOf(
                    candidate(
                        provider = ArtworkProviderId.AddonPreview,
                        role = ArtworkSourceRole.CURRENT_PREVIEW,
                        priority = 10
                    ),
                    candidate(
                        provider = ArtworkProviderId.RailPreview,
                        role = ArtworkSourceRole.OTHER_PREVIEW,
                        priority = 20
                    )
                ),
                policy = policy()
            ),
            router.select(
                candidates = listOf(
                    candidate(
                        provider = ArtworkProviderId.RailPreview,
                        role = ArtworkSourceRole.OTHER_PREVIEW,
                        priority = 10
                    ),
                    candidate(
                        provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                        role = ArtworkSourceRole.FALLBACK,
                        priority = 20
                    )
                ),
                policy = policy()
            ),
            router.select(
                candidates = listOf(
                    candidate(
                        provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                        role = ArtworkSourceRole.FALLBACK,
                        priority = 10
                    ),
                    candidate(
                        provider = ArtworkProviderId.Placeholder,
                        role = ArtworkSourceRole.PLACEHOLDER,
                        priority = 20
                    )
                ),
                policy = policy()
            ),
            router.select(
                candidates = listOf(
                    candidate(
                        provider = ArtworkProviderId.Placeholder,
                        role = ArtworkSourceRole.PLACEHOLDER,
                        priority = 10
                    ),
                    candidate(
                        provider = ArtworkProviderId.Placeholder,
                        role = ArtworkSourceRole.PLACEHOLDER,
                        priority = 20
                    )
                ),
                policy = policy()
            ),
            router.select(
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
                policy = policy()
            )
        )
        val reasons = decisions.flatMap { decision ->
            decision.rejectedCandidates.map { rejected -> rejected.reason }
        }

        assertFalse(reasons.any { reason -> reason.any(Char::isWhitespace) })
    }

    private fun policy(settings: ArtworkProviderSettings = ArtworkProviderSettings()): ArtworkRoutingPolicy =
        ArtworkRoutingPolicy(settings = settings)

    private fun candidate(
        provider: ArtworkProviderId,
        role: ArtworkSourceRole,
        priority: Int,
        providerIds: ProviderIds = ProviderIds(imdb = "tt0137523"),
        imageType: ArtworkType = ArtworkType.POSTER,
        source: ArtworkSource? = null
    ): ArtworkCandidate =
        ArtworkCandidate(
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            canonicalContentId = "imdb:tt0137523",
            providerIds = providerIds,
            imageType = imageType,
            provider = provider,
            sourceRole = role,
            source = source ?: (
                if (role == ArtworkSourceRole.PLACEHOLDER) {
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
                }
            ),
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

    private fun topPostersThumbnailSettings(
        tier: Int = 1,
        episodeThumbnails: Boolean = true
    ): ArtworkProviderSettings =
        ArtworkProviderSettings(
            topPostersApiKey = "top-key",
            selection = ArtworkProviderSelectionSettings(
                thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS
            ),
            topPostersEntitlement = TopPostersEntitlementSnapshot(
                valid = true,
                isActive = true,
                tier = tier,
                tierName = "Premium",
                episodeThumbnails = episodeThumbnails,
                verifiedAtMs = 1_000L,
                expiresAtMs = System.currentTimeMillis() + 86_400_000L
            )
        )
}
