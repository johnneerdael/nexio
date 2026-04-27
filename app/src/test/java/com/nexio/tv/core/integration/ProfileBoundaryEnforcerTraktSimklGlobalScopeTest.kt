package com.nexio.tv.core.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileBoundaryEnforcerTraktSimklGlobalScopeTest {
    private fun authedContext(provider: IntegrationProvider, profileId: Int = 1) =
        ProfileExecutionContext(
            profileId = profileId,
            sessionId = "s",
            displayLanguage = "en",
            region = "US",
            accounts = mapOf(provider to ProviderAccountRef(provider, "h", null))
        )

    @Test
    fun `trakt with GlobalContent and authed context is rejected`() {
        val ex = assertThrows(ProfileBoundaryException::class.java) {
            ProfileBoundaryEnforcer.validateRequest(
                provider = IntegrationProvider.TRAKT,
                scope = IntegrationScope.GlobalContent,
                cacheKey = "global:trakt:trending",
                profileContext = authedContext(IntegrationProvider.TRAKT)
            )
        }
        assertEquals(ProfileBoundaryViolation.AUTHENTICATED_PROVIDER_USED_GLOBAL_SCOPE, ex.violation)
    }

    @Test
    fun `simkl with GlobalLocalizedContent and authed context is rejected`() {
        val ex = assertThrows(ProfileBoundaryException::class.java) {
            ProfileBoundaryEnforcer.validateRequest(
                provider = IntegrationProvider.SIMKL,
                scope = IntegrationScope.GlobalLocalizedContent("nl", 1),
                cacheKey = "global:simkl:lang:nl",
                profileContext = authedContext(IntegrationProvider.SIMKL)
            )
        }
        assertEquals(ProfileBoundaryViolation.AUTHENTICATED_PROVIDER_USED_GLOBAL_SCOPE, ex.violation)
    }

    @Test
    fun `trakt device-auth with Profile scope is allowed`() {
        ProfileBoundaryEnforcer.validateRequest(
            provider = IntegrationProvider.TRAKT,
            scope = IntegrationScope.Profile(profileId = 1),
            cacheKey = "profile:1:trakt:device-auth",
            profileContext = ProfileExecutionContext(
                profileId = 1,
                sessionId = "s",
                displayLanguage = "en",
                region = "US"
            )
        )
    }

    @Test
    fun `tmdb with GlobalContent passes`() {
        ProfileBoundaryEnforcer.validateRequest(
            provider = IntegrationProvider.TMDB,
            scope = IntegrationScope.GlobalContent,
            cacheKey = "metadata:tmdb:550",
            profileContext = null
        )
    }
}
