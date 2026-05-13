package com.nexio.tv.core.artwork

import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.TopPostersEntitlementSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ArtworkProviderRegistryTest {
    private val registry = ArtworkProviderRegistry()

    @Test
    fun `no keys exposes only default`() {
        val choices = registry.availableChoices(ArtworkType.POSTER, ArtworkProviderSettings())
        assertEquals(ArtworkProviderChoiceKey.DEFAULT, choices.first())
        assertFalse(ArtworkProviderChoiceKey.TOP_POSTERS in choices)
        assertFalse(ArtworkProviderChoiceKey.RPDB in choices)
    }

    @Test
    fun `top posters configured with free or pro entitlement exposes poster choice but not thumbnail choice`() {
        val freeSettings = topPostersSettings(tier = 3)
        val proSettings = topPostersSettings(tier = 2)

        val posterChoices = registry.availableChoices(ArtworkType.POSTER, freeSettings)
        assertTrue(ArtworkProviderChoiceKey.DEFAULT in posterChoices)
        assertTrue(ArtworkProviderChoiceKey.TOP_POSTERS in posterChoices)

        val thumbnailFreeChoices = registry.availableChoices(ArtworkType.THUMBNAIL, freeSettings)
        assertEquals(listOf(ArtworkProviderChoiceKey.DEFAULT), thumbnailFreeChoices)

        val thumbnailProChoices = registry.availableChoices(ArtworkType.THUMBNAIL, proSettings)
        assertEquals(listOf(ArtworkProviderChoiceKey.DEFAULT), thumbnailProChoices)
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

        val choices = registry.availableChoices(ArtworkType.POSTER, settings)
        assertEquals(ArtworkProviderChoiceKey.DEFAULT, choices[0])
        assertTrue(ArtworkProviderChoiceKey.TOP_POSTERS in choices)
        assertTrue(ArtworkProviderChoiceKey.RPDB in choices)
        // Fanart.tv may be available depending on BuildConfig.FANARTTV_API_KEY
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

    @Test
    fun `FANART_TV is offered for poster, logo, backdrop when build key non-blank`() {
        val settings = ArtworkProviderSettings()
        val registry = ArtworkProviderRegistry()
        if (com.nexio.tv.BuildConfig.FANARTTV_API_KEY.isNotBlank()) {
            assertTrue(ArtworkProviderChoiceKey.FANART_TV in registry.availableChoices(ArtworkType.POSTER, settings))
            assertTrue(ArtworkProviderChoiceKey.FANART_TV in registry.availableChoices(ArtworkType.LOGO, settings))
            assertTrue(ArtworkProviderChoiceKey.FANART_TV in registry.availableChoices(ArtworkType.BACKDROP, settings))
            assertFalse(ArtworkProviderChoiceKey.FANART_TV in registry.availableChoices(ArtworkType.THUMBNAIL, settings))
        } else {
            assertFalse(ArtworkProviderChoiceKey.FANART_TV in registry.availableChoices(ArtworkType.POSTER, settings))
        }
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
