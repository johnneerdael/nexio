package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkProviderSettingsTest {
    @Test
    fun `no keys gives default choices only`() {
        val settings = ArtworkProviderSettings()

        assertEquals(ArtworkProviderChoiceKey.DEFAULT, settings.selection.providerFor(ArtworkTypeKey.POSTER))
        assertFalse(settings.hasRpdbKey)
        assertFalse(settings.hasTopPostersKey)
        assertFalse(settings.topPostersCanProvideThumbnails)
    }

    @Test
    fun `top posters premium entitlement enables thumbnail capability`() {
        val now = System.currentTimeMillis()
        val settings = ArtworkProviderSettings(
            topPostersApiKey = "TP-test",
            topPostersEntitlement = TopPostersEntitlementSnapshot(
                valid = true,
                isActive = true,
                tier = 1,
                tierName = "Premium",
                episodeThumbnails = true,
                verifiedAtMs = 1_000L,
                expiresAtMs = now + 86_400_000L
            )
        )

        assertTrue(settings.hasTopPostersKey)
        assertTrue(settings.topPostersCanProvideThumbnails)
    }

    @Test
    fun `unverified top posters key exposes poster capability only`() {
        val settings = ArtworkProviderSettings(topPostersApiKey = "TP-test")

        assertTrue(settings.hasTopPostersKey)
        assertFalse(settings.topPostersCanProvideThumbnails)
    }

    @Test
    fun `inactive top posters entitlement does not enable thumbnails`() {
        val settings = ArtworkProviderSettings(
            topPostersApiKey = "TP-test",
            topPostersEntitlement = TopPostersEntitlementSnapshot(
                valid = true,
                isActive = false,
                tier = 1,
                tierName = "Premium",
                episodeThumbnails = true,
                verifiedAtMs = 1_000L,
                expiresAtMs = 86_401_000L
            )
        )

        assertFalse(settings.topPostersCanProvideThumbnails)
    }

    @Test
    fun `expired top posters entitlement does not enable thumbnails`() {
        val now = System.currentTimeMillis()
        val settings = ArtworkProviderSettings(
            topPostersApiKey = "TP-test",
            topPostersEntitlement = TopPostersEntitlementSnapshot(
                valid = true,
                isActive = true,
                tier = 1,
                tierName = "Premium",
                episodeThumbnails = true,
                verifiedAtMs = now - 86_400_000L,
                expiresAtMs = now - 1L
            )
        )

        assertFalse(settings.topPostersCanProvideThumbnails)
    }

    @Test
    fun `known stored artwork provider choice round trips`() {
        assertEquals(ArtworkProviderChoiceKey.RPDB, ArtworkProviderChoiceKey.fromStored("rpdb"))
        assertEquals(ArtworkProviderChoiceKey.TOP_POSTERS, ArtworkProviderChoiceKey.fromStored("top_posters"))
        assertEquals(ArtworkProviderChoiceKey.DEFAULT, ArtworkProviderChoiceKey.fromStored("default"))
    }

    @Test
    fun `null blank and unknown stored artwork provider choices default`() {
        assertEquals(ArtworkProviderChoiceKey.DEFAULT, ArtworkProviderChoiceKey.fromStored(null))
        assertEquals(ArtworkProviderChoiceKey.DEFAULT, ArtworkProviderChoiceKey.fromStored(""))
        assertEquals(ArtworkProviderChoiceKey.DEFAULT, ArtworkProviderChoiceKey.fromStored("  "))
        assertEquals(ArtworkProviderChoiceKey.DEFAULT, ArtworkProviderChoiceKey.fromStored("unknown"))
    }

    @Test
    fun `legacy enabled rpdb migrates to rpdb poster selection`() {
        val legacy = PosterRatingsSettings(
            rpdbEnabled = true,
            rpdbApiKey = "rpdb-key",
            topPostersEnabled = false,
            topPostersApiKey = "TP-unused"
        )

        val migrated = legacy.toArtworkProviderSettings()

        assertEquals(ArtworkProviderChoiceKey.RPDB, migrated.selection.posterProvider)
        assertEquals("rpdb-key", migrated.rpdbApiKey)
        assertEquals("TP-unused", migrated.topPostersApiKey)
    }

    @Test
    fun `legacy enabled top posters migrates to top posters poster selection`() {
        val legacy = PosterRatingsSettings(
            rpdbEnabled = false,
            rpdbApiKey = "rpdb-unused",
            topPostersEnabled = true,
            topPostersApiKey = "TP-key"
        )

        val migrated = legacy.toArtworkProviderSettings()

        assertEquals(ArtworkProviderChoiceKey.TOP_POSTERS, migrated.selection.posterProvider)
        assertEquals("rpdb-unused", migrated.rpdbApiKey)
        assertEquals("TP-key", migrated.topPostersApiKey)
    }

    @Test
    fun `legacy migration preserves rpdb precedence when both providers are active`() {
        val legacy = PosterRatingsSettings(
            rpdbEnabled = true,
            rpdbApiKey = "rpdb-key",
            topPostersEnabled = true,
            topPostersApiKey = "TP-key"
        )

        val migrated = legacy.toArtworkProviderSettings()

        assertEquals(PosterRatingsProvider.RPDB, legacy.activeProvider)
        assertEquals(ArtworkProviderChoiceKey.RPDB, migrated.selection.posterProvider)
        assertEquals("rpdb-key", migrated.rpdbApiKey)
        assertEquals("TP-key", migrated.topPostersApiKey)
    }

    @Test
    fun `legacy disabled provider with key keeps key but defaults selection`() {
        val legacy = PosterRatingsSettings(
            rpdbEnabled = false,
            rpdbApiKey = "rpdb-key",
            topPostersEnabled = false,
            topPostersApiKey = "TP-key"
        )

        val migrated = legacy.toArtworkProviderSettings()

        assertEquals(ArtworkProviderChoiceKey.DEFAULT, migrated.selection.posterProvider)
        assertEquals("rpdb-key", migrated.rpdbApiKey)
        assertEquals("TP-key", migrated.topPostersApiKey)
    }
}
