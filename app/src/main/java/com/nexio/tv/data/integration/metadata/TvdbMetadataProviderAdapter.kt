package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import javax.inject.Inject

class TvdbMetadataProviderAdapter @Inject constructor(
    private val integrationProvider: TvdbIntegrationProvider
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TVDB

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in tvdbShapes

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val tvdbId = route.targetIds[MetadataPrimaryProvider.TVDB]?.toIntOrNull()
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val language = route.language.orEmpty()
        val candidate = when (step.apiShapeId) {
            TvdbApiShapes.SERIES_EXTENDED ->
                integrationProvider.fetchSeriesExtended(tvdbId).toMetadataCandidate(this.provider)
            TvdbApiShapes.SERIES_TRANSLATION ->
                integrationProvider.fetchSeriesTranslation(tvdbId, language).toMetadataCandidate(this.provider)
            TvdbApiShapes.SERIES_EPISODES_SEASON_TYPE -> {
                integrationProvider.fetchSeriesEpisodes(tvdbId, seasonType = "default", season = route.seasonNumber)
                emptyCandidate(this.provider)
            }
            TvdbApiShapes.SERIES_EPISODES_LANGUAGE -> {
                integrationProvider.fetchSeriesEpisodesTranslated(tvdbId, seasonType = "default", language = language, season = route.seasonNumber)
                emptyCandidate(this.provider)
            }
            TvdbApiShapes.EPISODE_TRANSLATION -> emptyCandidate(this.provider)
            else -> emptyCandidate(this.provider)
        }
        return ProviderStepResult(step = step, candidate = candidate)
    }

    private companion object {
        val tvdbShapes = setOf(
            TvdbApiShapes.SERIES_EXTENDED,
            TvdbApiShapes.SERIES_TRANSLATION,
            TvdbApiShapes.SERIES_EPISODES_SEASON_TYPE,
            TvdbApiShapes.SERIES_EPISODES_LANGUAGE,
            TvdbApiShapes.EPISODE_TRANSLATION
        )
    }
}
