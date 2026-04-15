package com.nexio.tv.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileModeRouterTest {
    private val router = ProfileModeRouter()

    @Test
    fun `profile one maps to default legacy route`() {
        assertEquals(ProfileModeRoute.DefaultLegacyRoute, router.routeFor(1))
        assertTrue(router.isDefaultLegacy(1))
        assertEquals(1, router.defaultLegacyProfileId())
    }

    @Test
    fun `profiles two through four map to secondary route`() {
        listOf(2, 3, 4).forEach { profileId ->
            assertEquals(
                ProfileModeRoute.SecondaryProfileRoute(profileId),
                router.routeFor(profileId)
            )
        }
    }

    @Test
    fun `invalid profile id fails closed instead of granting default legacy access`() {
        assertTrue(router.routeFor(0) is ProfileModeRoute.InvalidProfileRoute)
        assertTrue(router.routeFor(5) is ProfileModeRoute.InvalidProfileRoute)
    }
}
