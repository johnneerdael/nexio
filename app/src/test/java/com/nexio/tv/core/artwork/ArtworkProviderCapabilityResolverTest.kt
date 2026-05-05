package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TopPostersEntitlementSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkProviderCapabilityResolverTest {
    private val resolver = ArtworkProviderCapabilityResolver()

    @Test
    fun `rpdb does not support raw kitsu ids`() {
        assertFalse(
            resolver.supports(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                imageType = ArtworkType.POSTER,
                ids = ProviderIds(kitsu = "7442"),
                mediaKind = MetadataMediaKind.ANIME
            )
        )
    }

    @Test
    fun `top posters supports imdb poster candidates`() {
        assertTrue(
            resolver.supports(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                imageType = ArtworkType.POSTER,
                ids = ProviderIds(imdb = "tt0137523"),
                mediaKind = MetadataMediaKind.MOVIE
            )
        )
    }

    @Test
    fun `rpdb selected for poster with imdb tmdb or tvdb supports`() {
        listOf(
            ProviderIds(imdb = "tt0137523"),
            ProviderIds(tmdb = "550"),
            ProviderIds(tvdb = "123")
        ).forEach { ids ->
            assertSupported(
                resolver.evaluate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                    imageType = ArtworkType.POSTER,
                    ids = ids,
                    mediaKind = MetadataMediaKind.MOVIE,
                    settings = rpdbSelectedSettings()
                )
            )
        }
    }

    @Test
    fun `rpdb selected with raw kitsu anime id rejects with missing supported provider id`() {
        assertRejected(
            reason = "missing_supported_provider_id",
            capability = resolver.evaluate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                imageType = ArtworkType.POSTER,
                ids = ProviderIds(kitsu = "7442"),
                mediaKind = MetadataMediaKind.ANIME,
                settings = rpdbSelectedSettings()
            )
        )
    }

    @Test
    fun `top posters selected for anime poster with kitsu id supports`() {
        assertSupported(
            resolver.evaluate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                imageType = ArtworkType.POSTER,
                ids = ProviderIds(kitsu = "7442"),
                mediaKind = MetadataMediaKind.ANIME,
                settings = topPostersSelectedSettings()
            )
        )
    }

    @Test
    fun `top posters selected for thumbnail without entitlement rejects with entitlement missing`() {
        assertRejected(
            reason = "topposters_entitlement_missing",
            capability = resolver.evaluate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                imageType = ArtworkType.THUMBNAIL,
                ids = ProviderIds(tmdb = "550"),
                mediaKind = MetadataMediaKind.MOVIE,
                settings = topPostersSelectedSettings(entitlement = null)
            )
        )
    }

    @Test
    fun `top posters selected for thumbnail with inactive entitlement rejects with entitlement inactive`() {
        assertRejected(
            reason = "topposters_entitlement_inactive",
            capability = resolver.evaluate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                imageType = ArtworkType.THUMBNAIL,
                ids = ProviderIds(tmdb = "550"),
                mediaKind = MetadataMediaKind.MOVIE,
                settings = topPostersSelectedSettings(entitlement = topPostersEntitlement(isActive = false))
            )
        )
    }

    @Test
    fun `top posters selected for thumbnail with free or pro tier rejects with tier not premium`() {
        listOf(2, 3).forEach { tier ->
            assertRejected(
                reason = "topposters_tier_not_premium",
                capability = resolver.evaluate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                    imageType = ArtworkType.THUMBNAIL,
                    ids = ProviderIds(tmdb = "550"),
                    mediaKind = MetadataMediaKind.MOVIE,
                    settings = topPostersSelectedSettings(entitlement = topPostersEntitlement(tier = tier))
                )
            )
        }
    }

    @Test
    fun `top posters selected for thumbnail with premium but episode thumbnails disabled rejects`() {
        assertRejected(
            reason = "topposters_episode_thumbnails_not_enabled",
            capability = resolver.evaluate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                imageType = ArtworkType.THUMBNAIL,
                ids = ProviderIds(tmdb = "550"),
                mediaKind = MetadataMediaKind.MOVIE,
                settings = topPostersSelectedSettings(
                    entitlement = topPostersEntitlement(episodeThumbnails = false)
                )
            )
        )
    }

    @Test
    fun `top posters selected for thumbnail with premium entitlement and kitsu tmdb or tvdb id supports`() {
        listOf(
            ProviderIds(kitsu = "7442"),
            ProviderIds(tmdb = "550"),
            ProviderIds(tvdb = "123")
        ).forEach { ids ->
            assertSupported(
                resolver.evaluate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                    imageType = ArtworkType.THUMBNAIL,
                    ids = ids,
                    mediaKind = MetadataMediaKind.ANIME,
                    settings = topPostersSelectedSettings(entitlement = topPostersEntitlement())
                )
            )
        }
    }

    @Test
    fun `top posters selected for anime poster with mal anilist or anidb id supports`() {
        listOf(
            ProviderIds(mal = "1"),
            ProviderIds(anilist = "1"),
            ProviderIds(anidb = "1")
        ).forEach { ids ->
            assertSupported(
                resolver.evaluate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                    imageType = ArtworkType.POSTER,
                    ids = ids,
                    mediaKind = MetadataMediaKind.ANIME,
                    settings = topPostersSelectedSettings()
                )
            )
        }
    }

    @Test
    fun `top posters selected without api key rejects with not configured`() {
        assertRejected(
            reason = "topposters_not_configured",
            capability = resolver.evaluate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                imageType = ArtworkType.POSTER,
                ids = ProviderIds(imdb = "tt0137523"),
                mediaKind = MetadataMediaKind.MOVIE,
                settings = ArtworkProviderSettings(
                    selection = ArtworkProviderSelectionSettings(
                        posterProvider = ArtworkProviderChoiceKey.TOP_POSTERS
                    )
                )
            )
        )
    }

    @Test
    fun `rpdb selected without api key rejects with not configured`() {
        assertRejected(
            reason = "rpdb_not_configured",
            capability = resolver.evaluate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                imageType = ArtworkType.POSTER,
                ids = ProviderIds(imdb = "tt0137523"),
                mediaKind = MetadataMediaKind.MOVIE,
                settings = ArtworkProviderSettings(
                    selection = ArtworkProviderSelectionSettings(
                        posterProvider = ArtworkProviderChoiceKey.RPDB
                    )
                )
            )
        )
    }

    @Test
    fun `rpdb selected for backdrop rejects with unsupported artwork type`() {
        assertRejected(
            reason = "unsupported_artwork_type_for_provider",
            capability = resolver.evaluate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                imageType = ArtworkType.BACKDROP,
                ids = ProviderIds(imdb = "tt0137523"),
                mediaKind = MetadataMediaKind.MOVIE,
                settings = ArtworkProviderSettings(
                    rpdbApiKey = "rpdb-key",
                    selection = ArtworkProviderSelectionSettings(
                        backdropProvider = ArtworkProviderChoiceKey.RPDB
                    )
                )
            )
        )
    }

    @Test
    fun `settings free compatibility evaluation uses descriptor reasons`() {
        assertRejected(
            reason = "missing_supported_provider_id",
            capability = resolver.evaluate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
                imageType = ArtworkType.POSTER,
                ids = ProviderIds(),
                mediaKind = MetadataMediaKind.ANIME
            )
        )
    }

    @Test
    fun `provider not selected for that artwork type rejects with provider not selected`() {
        assertRejected(
            reason = "provider_not_selected_for_artwork_type",
            capability = resolver.evaluate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                imageType = ArtworkType.POSTER,
                ids = ProviderIds(imdb = "tt0137523"),
                mediaKind = MetadataMediaKind.MOVIE,
                settings = ArtworkProviderSettings(
                    rpdbApiKey = "rpdb-key",
                    selection = ArtworkProviderSelectionSettings(
                        posterProvider = ArtworkProviderChoiceKey.DEFAULT
                    )
                )
            )
        )
    }

    private fun rpdbSelectedSettings(): ArtworkProviderSettings =
        ArtworkProviderSettings(
            rpdbApiKey = "rpdb-key",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        )

    private fun topPostersSelectedSettings(
        entitlement: TopPostersEntitlementSnapshot? = topPostersEntitlement()
    ): ArtworkProviderSettings =
        ArtworkProviderSettings(
            topPostersApiKey = "top-key",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.TOP_POSTERS,
                thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS
            ),
            topPostersEntitlement = entitlement
        )

    private fun topPostersEntitlement(
        isActive: Boolean = true,
        tier: Int = 1,
        episodeThumbnails: Boolean = true
    ): TopPostersEntitlementSnapshot =
        TopPostersEntitlementSnapshot(
            valid = true,
            isActive = isActive,
            tier = tier,
            tierName = when (tier) {
                1 -> "Premium"
                2 -> "Pro"
                else -> "Free"
            },
            episodeThumbnails = episodeThumbnails,
            verifiedAtMs = 1_000L,
            expiresAtMs = System.currentTimeMillis() + 86_400_000L
        )

    private fun assertSupported(capability: ArtworkProviderCapability) {
        assertTrue(capability.supported)
        assertEquals(null, capability.reason)
    }

    private fun assertRejected(reason: String, capability: ArtworkProviderCapability) {
        assertFalse(capability.supported)
        assertEquals(reason, capability.reason)
    }
}
