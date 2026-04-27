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
        val tvdbId = MetadataProviderTargetIds.tvdbInt(route.targetIds[MetadataPrimaryProvider.TVDB])
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val language = route.language.orEmpty()
        val policy = LocalizationPolicy.tvdb(language)
        val episodeMetadata = mutableMapOf<Pair<Int, Int>, com.nexio.tv.core.tvdb.TvEpisodeMetadata>()
        val candidate = when (step.apiShapeId) {
            TvdbApiShapes.SERIES_EXTENDED -> {
                val extended = integrationProvider.fetchSeriesExtended(tvdbId)
                val english = integrationProvider.fetchSeriesTranslation(
                    tvdbId = tvdbId,
                    language = policy.fallbackLanguage.providerCode,
                    localizationPolicyVersion = policy.policyVersion
                )
                val requested = if (policy.requestedIsFallback) {
                    null
                } else {
                    integrationProvider.fetchSeriesTranslation(
                        tvdbId = tvdbId,
                        language = policy.requestedLanguage.providerCode,
                        localizationPolicyVersion = policy.policyVersion
                    )
                }
                buildTvdbCoreLocalizedCandidate(
                    provider = this.provider,
                    policy = policy,
                    extended = extended,
                    englishTranslation = english,
                    requestedTranslation = requested
                )
            }
            TvdbApiShapes.SERIES_EPISODES_LANGUAGE -> {
                episodeMetadata += integrationProvider.fetchLocalizedSeasonEpisodeBundle(
                    tvdbId = tvdbId,
                    seasonType = "default",
                    requestedLanguage = language,
                    season = route.seasonNumber
                ).episodes.mapValues { it.value.metadata }
                emptyCandidate(this.provider)
            }
            else -> emptyCandidate(this.provider)
        }
        return ProviderStepResult(step = step, candidate = candidate, episodeMetadata = episodeMetadata)
    }

    private companion object {
        val tvdbShapes = setOf(
            TvdbApiShapes.SERIES_EXTENDED,
            TvdbApiShapes.SERIES_EPISODES_LANGUAGE
        )
    }
}
