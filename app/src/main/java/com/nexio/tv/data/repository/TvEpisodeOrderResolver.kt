package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

interface TvEpisodeOrderResolver {
    suspend fun resolve(
        tmdbTvId: String?,
        providerIds: ProviderIds
    ): TvEpisodeOrderResolution
}

@Singleton
class DefaultTvEpisodeOrderResolver @Inject constructor(
    private val overrideRepository: TvEpisodeOrderOverrideRepository
) : TvEpisodeOrderResolver {

    override suspend fun resolve(
        tmdbTvId: String?,
        providerIds: ProviderIds
    ): TvEpisodeOrderResolution {
        val key = normalizeTmdbTvEpisodeOrderKey(tmdbTvId)
            ?: return TvEpisodeOrderResolution(
                provider = TvEpisodeOrderProvider.TMDB_DEFAULT,
                tmdbTvId = "",
                reason = "missing tmdb tv id"
            )

        val override = overrideRepository.getOrder(key)
        if (override != TvEpisodeOrderProvider.TVDB_DEFAULT) {
            return TvEpisodeOrderResolution(
                provider = TvEpisodeOrderProvider.TMDB_DEFAULT,
                tmdbTvId = key,
                reason = "tmdb default"
            )
        }

        val tvdbSeriesId = providerIds.tvdb?.trim()?.takeIf { it.isNotEmpty() }
        if (tvdbSeriesId == null) {
            return TvEpisodeOrderResolution(
                provider = TvEpisodeOrderProvider.TMDB_DEFAULT,
                tmdbTvId = key,
                reason = "tvdb override missing tvdb sidecar"
            )
        }

        return TvEpisodeOrderResolution(
            provider = TvEpisodeOrderProvider.TVDB_DEFAULT,
            tmdbTvId = key,
            tvdbSeriesId = tvdbSeriesId,
            reason = "tvdb override"
        )
    }
}
