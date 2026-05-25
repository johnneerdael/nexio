package com.nexio.tv.ui.screens.search

import com.nexio.tv.R
import com.nexio.tv.data.repository.TvEpisodeOrderProvider
import com.nexio.tv.data.repository.normalizeTmdbTvEpisodeOrderKey

data class SearchTvEpisodeOrderMenuAction(
    val provider: TvEpisodeOrderProvider,
    val tmdbTvOrderKey: String,
    val labelRes: Int
)

internal fun resolveSearchTvEpisodeOrderMenuAction(
    provider: TvEpisodeOrderProvider,
    tmdbTvOrderKey: String?
): SearchTvEpisodeOrderMenuAction? {
    val normalizedTmdbKey = normalizeTmdbTvEpisodeOrderKey(tmdbTvOrderKey) ?: return null
    return SearchTvEpisodeOrderMenuAction(
        provider = provider,
        tmdbTvOrderKey = normalizedTmdbKey,
        labelRes = if (provider == TvEpisodeOrderProvider.TVDB_DEFAULT) {
            R.string.detail_use_tmdb_season_numbering
        } else {
            R.string.detail_use_tvdb_season_numbering
        }
    )
}
