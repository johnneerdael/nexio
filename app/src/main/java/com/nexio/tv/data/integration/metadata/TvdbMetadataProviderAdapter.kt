package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataLocalizationPayloadTrace
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
        val localizationPayloads = mutableListOf<MetadataLocalizationPayloadTrace>()
        val candidate = when (step.apiShapeId) {
            TvdbApiShapes.SERIES_EXTENDED -> {
                val extended = integrationProvider.fetchSeriesExtendedCached(
                    tvdbId = tvdbId,
                    localizationPolicyVersion = policy.policyVersion
                )
                val english = integrationProvider.fetchSeriesTranslation(
                    tvdbId = tvdbId,
                    language = policy.fallbackLanguage.providerCode,
                    localizationPolicyVersion = policy.policyVersion
                )
                localizationPayloads += MetadataLocalizationPayloadTrace(
                    provider = this.provider,
                    apiShapeId = TvdbApiShapes.SERIES_TRANSLATION,
                    language = policy.fallbackLanguage.providerCode,
                    cacheKey = tvdbSeriesTranslationCacheKey(
                        tvdbId = tvdbId,
                        language = policy.fallbackLanguage.providerCode,
                        policyVersion = policy.policyVersion
                    ),
                    cacheDecision = null,
                    executedNetwork = false,
                    policyVersion = policy.policyVersion
                )
                val requested = if (policy.requestedIsFallback) {
                    null
                } else {
                    integrationProvider.fetchSeriesTranslation(
                        tvdbId = tvdbId,
                        language = policy.requestedLanguage.providerCode,
                        localizationPolicyVersion = policy.policyVersion
                    ).also {
                        localizationPayloads += MetadataLocalizationPayloadTrace(
                            provider = this.provider,
                            apiShapeId = TvdbApiShapes.SERIES_TRANSLATION,
                            language = policy.requestedLanguage.providerCode,
                            cacheKey = tvdbSeriesTranslationCacheKey(
                                tvdbId = tvdbId,
                                language = policy.requestedLanguage.providerCode,
                                policyVersion = policy.policyVersion
                            ),
                            cacheDecision = null,
                            executedNetwork = false,
                            policyVersion = policy.policyVersion
                        )
                    }
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
        return ProviderStepResult(
            step = step,
            candidate = candidate,
            episodeMetadata = episodeMetadata,
            localizationPayloads = localizationPayloads
        )
    }

    private companion object {
        val tvdbShapes = setOf(
            TvdbApiShapes.SERIES_EXTENDED,
            TvdbApiShapes.SERIES_EPISODES_LANGUAGE
        )
    }
}
