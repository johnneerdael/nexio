package com.nexio.tv.ui.screens.home.order

import com.nexio.tv.core.integration.RailKeyFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard tests for the account-owned key scoping invariant.
 *
 * `RailKeyFactory` encodes account scope indirectly via `profileId`: every account-owned
 * rail (Trakt user lists, SIMKL personal lists, home catalogs) is keyed under
 * `profile:<profileId>:...`. Different provider accounts on the device are bound to
 * different profiles, so per-profile namespacing is what prevents cross-account
 * collisions.
 *
 * These tests lock in that invariant so a future refactor cannot accidentally drop
 * the profile scope and re-introduce key collisions across accounts/profiles.
 */
class HomeRailKeyScopingTest {

    @Test
    fun `trakt library keys with same listKey on different profiles do not collide`() {
        val keyForProfileA = RailKeyFactory.traktLibrary(profileId = 1, listKey = "watchlist")
        val keyForProfileB = RailKeyFactory.traktLibrary(profileId = 2, listKey = "watchlist")
        assertNotEquals(
            "Same Trakt list on different profiles must produce distinct keys",
            keyForProfileA,
            keyForProfileB
        )
    }

    @Test
    fun `simkl library keys with same listKey on different profiles do not collide`() {
        val keyForProfileA = RailKeyFactory.simklLibrary(profileId = 1, listKey = "watching")
        val keyForProfileB = RailKeyFactory.simklLibrary(profileId = 2, listKey = "watching")
        assertNotEquals(
            "Same SIMKL list on different profiles must produce distinct keys",
            keyForProfileA,
            keyForProfileB
        )
    }

    @Test
    fun `home catalog keys with same catalogId on different profiles do not collide`() {
        val keyForProfileA = RailKeyFactory.homeCatalog(profileId = 1, catalogId = "tmdb:popular:movies")
        val keyForProfileB = RailKeyFactory.homeCatalog(profileId = 2, catalogId = "tmdb:popular:movies")
        assertNotEquals(
            "Same home catalog on different profiles must produce distinct keys",
            keyForProfileA,
            keyForProfileB
        )
    }

    @Test
    fun `trakt and simkl namespaces never overlap on the same profile`() {
        val traktKey = RailKeyFactory.traktLibrary(profileId = 5, listKey = "watchlist")
        val simklKey = RailKeyFactory.simklLibrary(profileId = 5, listKey = "watchlist")
        assertNotEquals(
            "Identical listKey under different providers must not collapse to the same key",
            traktKey,
            simklKey
        )
        assertFalse(
            "Trakt key must not fall under the SIMKL namespace",
            traktKey.startsWith(RailKeyFactory.simklLibraryNamespace(profileId = 5))
        )
        assertFalse(
            "SIMKL key must not fall under the Trakt namespace",
            simklKey.startsWith(RailKeyFactory.traktLibraryNamespace(profileId = 5))
        )
    }

    @Test
    fun `account-owned keys live under the profile-scoped namespace`() {
        val profileId = 9
        val traktKey = RailKeyFactory.traktLibrary(profileId = profileId, listKey = "favorites")
        val simklKey = RailKeyFactory.simklLibrary(profileId = profileId, listKey = "plantowatch")
        val catalogKey = RailKeyFactory.homeCatalog(profileId = profileId, catalogId = "tmdb:popular:tv")
        assertTrue(
            "Trakt account-owned keys must be profile-scoped",
            traktKey.startsWith("profile:$profileId:")
        )
        assertTrue(
            "SIMKL account-owned keys must be profile-scoped",
            simklKey.startsWith("profile:$profileId:")
        )
        assertTrue(
            "Home catalog keys must be profile-scoped",
            catalogKey.startsWith("profile:$profileId:")
        )
    }

    @Test
    fun `same profile and same listKey produces identical keys (sanity check)`() {
        val a = RailKeyFactory.traktLibrary(profileId = 4, listKey = "watchlist")
        val b = RailKeyFactory.traktLibrary(profileId = 4, listKey = "watchlist")
        assertEquals("Equal inputs must produce equal keys", a, b)
    }
}
