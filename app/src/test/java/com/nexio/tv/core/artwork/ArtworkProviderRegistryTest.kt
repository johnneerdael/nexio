package com.nexio.tv.core.artwork

import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.TopPostersEntitlementSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkProviderRegistryTest {
    private val registry = ArtworkProviderRegistry()

    @Test
    fun `no keys exposes only default`() {
        assertEquals(
            listOf(ArtworkProviderChoiceKey.DEFAULT),
            registry.availableChoices(ArtworkType.POSTER, ArtworkProviderSettings())
        )
    }

    @Test
    fun `top posters configured with free or pro entitlement exposes poster choice but not thumbnail choice`() {
        val freeSettings = topPostersSettings(tier = 3)
        val proSettings = topPostersSettings(tier = 2)

        assertEquals(
            listOf(ArtworkProviderChoiceKey.DEFAULT, ArtworkProviderChoiceKey.TOP_POSTERS),
            registry.availableChoices(ArtworkType.POSTER, freeSettings)
        )
        assertEquals(
            listOf(ArtworkProviderChoiceKey.DEFAULT),
            registry.availableChoices(ArtworkType.THUMBNAIL, freeSettings)
        )
        assertEquals(
            listOf(ArtworkProviderChoiceKey.DEFAULT),
            registry.availableChoices(ArtworkType.THUMBNAIL, proSettings)
        )
    }

    @Test
    fun `top posters premium entitlement with episode thumbnails exposes thumbnail choice`() {
        val settings = topPostersSettings(tier = 1, episodeThumbnails = true)

        assertEquals(
            listOf(ArtworkProviderChoiceKey.DEFAULT, ArtworkProviderChoiceKey.TOP_POSTERS),
            registry.availableChoices(ArtworkType.THUMBNAIL, settings)
        )
    }

    @Test
    fun `inactive or expired top posters entitlement hides thumbnail choice`() {
        val inactive = topPostersSettings(isActive = false)
        val expired = topPostersSettings(expiresAtMs = System.currentTimeMillis() - 1L)

        assertEquals(
            listOf(ArtworkProviderChoiceKey.DEFAULT),
            registry.availableChoices(ArtworkType.THUMBNAIL, inactive)
        )
        assertEquals(
            listOf(ArtworkProviderChoiceKey.DEFAULT),
            registry.availableChoices(ArtworkType.THUMBNAIL, expired)
        )
    }

    @Test
    fun `rpdb and top posters configured exposes poster choices in deterministic order`() {
        val settings = ArtworkProviderSettings(
            rpdbApiKey = "rpdb-key",
            topPostersApiKey = "top-key"
        )

        assertEquals(
            listOf(
                ArtworkProviderChoiceKey.DEFAULT,
                ArtworkProviderChoiceKey.TOP_POSTERS,
                ArtworkProviderChoiceKey.RPDB
            ),
            registry.availableChoices(ArtworkType.POSTER, settings)
        )
    }

    @Test
    fun `configured provider choices follow descriptor order after default`() {
        val settings = ArtworkProviderSettings(
            rpdbApiKey = "rpdb-key",
            topPostersApiKey = "top-key"
        )

        assertEquals(
            listOf(ArtworkProviderChoiceKey.DEFAULT) + artworkProviderDescriptors.map { it.choice },
            registry.availableChoices(ArtworkType.POSTER, settings)
        )
    }

    @Test
    fun `provider choice maps to runtime provider id for rpdb and top posters`() {
        artworkProviderDescriptors.forEach { descriptor ->
            assertEquals(
                ArtworkProviderId.RuntimeProvider(descriptor.provider),
                registry.providerIdFor(descriptor.choice)
            )
        }
    }

    @Test
    fun `unknown provider choice maps to null safely`() {
        assertNull(registry.providerIdFor(ArtworkProviderChoiceKey.DEFAULT))
        assertNull(registry.providerIdFor(ArtworkProviderChoiceKey("future_provider")))
    }

    private fun topPostersSettings(
        tier: Int = 1,
        isActive: Boolean = true,
        episodeThumbnails: Boolean = true,
        expiresAtMs: Long = System.currentTimeMillis() + 86_400_000L
    ): ArtworkProviderSettings =
        ArtworkProviderSettings(
            topPostersApiKey = "top-key",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.TOP_POSTERS,
                thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS
            ),
            topPostersEntitlement = TopPostersEntitlementSnapshot(
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
                expiresAtMs = expiresAtMs
            )
        )
}
