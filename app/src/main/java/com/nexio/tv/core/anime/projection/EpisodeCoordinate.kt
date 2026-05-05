package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.ProviderId

data class EpisodeCoordinate(
    val provider: ProviderId,
    val seriesId: String,
    val season: Int,
    val episode: Int,
    val absoluteNumber: Int? = null,
) {
    val identityKey: String = "${provider.name.lowercase()}:$seriesId:s${season}e$episode"
}
