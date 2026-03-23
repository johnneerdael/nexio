package com.nexio.tv.domain.model

data class ImdbSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = ""
) {
    val isActive: Boolean
        get() = enabled && baseUrl.isNotBlank() && apiKey.isNotBlank()
}
