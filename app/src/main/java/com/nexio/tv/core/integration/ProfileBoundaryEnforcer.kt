package com.nexio.tv.core.integration

import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import com.nexio.tv.core.trace.TraceHash
import java.util.concurrent.atomic.AtomicLong

object ProfileBoundaryEnforcer {
    @Volatile
    private var traceSink: RuntimeTraceSink = NoopRuntimeTraceSink

    @Volatile
    private var traceSessionId: () -> String? = { null }

    private val traceSeq = AtomicLong(0L)

    /**
     * Installs a sink/session-id pair for `profile.boundary_check` emissions.
     *
     * Called as a side-effect from `RuntimeTraceModule.provideRuntimeTraceSink` so the
     * Hilt graph wires the production sink at app start. Tests may swap directly.
     */
    @JvmStatic
    fun installTraceSink(sink: RuntimeTraceSink, sessionId: () -> String?) {
        this.traceSink = sink
        this.traceSessionId = sessionId
    }

    fun validateRequest(
        provider: IntegrationProvider,
        scope: IntegrationScope,
        cacheKey: String?,
        profileContext: ProfileExecutionContext?
    ) {
        try {
            rejectGlobalScopeForAuthenticatedProvider(provider, scope, profileContext)
            when (scope) {
                IntegrationScope.Global,
                IntegrationScope.GlobalContent -> validateGlobalCacheKey(cacheKey)

                is IntegrationScope.ProviderConfig -> validateGlobalCacheKey(cacheKey)

                is IntegrationScope.GlobalLocalizedContent -> validateGlobalCacheKey(cacheKey)

                IntegrationScope.GlobalEnglishImage -> validateImageCacheKey(cacheKey)

                is IntegrationScope.Profile -> validateLegacyProfileScope(
                    profileId = scope.profileId,
                    cacheKey = cacheKey,
                    profileContext = profileContext
                )

                is IntegrationScope.ProfileLocal -> validateProfileScope(
                    profileId = scope.profileId,
                    cacheKey = cacheKey,
                    profileContext = profileContext
                )

                is IntegrationScope.Account -> validateAccountScope(
                    provider = provider,
                    scope = scope,
                    cacheKey = cacheKey,
                    profileContext = profileContext
                )
            }
            emitBoundaryCheck(provider, scope, cacheKey, profileContext, verdict = "PASS")
        } catch (e: ProfileBoundaryException) {
            emitBoundaryCheck(
                provider = provider,
                scope = scope,
                cacheKey = cacheKey,
                profileContext = profileContext,
                verdict = "FAIL",
                violation = e.violation,
                message = e.message
            )
            throw e
        }
    }

