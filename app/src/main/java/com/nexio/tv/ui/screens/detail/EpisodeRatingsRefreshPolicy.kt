package com.nexio.tv.ui.screens.detail

import com.nexio.tv.data.remote.normalizeCustomImdbBaseUrl
import com.nexio.tv.domain.model.ImdbSettings

internal data class ImdbEpisodeRatingsRefreshState(
    val isActive: Boolean,
    val normalizedBaseUrl: String,
    val apiKeyHash: Int
)

internal fun ImdbSettings.toEpisodeRatingsRefreshState(): ImdbEpisodeRatingsRefreshState {
    val trimmedApiKey = apiKey.trim()
    return ImdbEpisodeRatingsRefreshState(
        isActive = isActive,
        normalizedBaseUrl = normalizeCustomImdbBaseUrl(baseUrl),
        apiKeyHash = trimmedApiKey.hashCode()
    )
}

internal fun shouldReloadEpisodeRatingsForImdbSettingsChange(
    previous: ImdbEpisodeRatingsRefreshState?,
    current: ImdbEpisodeRatingsRefreshState
): Boolean {
    if (previous == null || previous == current) return false
    return previous.isActive || current.isActive
}
