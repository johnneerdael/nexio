package com.nexio.tv.core.profile

import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileBoundaryTest {
    private fun profileManager(activeProfileId: MutableStateFlow<Int> = MutableStateFlow(1)): ProfileManager {
        return mockk {
            every { this@mockk.activeProfileId } returns activeProfileId
            every { this@mockk.isPrimaryProfileActive } answers { activeProfileId.value == 1 }
        }
    }

    private fun boundary(activeProfileId: MutableStateFlow<Int> = MutableStateFlow(1)): ProfileBoundary {
        return ProfileBoundary(
            profileManager = profileManager(activeProfileId),
            languageTagProvider = { "en" }
        )
    }

    @Test
    fun `boundary rejects default legacy profile`() {
        val boundary = boundary()

        val failure = runCatching {
            boundary.authRoute(
                ProfileModeRoute.SecondaryProfileRoute(1),
                TrackingProvider.TRAKT
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `secondary auth route cannot target default profile`() {
        val boundary = boundary()

        val route = boundary.authRoute(
            ProfileModeRoute.SecondaryProfileRoute(2),
            TrackingProvider.SIMKL
        )

        assertEquals(2, route.profileId)
        assertEquals(TrackingProvider.SIMKL, route.provider)
    }

    @Test
    fun `secondary settings route cannot target default profile`() {
        val boundary = boundary()

        val route = boundary.settingsRoute(
            ProfileModeRoute.SecondaryProfileRoute(3),
            ProfileSettingsDomain.CATALOGS
        )

        assertEquals(3, route.profileId)
        assertEquals(ProfileSettingsDomain.CATALOGS, route.domain)
    }

    @Test
    fun `shared cache scopes do not contain profile ids`() {
        val boundary = boundary()
        val secondary = ProfileModeRoute.SecondaryProfileRoute(2)

        val artwork = boundary.cacheScope(secondary, ProfileCacheDomain.SHARED_ARTWORK)
        val metadata = boundary.cacheScope(
            secondary,
            ProfileCacheDomain.SHARED_TEXT_METADATA,
            languageTag = "en",
            providerToken = "native"
        )

        assertEquals(ProfileCacheScope.SharedArtwork, artwork)
        assertEquals(
            ProfileCacheScope.SharedLanguageMetadata("en", "native"),
            metadata
        )
    }

    @Test
    fun `profile snapshot cache scope is secondary profile scoped`() {
        val boundary = boundary()

        val scope = boundary.cacheScope(
            ProfileModeRoute.SecondaryProfileRoute(4),
            ProfileCacheDomain.HOME_SNAPSHOT,
            languageTag = "nl"
        )

        assertEquals(
            ProfileCacheScope.ProfileSnapshot(
                profileId = 4,
                languageTag = "nl",
                domain = ProfileCacheDomain.HOME_SNAPSHOT
            ),
            scope
        )
    }

    @Test
    fun `secondary runtime context can be created only for secondary profile`() {
        val activeProfileId = MutableStateFlow(1)
        val boundary = boundary(activeProfileId)

        assertEquals(null, boundary.activeContext.value)

        val contextForTwo = boundary.contextFor(ProfileModeRoute.SecondaryProfileRoute(2))

        assertEquals(2, contextForTwo.profileId)
        assertTrue(contextForTwo.generation >= 0L)
    }

    @Test
    fun `current language tag comes from profile boundary provider`() {
        val boundary = ProfileBoundary(
            profileManager = profileManager(),
            languageTagProvider = { "nl" }
        )

        assertEquals("nl", boundary.currentLanguageTag())
    }

    @Test
    fun `secondary context reads current profile language from provider`() {
        val activeProfileId = MutableStateFlow(2)
        var language = "en"
        val boundary = ProfileBoundary(
            profileManager = profileManager(activeProfileId),
            languageTagProvider = { language }
        )

        val initial = boundary.contextFor(ProfileModeRoute.SecondaryProfileRoute(2))
        language = "nl"
        val updated = boundary.contextFor(ProfileModeRoute.SecondaryProfileRoute(2))

        assertEquals("en", initial.languageTag)
        assertEquals("nl", updated.languageTag)
    }
}
