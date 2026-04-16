package com.nexio.tv.domain.model

enum class TvdbValidationStatus {
    NOT_CONFIGURED,
    VALIDATING,
    VALID,
    INVALID,
    FALLBACK_ACTIVE
}

data class TvdbSettings(
    val enabled: Boolean = true,
    val apiKey: String = "",
    val subscriberPin: String = "",
    val validationStatus: TvdbValidationStatus = TvdbValidationStatus.VALID,
    val lastFailure: String = "",
    val lastValidatedAtEpochMs: Long? = null
) {
    val configured: Boolean get() = true
    val hasCustomCredentials: Boolean get() = apiKey.isNotBlank()
    val isActive: Boolean get() = enabled
}
