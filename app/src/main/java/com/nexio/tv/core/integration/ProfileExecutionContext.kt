package com.nexio.tv.core.integration

data class ActiveProfileSession(
    val profileId: Int,
    val sessionId: String,
    val sessionOrdinal: Long,
    val startedAtMs: Long
) {
    init {
        require(profileId > 0) { "ActiveProfileSession.profileId must be positive" }
        require(sessionId.isNotBlank()) { "ActiveProfileSession.sessionId must not be blank" }
        require(sessionOrdinal > 0L) { "ActiveProfileSession.sessionOrdinal must be positive" }
        require(startedAtMs > 0L) { "ActiveProfileSession.startedAtMs must be positive" }
    }
}

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
