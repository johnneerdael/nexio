package com.nexio.tv.ui.screens.home

import com.nexio.tv.R
import com.nexio.tv.data.repository.TvEpisodeOrderProvider
import com.nexio.tv.data.repository.normalizeTmdbTvEpisodeOrderKey

data class HomeTvEpisodeOrderMenuAction(
    val provider: TvEpisodeOrderProvider,
    val tmdbTvOrderKey: String,
    val labelRes: Int
)

internal fun resolveHomeTvEpisodeOrderMenuAction(
    provider: TvEpisodeOrderProvider,
    tmdbTvOrderKey: String?
): HomeTvEpisodeOrderMenuAction? {
    val normalizedTmdbKey = normalizeTmdbTvEpisodeOrderKey(tmdbTvOrderKey) ?: return null
    return HomeTvEpisodeOrderMenuAction(
        provider = provider,
        tmdbTvOrderKey = normalizedTmdbKey,
        labelRes = if (provider == TvEpisodeOrderProvider.TVDB_DEFAULT) {
            R.string.detail_use_tmdb_season_numbering
        } else {
            R.string.detail_use_tvdb_season_numbering
        }
    )
}
