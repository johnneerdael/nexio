package com.nexio.tv.core.integration

data class ProfileExecutionContext(
    val profileId: Int,
    val sessionId: String,
    val displayLanguage: String,
    val region: String,
    val accounts: Map<IntegrationProvider, ProviderAccountRef> = emptyMap()
) {
    init {
        require(profileId > 0) { "ProfileExecutionContext.profileId must be positive" }
        require(sessionId.isNotBlank()) { "ProfileExecutionContext.sessionId must not be blank" }
        require(displayLanguage.isNotBlank()) { "ProfileExecutionContext.displayLanguage must not be blank" }
        require(region.isNotBlank()) { "ProfileExecutionContext.region must not be blank" }
    }

    fun account(provider: IntegrationProvider): ProviderAccountRef? = accounts[provider]
}

data class ProviderAccountRef(
    val provider: IntegrationProvider,
    val credentialHash: String,
    val accountIdHash: String?
) {
    init {
        require(credentialHash.isNotBlank()) { "ProviderAccountRef.credentialHash must not be blank" }
    }
}
