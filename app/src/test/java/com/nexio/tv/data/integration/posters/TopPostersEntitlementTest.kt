package com.nexio.tv.data.integration.posters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopPostersEntitlementTest {
    @Test
    fun `parse maps premium entitlement response to snapshot with expiry`() {
        val verifiedAtMs = 1_700_000_000_000L
        val ttlMs = 86_400_000L

        val snapshot = TopPostersEntitlementParser.parse(
            body = """
                {
                  "valid": true,
                  "is_active": true,
                  "tier": 1,
                  "tier_name": "Premium",
                  "tier_info": {
                    "features": {
                      "episode_thumbnails": true
                    }
                  }
                }
            """.trimIndent(),
            verifiedAtMs = verifiedAtMs,
            ttlMs = ttlMs
        )

        assertTrue(snapshot.valid)
        assertTrue(snapshot.isActive)
        assertEquals(1, snapshot.tier)
        assertEquals("Premium", snapshot.tierName)
        assertTrue(snapshot.episodeThumbnails)
        assertEquals(verifiedAtMs, snapshot.verifiedAtMs)
        assertEquals(verifiedAtMs + ttlMs, snapshot.expiresAtMs)
    }

    @Test
    fun `parse defaults missing episode thumbnail feature to false`() {
        val snapshot = TopPostersEntitlementParser.parse(
            body = """
                {
                  "valid": true,
                  "is_active": true,
                  "tier": 1,
                  "tier_name": "Premium"
                }
            """.trimIndent(),
            verifiedAtMs = 1_700_000_000_000L,
            ttlMs = 86_400_000L
        )

        assertFalse(snapshot.episodeThumbnails)
    }
}
