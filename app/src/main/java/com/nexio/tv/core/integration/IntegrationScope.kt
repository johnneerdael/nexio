package com.nexio.tv.core.integration

sealed class IntegrationScope(
    val storageKey: String,
    val auditName: String,
    val isProfileBound: Boolean
) {
    data object GlobalContent : IntegrationScope(
        storageKey = "global:content",
        auditName = "GlobalContent",
        isProfileBound = false
    )

    /**
     * F2-F-06: defined for globally-shared content keyed by display language and policy version.
     * NOT currently constructed by any production [IntegrationSpec] as of cluster H;
     * only the profile boundary audit golden test uses this scope for verification purposes.
     * Use [GlobalContent] for non-localized global content. Reserve this scope for future
     * adoption when production specs need per-language global cache partitioning.
     */
    data class GlobalLocalizedContent(
        val language: String,
        val localizationPolicyVersion: Int
    ) : IntegrationScope(
        storageKey = "global:localized:lang:${language.trim()}:policy:$localizationPolicyVersion",
        auditName = "GlobalLocalizedContent",
        isProfileBound = false
    ) {
        init {
            require(language.isNotBlank()) { "GlobalLocalizedContent.language must not be blank" }
            require(localizationPolicyVersion > 0) {
                "GlobalLocalizedContent.localizationPolicyVersion must be positive"
            }
        }
    }

    /**
     * F2-F-06: defined for English-only image fetches where the image language is always
     * fixed to "en" regardless of the user's display language. NOT currently constructed
     * by any production [IntegrationSpec] as of cluster H; image cache keys use a separate
     * `imageLang:en` token mechanism. The profile boundary audit golden test exercises this
     * scope for verification. Reserve for future direct adoption when image specs are
     * migrated to the integration runtime.
     */
    data object GlobalEnglishImage : IntegrationScope(
        storageKey = "global:image:lang:en",
        auditName = "GlobalEnglishImage",
        isProfileBound = false
    )

    @Deprecated(
        message = "Use GlobalContent, GlobalLocalizedContent, or GlobalEnglishImage.",
        replaceWith = ReplaceWith("IntegrationScope.GlobalContent")
    )
    data object Global : IntegrationScope(
        storageKey = "global",
        auditName = "Global",
        isProfileBound = false
    )

    data class ProviderConfig(val key: String) : IntegrationScope(
        storageKey = "provider-config:${key.trim()}",
        auditName = "ProviderConfig",
        isProfileBound = false
    ) {
        init {
            require(key.isNotBlank()) { "ProviderConfig.key must not be blank" }
        }
    }

    data class Profile(val profileId: Int) : IntegrationScope(
        storageKey = "profile:$profileId",
        auditName = "Profile",
        isProfileBound = true
    ) {
        init {
            require(profileId > 0) { "Profile.profileId must be positive" }
        }
    }

    class Account : IntegrationScope {
        val profileId: Int?
        val provider: IntegrationProvider?
        val credentialHash: String?

        constructor(
            profileId: Int,
            provider: IntegrationProvider,
            credentialHash: String
        ) : super(
            storageKey = "account:profile:$profileId:provider:${provider.name}:credential:${credentialHash.trim()}",
            auditName = "Account",
            isProfileBound = true
        ) {
            require(profileId > 0) { "Account.profileId must be positive" }
            require(credentialHash.isNotBlank()) { "Account.credentialHash must not be blank" }
            this.profileId = profileId
            this.provider = provider
            this.credentialHash = credentialHash.trim()
        }

        val isExplicitAccountScope: Boolean
            get() = profileId != null && provider != null && credentialHash != null

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Account) return false

            return profileId == other.profileId &&
                provider == other.provider &&
                credentialHash == other.credentialHash
        }

        override fun hashCode(): Int {
            var result = profileId ?: 0
            result = 31 * result + (provider?.hashCode() ?: 0)
            result = 31 * result + (credentialHash?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "Account(profileId=$profileId, provider=$provider, credentialHash=$credentialHash)"
    }

    data class ProfileLocal(val profileId: Int) : IntegrationScope(
        storageKey = "profile-local:$profileId",
        auditName = "ProfileLocal",
        isProfileBound = true
    ) {
        init {
            require(profileId > 0) { "ProfileLocal.profileId must be positive" }
        }
    }
}
