package com.nexio.tv.core.integration

object ProfileBoundaryEnforcer {
    fun validateRequest(
        provider: IntegrationProvider,
        scope: IntegrationScope,
        cacheKey: String?,
        profileContext: ProfileExecutionContext?
    ) {
        when (scope) {
            IntegrationScope.Global,
            IntegrationScope.GlobalContent -> validateGlobalCacheKey(cacheKey)

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
        if (key.isNotBlank() && !key.contains("profile:$profileId")) {
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
        val account = profileContext?.account(provider) ?: throw ProfileBoundaryException(
            ProfileBoundaryViolation.ACCOUNT_SCOPE_CONTEXT_MISSING_ACCOUNT,
            "Account scope requires account ref for provider=$provider"
        )
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

    private val PROFILE_KEY_PATTERN = Regex("""(^|:)profile(:|-|\d)""")
    private val IMAGE_LANGUAGE_PATTERN = Regex("""imageLang:([^:]+)""", RegexOption.IGNORE_CASE)
}
