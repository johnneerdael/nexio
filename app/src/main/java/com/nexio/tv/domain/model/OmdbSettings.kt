package com.nexio.tv.domain.model

data class OmdbSettings(
    val enabled: Boolean = false,
    val apiKey: String = ""
) {
    val isActive: Boolean
        get() = enabled && apiKey.isNotBlank()
}