    private fun emitBoundaryCheck(
        provider: IntegrationProvider,
        scope: IntegrationScope,
        cacheKey: String?,
        profileContext: ProfileExecutionContext?,
        verdict: String,
        violation: ProfileBoundaryViolation? = null,
        message: String? = null
    ) {
        if (traceSink === NoopRuntimeTraceSink) return
        val sid = traceSessionId() ?: return
        val profileHash = profileContext?.profileId?.let { TraceHash.of(sid, it.toString()) }
        val account = profileContext?.account(provider)
        val credentialHash = account?.credentialHash?.let { TraceHash.of(sid, it) }
        val keyContainsProfile = cacheKey?.contains("profile:") ?: false
        val keyContainsCredential = cacheKey?.contains("credential:") ?: false

        traceSink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = traceSeq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "profile.boundary_check",
                payload = mapOf(
                    "provider" to provider.name,
                    "scope" to scope.auditName,
                    "profileHash" to profileHash,
                    "credentialTraceHash" to credentialHash,
                    // Enforcer has no ProfileManager handle; reuse context's profile hash as
                    // the "active" stand-in per the runtime-trace-mode plan task 26.
                    "activeProfileHash" to profileHash,
                    "cacheKey" to cacheKey,
                    "cacheKeyContainsProfile" to keyContainsProfile,
                    "cacheKeyContainsCredentialHash" to keyContainsCredential,
                    "verdict" to verdict,
                    "violation" to violation?.name,
                    "message" to message
                )
            )
        )
    }

    /**
     * F-F-02: emits a `profile.boundary_check` trace event for a profile-switch attempt and
     * throws ProfileBoundaryException(PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK) if rejected.
     *
     * Routes the playback-active rejection through the same trace pipeline as cross-profile
     * write rejections (assertCanWriteProfileState).
     */
    fun assertCanSwitchProfile(
        activeProfileId: Int,
        targetProfileId: Int,
        hasActivePlaybackOwner: Boolean
    ) {
        if (hasActivePlaybackOwner && activeProfileId != targetProfileId) {
            emitProfileSwitchBoundaryCheck(
                activeProfileId = activeProfileId,
                targetProfileId = targetProfileId,
                verdict = "FAIL",
                violation = ProfileBoundaryViolation.PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK
            )
            throw ProfileBoundaryException(
                ProfileBoundaryViolation.PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK,
                "Cannot switch profile while playback is active (active=$activeProfileId, target=$targetProfileId)"
            )
        }
        emitProfileSwitchBoundaryCheck(
            activeProfileId = activeProfileId,
            targetProfileId = targetProfileId,
            verdict = "PASS",
            violation = null
        )
    }

    private fun emitProfileSwitchBoundaryCheck(
        activeProfileId: Int,
        targetProfileId: Int,
        verdict: String,
        violation: ProfileBoundaryViolation?
    ) {
        if (traceSink === NoopRuntimeTraceSink) return
        val sid = traceSessionId() ?: return
        val activeHash = TraceHash.of(sid, activeProfileId.toString())
        val targetHash = TraceHash.of(sid, targetProfileId.toString())
        traceSink.emit(
            TraceEventEnvelope(
                traceSessionId = sid,
                sequence = traceSeq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "profile.boundary_check",
                payload = mapOf(
                    "operation" to "profile.switch",
                    "activeProfileId" to activeProfileId,
                    "targetProfileId" to targetProfileId,
                    "activeProfileHash" to activeHash,
                    "targetProfileHash" to targetHash,
                    "verdict" to verdict,
                    "violation" to violation?.name
                )
            )
        )
    }

    fun assertCanWriteProfileState(
        resultProfileId: Int,
        resultSessionId: String,
        activeProfileId: Int,
        activeSessionId: String
    ) {
        if (resultProfileId != activeProfileId || resultSessionId != activeSessionId) {
            throw ProfileBoundaryException(
                ProfileBoundaryViolation.STALE_SESSION_WRITE_REJECTED,
                "Rejecting stale profile write for profile=$resultProfileId session=$resultSessionId activeProfile=$activeProfileId activeSession=$activeSessionId"
            )
        }
    }

    fun assertCanWriteProfileState(
        resultSession: ActiveProfileSession,
        activeSession: ActiveProfileSession
    ) {
        assertCanWriteProfileState(
            resultProfileId = resultSession.profileId,
            resultSessionId = resultSession.sessionId,
            activeProfileId = activeSession.profileId,
            activeSessionId = activeSession.sessionId
        )
    }

    private fun validateGlobalCacheKey(cacheKey: String?) {
        val key = cacheKey.orEmpty()
        if (PROFILE_KEY_PATTERN.containsMatchIn(key)) {
            throw ProfileBoundaryException(
                ProfileBoundaryViolation.GLOBAL_CACHE_KEY_CONTAINS_PROFILE_ID,
                "Global cache key must not contain profile id: $key"
            )
        }
    }

    private fun validateImageCacheKey(cacheKey: String?) {
        validateGlobalCacheKey(cacheKey)
        val key = cacheKey.orEmpty()
        val imageLanguage = IMAGE_LANGUAGE_PATTERN.find(key)?.groupValues?.getOrNull(1)
        if (imageLanguage != null && imageLanguage.lowercase() != "en") {
            throw ProfileBoundaryException(
                ProfileBoundaryViolation.IMAGE_CACHE_KEY_LANGUAGE_NOT_ENGLISH,
                "Image cache key must use imageLang:en: $key"
            )
        }
    }

    private fun validateProfileScope(
        profileId: Int,
        cacheKey: String?,
        profileContext: ProfileExecutionContext?
    ) {
        val context = profileContext ?: throw ProfileBoundaryException(
            ProfileBoundaryViolation.PROFILE_BOUND_SCOPE_MISSING_CONTEXT,
            "Profile-bound scope requires ProfileExecutionContext"
        )
        if (context.profileId != profileId) {
            throw ProfileBoundaryException(
                ProfileBoundaryViolation.PROFILE_SCOPE_CONTEXT_MISMATCH,
                "Scope profile=$profileId does not match context profile=${context.profileId}"
            )
        }
        val key = cacheKey.orEmpty()
        if (key.isBlank() || !profileOwnershipTokenPattern(profileId).containsMatchIn(key)) {
            throw ProfileBoundaryException(
                ProfileBoundaryViolation.PROFILE_CACHE_KEY_MISSING_PROFILE_ID,
                "Profile cache key must include profile:$profileId: $key"
            )
        }
    }

    private fun validateLegacyProfileScope(
        profileId: Int,
        cacheKey: String?,
        profileContext: ProfileExecutionContext?
    ) {
        if (profileContext == null) return
        validateProfileScope(profileId, cacheKey, profileContext)
    }

    private fun validateAccountScope(
        provider: IntegrationProvider,
        scope: IntegrationScope.Account,
        cacheKey: String?,
        profileContext: ProfileExecutionContext?
    ) {
        if (!scope.isExplicitAccountScope) {
            validateLegacyAccountScope(cacheKey)
            return
        }

        val profileId = requireNotNull(scope.profileId)
        val accountProvider = requireNotNull(scope.provider)
        val credentialHash = requireNotNull(scope.credentialHash)

        validateProfileScope(
            profileId = profileId,
            cacheKey = cacheKey,
            profileContext = profileContext
        )
        if (accountProvider != provider) {
            throw ProfileBoundaryException(
                ProfileBoundaryViolation.ACCOUNT_SCOPE_CONTEXT_MISSING_ACCOUNT,
                "Account scope provider=$accountProvider does not match request provider=$provider"
            )
        }
        if (credentialHash.isBlank()) {
            throw ProfileBoundaryException(
                ProfileBoundaryViolation.ACCOUNT_SCOPE_MISSING_CREDENTIAL_HASH,
                "Account scope requires credentialHash"
            )
        }
        validateAccountOwnershipKey(
            profileId = profileId,
            provider = accountProvider,
            credentialHash = credentialHash,
            cacheKey = cacheKey
        )
        val account = profileContext?.account(provider) ?: throw ProfileBoundaryException(
            ProfileBoundaryViolation.ACCOUNT_SCOPE_CONTEXT_MISSING_ACCOUNT,
            "Account scope requires account ref for provider=$provider"
        )
        if (account.provider != provider) {
            throw ProfileBoundaryException(
                ProfileBoundaryViolation.ACCOUNT_SCOPE_CONTEXT_MISSING_ACCOUNT,
                "Account scope context provider=${account.provider} does not match request provider=$provider"
            )
        }
        if (account.credentialHash != credentialHash) {
            throw ProfileBoundaryException(
                ProfileBoundaryViolation.ACCOUNT_SCOPE_CREDENTIAL_MISMATCH,
                "Account scope credentialHash does not match context credentialHash"
            )
        }
    }

    private fun validateLegacyAccountScope(cacheKey: String?) {
        val key = cacheKey.orEmpty()
        if (key.contains("profile:")) {
            throw ProfileBoundaryException(
                ProfileBoundaryViolation.ACCOUNT_SCOPE_CONTEXT_MISSING_ACCOUNT,
                "Legacy account scope cannot be used for profile-bound cache key: $key"
            )
        }
    }

    private fun validateAccountOwnershipKey(
        profileId: Int,
        provider: IntegrationProvider,
        credentialHash: String,
        cacheKey: String?
    ) {
        val key = cacheKey.orEmpty()
        val missingAccountTokens = !key.contains("provider:${provider.name}") ||
            !key.contains("credential:$credentialHash")
        if (missingAccountTokens) {
            throw ProfileBoundaryException(
                ProfileBoundaryViolation.PROFILE_CACHE_KEY_MISSING_PROFILE_ID,
                "Account key must include profile:$profileId, provider:${provider.name}, and credential:$credentialHash: $key"
            )
        }
    }

    private fun rejectGlobalScopeForAuthenticatedProvider(
        provider: IntegrationProvider,
        scope: IntegrationScope,
        profileContext: ProfileExecutionContext?
    ) {
        if (provider != IntegrationProvider.TRAKT && provider != IntegrationProvider.SIMKL) return
        if (profileContext == null) return
        val account = profileContext.account(provider) ?: return
        val isGlobalScope = scope is IntegrationScope.GlobalContent ||
            scope is IntegrationScope.GlobalLocalizedContent ||
            scope is IntegrationScope.GlobalEnglishImage ||
            scope === IntegrationScope.Global
        if (isGlobalScope) {
            throw ProfileBoundaryException(
                ProfileBoundaryViolation.AUTHENTICATED_PROVIDER_USED_GLOBAL_SCOPE,
                "Authenticated provider $provider with credentialHash=${account.credentialHash} cannot use Global scope ${scope.auditName}"
            )
        }
    }

    private val PROFILE_KEY_PATTERN = Regex("""(^|:)profile(:|-|\d)""")
    private val IMAGE_LANGUAGE_PATTERN = Regex("""imageLang:([^:]+)""", RegexOption.IGNORE_CASE)

    private fun profileOwnershipTokenPattern(profileId: Int): Regex =
        Regex("""(^|:)profile:${Regex.escape(profileId.toString())}(:|$)""")
}
