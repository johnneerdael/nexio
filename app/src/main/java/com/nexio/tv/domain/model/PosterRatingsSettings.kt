package com.nexio.tv.domain.model

enum class PosterRatingsProvider {
    NONE,
    RPDB,
    TOP_POSTERS
}

data class PosterRatingsSettings(
    val rpdbEnabled: Boolean = false,
    val rpdbApiKey: String = "",
    val topPostersEnabled: Boolean = false,
    val topPostersApiKey: String = ""
) {
    val activeProvider: PosterRatingsProvider
        get() = when {
            rpdbEnabled && rpdbApiKey.isNotBlank() -> PosterRatingsProvider.RPDB
            topPostersEnabled && topPostersApiKey.isNotBlank() -> PosterRatingsProvider.TOP_POSTERS
            else -> PosterRatingsProvider.NONE
        }
}

