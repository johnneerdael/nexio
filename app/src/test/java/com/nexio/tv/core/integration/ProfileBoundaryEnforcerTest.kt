package com.nexio.tv.core.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBoundaryEnforcerTest {
    @Test
    fun `global localized scope stores language and policy version`() {
        val scope = IntegrationScope.GlobalLocalizedContent(
            language = "nl-NL",
            localizationPolicyVersion = 2
        )

        assertEquals("GlobalLocalizedContent", scope.auditName)
        assertEquals("global:localized:lang:nl-NL:policy:2", scope.storageKey)
        assertFalse(scope.isProfileBound)
    }

    @Test
    fun `account scope stores profile provider and credential hash`() {
        val scope = IntegrationScope.Account(
            profileId = 2,
            provider = IntegrationProvider.TRAKT,
            credentialHash = "abc123"
        )

        assertEquals("Account", scope.auditName)
        assertEquals("account:profile:2:provider:TRAKT:credential:abc123", scope.storageKey)
        assertTrue(scope.isProfileBound)
    }

    @Test
    fun `profile execution context exposes account refs by provider`() {
        val trakt = ProviderAccountRef(
            provider = IntegrationProvider.TRAKT,
            credentialHash = "traktHash",
            accountIdHash = "traktAccount"
        )
        val simkl = ProviderAccountRef(
            provider = IntegrationProvider.SIMKL,
            credentialHash = "simklHash",
            accountIdHash = "simklAccount"
        )
        val context = ProfileExecutionContext(
            profileId = 2,
            sessionId = "session-2",
            displayLanguage = "nl-NL",
            region = "NL",
            accounts = mapOf(
                IntegrationProvider.TRAKT to trakt,
                IntegrationProvider.SIMKL to simkl
            )
        )

        assertEquals(trakt, context.account(IntegrationProvider.TRAKT))
        assertEquals(simkl, context.account(IntegrationProvider.SIMKL))
    }

    @Test
    fun `global content cache key must not contain profile id`() {
        val exception = org.junit.Assert.assertThrows(ProfileBoundaryException::class.java) {
            ProfileBoundaryEnforcer.validateRequest(
                provider = IntegrationProvider.TMDB,
                scope = IntegrationScope.GlobalContent,
                cacheKey = "metadata:profile:2:TMDB:tmdb.movie.core:tmdb:550",
                profileContext = null
            )
        }

        assertEquals(ProfileBoundaryViolation.GLOBAL_CACHE_KEY_CONTAINS_PROFILE_ID, exception.violation)
    }

    @Test
    fun `profile local cache key must include profile id`() {
        val context = ProfileExecutionContext(
            profileId = 2,
            sessionId = "session-2",
            displayLanguage = "nl-NL",
            region = "NL"
        )

        val exception = org.junit.Assert.assertThrows(ProfileBoundaryException::class.java) {
            ProfileBoundaryEnforcer.validateRequest(
                provider = IntegrationProvider.TRAKT,
                scope = IntegrationScope.ProfileLocal(profileId = 2),
                cacheKey = "continue-watching:tt123",
                profileContext = context
            )
        }

        assertEquals(ProfileBoundaryViolation.PROFILE_CACHE_KEY_MISSING_PROFILE_ID, exception.violation)
    }

    @Test
    fun `profile local cache key accepts exact profile token`() {
        val context = ProfileExecutionContext(
            profileId = 2,
            sessionId = "session-2",
            displayLanguage = "nl-NL",
            region = "NL"
        )

        ProfileBoundaryEnforcer.validateRequest(
            provider = IntegrationProvider.TRAKT,
            scope = IntegrationScope.ProfileLocal(profileId = 2),
            cacheKey = "profile:2:continue-watching:tt123",
            profileContext = context
        )
    }

    @Test
    fun `profile local cache key must not be blank or null`() {
        val context = ProfileExecutionContext(
            profileId = 2,
            sessionId = "session-2",
            displayLanguage = "nl-NL",
            region = "NL"
        )

        listOf(null, " ").forEach { cacheKey ->
            val exception = org.junit.Assert.assertThrows(ProfileBoundaryException::class.java) {
                ProfileBoundaryEnforcer.validateRequest(
                    provider = IntegrationProvider.TRAKT,
                    scope = IntegrationScope.ProfileLocal(profileId = 2),
                    cacheKey = cacheKey,
                    profileContext = context
                )
            }

            assertEquals(ProfileBoundaryViolation.PROFILE_CACHE_KEY_MISSING_PROFILE_ID, exception.violation)
        }
    }

    @Test
    fun `profile local cache key must match exact profile token`() {
        val context = ProfileExecutionContext(
            profileId = 2,
            sessionId = "session-2",
            displayLanguage = "nl-NL",
            region = "NL"
        )

        val exception = org.junit.Assert.assertThrows(ProfileBoundaryException::class.java) {
            ProfileBoundaryEnforcer.validateRequest(
                provider = IntegrationProvider.TRAKT,
                scope = IntegrationScope.ProfileLocal(profileId = 2),
                cacheKey = "profile:20:continue-watching:tt123",
                profileContext = context
            )
        }

        assertEquals(ProfileBoundaryViolation.PROFILE_CACHE_KEY_MISSING_PROFILE_ID, exception.violation)
    }

    @Test
    fun `profile local scope requires matching execution context`() {
        val context = ProfileExecutionContext(
            profileId = 3,
            sessionId = "session-3",
            displayLanguage = "nl-NL",
            region = "NL"
        )

        val exception = org.junit.Assert.assertThrows(ProfileBoundaryException::class.java) {
            ProfileBoundaryEnforcer.validateRequest(
                provider = IntegrationProvider.TRAKT,
                scope = IntegrationScope.ProfileLocal(profileId = 2),
                cacheKey = "profile:2:continue-watching:tt123",
                profileContext = context
            )
        }

        assertEquals(ProfileBoundaryViolation.PROFILE_SCOPE_CONTEXT_MISMATCH, exception.violation)
    }

    @Test
    fun `account scope requires matching context account credential`() {
        val context = ProfileExecutionContext(
            profileId = 2,
            sessionId = "session-2",
            displayLanguage = "nl-NL",
            region = "NL",
            accounts = mapOf(
                IntegrationProvider.TRAKT to ProviderAccountRef(
                    provider = IntegrationProvider.TRAKT,
                    credentialHash = "good",
                    accountIdHash = null
                )
            )
        )

        val exception = org.junit.Assert.assertThrows(ProfileBoundaryException::class.java) {
            ProfileBoundaryEnforcer.validateRequest(
                provider = IntegrationProvider.TRAKT,
                scope = IntegrationScope.Account(
                    profileId = 2,
                    provider = IntegrationProvider.TRAKT,
                    credentialHash = "bad"
                ),
                cacheKey = "profile:2:provider:TRAKT:credential:bad:operation:trakt.user.lists",
                profileContext = context
            )
        }

        assertEquals(ProfileBoundaryViolation.ACCOUNT_SCOPE_CREDENTIAL_MISMATCH, exception.violation)
    }

    @Test
    fun `account scope cache key must match exact profile token`() {
        val context = ProfileExecutionContext(
            profileId = 2,
            sessionId = "session-2",
            displayLanguage = "nl-NL",
            region = "NL",
            accounts = mapOf(
                IntegrationProvider.TRAKT to ProviderAccountRef(
                    provider = IntegrationProvider.TRAKT,
                    credentialHash = "good",
                    accountIdHash = null
                )
            )
        )

        val exception = org.junit.Assert.assertThrows(ProfileBoundaryException::class.java) {
            ProfileBoundaryEnforcer.validateRequest(
                provider = IntegrationProvider.TRAKT,
                scope = IntegrationScope.Account(
                    profileId = 2,
                    provider = IntegrationProvider.TRAKT,
                    credentialHash = "good"
                ),
                cacheKey = "profile:20:provider:TRAKT:credential:good:operation:trakt.user.lists",
                profileContext = context
            )
        }

        assertEquals(ProfileBoundaryViolation.PROFILE_CACHE_KEY_MISSING_PROFILE_ID, exception.violation)
    }

    @Test
    fun `account scope requires context account ref provider to match request provider`() {
        val context = ProfileExecutionContext(
            profileId = 2,
            sessionId = "session-2",
            displayLanguage = "nl-NL",
            region = "NL",
            accounts = mapOf(
                IntegrationProvider.TRAKT to ProviderAccountRef(
                    provider = IntegrationProvider.SIMKL,
                    credentialHash = "good",
                    accountIdHash = null
                )
            )
        )

        val exception = org.junit.Assert.assertThrows(ProfileBoundaryException::class.java) {
            ProfileBoundaryEnforcer.validateRequest(
                provider = IntegrationProvider.TRAKT,
                scope = IntegrationScope.Account(
                    profileId = 2,
                    provider = IntegrationProvider.TRAKT,
                    credentialHash = "good"
                ),
                cacheKey = "profile:2:provider:TRAKT:credential:good:operation:trakt.user.lists",
                profileContext = context
            )
        }

        assertEquals(ProfileBoundaryViolation.ACCOUNT_SCOPE_CONTEXT_MISSING_ACCOUNT, exception.violation)
    }

    @Test
    fun `image scope rejects non english image key`() {
        val exception = org.junit.Assert.assertThrows(ProfileBoundaryException::class.java) {
            ProfileBoundaryEnforcer.validateRequest(
                provider = IntegrationProvider.TMDB,
                scope = IntegrationScope.GlobalEnglishImage,
                cacheKey = "artwork:TMDB:poster:tmdb:550:imageLang:nl:policy:1",
                profileContext = null
            )
        }

        assertEquals(ProfileBoundaryViolation.IMAGE_CACHE_KEY_LANGUAGE_NOT_ENGLISH, exception.violation)
    }

    @Test
    fun `stale profile state write is rejected`() {
        val exception = org.junit.Assert.assertThrows(ProfileBoundaryException::class.java) {
            ProfileBoundaryEnforcer.assertCanWriteProfileState(
                resultProfileId = 2,
                resultSessionId = "old-session",
                activeProfileId = 2,
                activeSessionId = "new-session"
            )
        }

        assertEquals(ProfileBoundaryViolation.STALE_SESSION_WRITE_REJECTED, exception.violation)
    }
}
