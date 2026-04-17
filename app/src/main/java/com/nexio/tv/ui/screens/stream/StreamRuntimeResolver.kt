package com.nexio.tv.ui.screens.stream

import com.nexio.tv.core.metadata.episodeRuntimeOrSeriesAverageMinutes
import com.nexio.tv.core.metadata.parseRuntimeMinutes
import com.nexio.tv.domain.model.Meta

internal fun resolveStreamRuntimeMinutes(
    meta: Meta,
    season: Int?,
    episode: Int?
): Int? {
    if (season != null && episode != null) {
        val episodeRuntime = meta.videos
            .firstOrNull { video -> video.season == season && video.episode == episode }
            ?.runtime
        return episodeRuntimeOrSeriesAverageMinutes(
            episodeRuntimeMinutes = episodeRuntime,
            seriesRuntime = meta.runtime
        )
    }

    return parseRuntimeMinutes(meta.runtime)
}
