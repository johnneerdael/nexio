package com.nexio.tv.core.metadata.composition

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.ProfileBoundaryEnforcer
import com.nexio.tv.core.integration.ProfileExecutionContext
import org.junit.Assert.assertThrows
import org.junit.Test

class CompositionCacheBoundaryTest {
    @Test
    fun `resolved display key with profile token is rejected under global scope`() {
        // Composition produced a profile-resolved cache key but accidentally tagged it Global.
        // Enforcer must reject — global keys can never carry profile id.
        assertThrows(IllegalArgumentException::class.java) {
            ProfileBoundaryEnforcer.validateRequest(
                provider = IntegrationProvider.TMDB,
                scope = IntegrationScope.GlobalContent,
                cacheKey = "metadata:profile:1:resolved-display:tt1234:nl:v1",
                profileContext = null
            )
        }
    }

    @Test
    fun `resolved display key under profile-local scope passes`() {
        val context = ProfileExecutionContext(
            profileId = 1,
            sessionId = "s1",
            displayLanguage = "nl",
            region = "NL"
        )
        ProfileBoundaryEnforcer.validateRequest(
            provider = IntegrationProvider.TMDB,
            scope = IntegrationScope.ProfileLocal(profileId = 1),
            cacheKey = "profile:1:resolved-display:tt1234:nl:v1",
            profileContext = context
        )
    }
}
