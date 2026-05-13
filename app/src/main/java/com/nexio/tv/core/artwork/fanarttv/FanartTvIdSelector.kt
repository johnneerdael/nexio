package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds

data class FanartTvCallId(val type: Type, val value: String) {
    enum class Type { MOVIE, TV }
}

class FanartTvIdSelector {
    fun select(mediaKind: MetadataMediaKind, providerIds: ProviderIds): FanartTvCallId? =
        when (mediaKind) {
            MetadataMediaKind.MOVIE ->
                providerIds.tmdb?.takeIf { it.isNotBlank() }
                    ?.let { FanartTvCallId(FanartTvCallId.Type.MOVIE, it) }
            MetadataMediaKind.SERIES ->
                providerIds.tvdb?.takeIf { it.isNotBlank() }
                    ?.let { FanartTvCallId(FanartTvCallId.Type.TV, it) }
            MetadataMediaKind.ANIME, MetadataMediaKind.UNKNOWN -> null
        }
}
