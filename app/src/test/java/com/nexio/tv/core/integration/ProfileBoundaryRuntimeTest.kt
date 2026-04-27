package com.nexio.tv.core.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBoundaryRuntimeTest {
    @Test
    fun `integration spec rejects profile bound scope without context`() {
        val exception = assertThrows(ProfileBoundaryException::class.java) {
            IntegrationSpec(
                provider = IntegrationProvider.TRAKT,
                apiShapeId = "trakt.user.lists",
                operationKey = "trakt.user.lists",
                cacheKey = "profile:2:provider:TRAKT:credential:abc:operation:trakt.user.lists",
                codec = stringCodec(),
                cachePolicy = IntegrationCachePolicy.CacheFirst(ttlMs = 60_000L, staleAfterExpiryMs = 60_000L),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.Account(
                    profileId = 2,
                    provider = IntegrationProvider.TRAKT,
                    credentialHash = "abc"
                ),
                load = { IntegrationLoadResult.Success("ok") }
            )
        }

        assertEquals(ProfileBoundaryViolation.PROFILE_BOUND_SCOPE_MISSING_CONTEXT, exception.violation)
    }

    @Test
    fun `integration call spec accepts account scope with matching context`() {
        val context = ProfileExecutionContext(
            profileId = 2,
            sessionId = "session-2",
            displayLanguage = "en-US",
            region = "US",
            accounts = mapOf(
                IntegrationProvider.TRAKT to ProviderAccountRef(
                    provider = IntegrationProvider.TRAKT,
                    credentialHash = "abc",
                    accountIdHash = null
                )
            )
        )

        val spec = IntegrationCallSpec(
            provider = IntegrationProvider.TRAKT,
            apiShapeId = "trakt.scrobble",
            operationKey = "profile:2:provider:TRAKT:credential:abc:operation:trakt.scrobble.start",
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.Account(
                profileId = 2,
                provider = IntegrationProvider.TRAKT,
                credentialHash = "abc"
            ),
            profileContext = context,
            call = { IntegrationCallResult.Success("ok") }
        )

        assertEquals("Account", spec.scope.auditName)
    }

    @Test
    fun `account call spec rejects keys missing account isolation tokens`() {
        val context = accountContext(
            profileId = 2,
            provider = IntegrationProvider.SIMKL,
            credentialHash = "simkl:profile:2"
        )

        val exception = assertThrows(ProfileBoundaryException::class.java) {
            IntegrationCallSpec(
                provider = IntegrationProvider.SIMKL,
                apiShapeId = "simkl.scrobble",
                operationKey = "profile:2:operation:simkl.scrobble.start",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.Account(
                    profileId = 2,
                    provider = IntegrationProvider.SIMKL,
                    credentialHash = "simkl:profile:2"
                ),
                profileContext = context,
                call = { IntegrationCallResult.Success("ok") }
            )
        }

        assertEquals(ProfileBoundaryViolation.PROFILE_CACHE_KEY_MISSING_PROFILE_ID, exception.violation)
    }

    @Test
    fun `simkl and trakt account call keys include profile provider credential and operation`() {
        val accountKeys = listOf(
            AccountKeyFixture(
                provider = IntegrationProvider.SIMKL,
                profileId = 2,
                credentialHash = "simkl:profile:2",
                operationName = "simkl.scrobble.start"
            ),
            AccountKeyFixture(
                provider = IntegrationProvider.TRAKT,
                profileId = 7,
                credentialHash = "trakt:profile:7",
                operationName = "trakt.scrobble.start"
            )
        )

        accountKeys.forEach { fixture ->
            val operationKey = "profile:${fixture.profileId}:provider:${fixture.provider.name}:credential:${fixture.credentialHash}:operation:${fixture.operationName}"
            val spec = IntegrationCallSpec(
                provider = fixture.provider,
                apiShapeId = fixture.operationName.substringBeforeLast('.'),
                operationKey = operationKey,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.Account(
                    profileId = fixture.profileId,
                    provider = fixture.provider,
                    credentialHash = fixture.credentialHash
                ),
                profileContext = accountContext(
                    profileId = fixture.profileId,
                    provider = fixture.provider,
                    credentialHash = fixture.credentialHash
                ),
                call = { IntegrationCallResult.Success("ok") }
            )

            assertEquals("Account", spec.scope.auditName)
            assertTrue(operationKey.contains("profile:${fixture.profileId}"))
            assertTrue(operationKey.contains("provider:${fixture.provider.name}"))
            assertTrue(operationKey.contains("credential:${fixture.credentialHash}"))
            assertTrue(operationKey.contains("operation:${fixture.operationName}"))
        }
    }

    @Test
    fun `audit event exposes profile boundary scope details`() {
        val event = IntegrationAuditEvent(
            traceId = "trace",
            provider = IntegrationProvider.TMDB,
            apiShapeId = "tmdb.movie.core",
            headerPolicyId = "tmdb-v3",
            adapterClass = "TmdbIntegrationProvider",
            cacheKeyHash = "hash",
            cacheKeyTemplate = "metadata:TMDB:tmdb.movie.core:tmdb:550:lang:nl-NL:policy:2",
            scopeKeyHash = "scope",
            scopeName = "GlobalLocalizedContent",
            profileId = null,
            accountProvider = null,
            credentialHash = null,
            language = "nl-NL",
            imageLanguage = null,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            cachePolicy = "CacheFirst",
            codec = "Json",
            laneConcurrency = 4,
            playbackActive = false,
            phase = IntegrationAuditPhase.REQUEST_RECEIVED,
            outcome = null,
            httpStatus = null,
            retryAfterMs = null,
            freshUntilEpochMs = null,
            staleUntilEpochMs = null,
            networkStarted = false,
            loaderInvoked = false,
            durationMs = 0L,
            timestampEpochMs = 1L
        )

        assertEquals("GlobalLocalizedContent", event.scopeName)
        assertEquals("nl-NL", event.language)
    }

    private fun stringCodec(): IntegrationCodec<String> =
        object : IntegrationCodec<String> {
            override val mimeType: String = "text/plain"
            override fun encode(value: String): ByteArray = value.toByteArray()
            override fun decode(bytes: ByteArray): String = bytes.decodeToString()
        }

    private data class AccountKeyFixture(
        val provider: IntegrationProvider,
        val profileId: Int,
        val credentialHash: String,
        val operationName: String
    )

    private fun accountContext(
        profileId: Int,
        provider: IntegrationProvider,
        credentialHash: String
    ): ProfileExecutionContext =
        ProfileExecutionContext(
            profileId = profileId,
            sessionId = "session-$profileId",
            displayLanguage = "en-US",
            region = "US",
            accounts = mapOf(
                provider to ProviderAccountRef(
                    provider = provider,
                    credentialHash = credentialHash,
                    accountIdHash = null
                )
            )
        )
}
