package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataLocalizationFallbackRole
import com.nexio.tv.core.metadata.router.MetadataLocalizationPayloadTrace
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import javax.inject.Inject

class TvdbMetadataProviderAdapter @Inject constructor(
    private val integrationProvider: TvdbIntegrationProvider,
    private val traceEvents: TraceMetadataEvents
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TVDB

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in tvdbShapes

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val tvdbId = MetadataProviderTargetIds.tvdbInt(route.targetIds[MetadataPrimaryProvider.TVDB])
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val language = route.language.orEmpty()
        val policy = LocalizationPolicy.tvdb(language)
        // F-E-02: emit localization_plan immediately after policy construction.
        traceEvents.emitLocalizationPlan(
            contentId = "tvdb:$tvdbId",
            provider = "TVDB",
            policyVersion = policy.policyVersion,
            requestedLanguage = policy.requestedLanguage.providerCode,
            fallbackLanguage = policy.fallbackLanguage.providerCode,
            requestedIsFallback = policy.requestedIsFallback,
            allowProviderFallbackForMissingLocalizedFields = policy.allowProviderFallbackForMissingLocalizedFields,
            perEpisodeFallbacksAttempted = 0,
            perEpisodeFallbacksAllowed = policy.maxPerEpisodeTranslationFallbacksPerRequest
        )
        val episodeMetadata = mutableMapOf<Pair<Int, Int>, com.nexio.tv.core.tvdb.TvEpisodeMetadata>()
        val localizationPayloads = mutableListOf<MetadataLocalizationPayloadTrace>()
        val candidate = when (step.apiShapeId) {
            TvdbApiShapes.SERIES_EXTENDED -> {
                val extended = integrationProvider.fetchSeriesExtendedCached(
                    tvdbId = tvdbId,
                    localizationPolicyVersion = policy.policyVersion
                )
                val english = integrationProvider.fetchSeriesTranslationWithTrace(
                    tvdbId = tvdbId,
                    language = policy.fallbackLanguage.providerCode,
                    fallbackRole = MetadataLocalizationFallbackRole.LANGUAGE_FALLBACK,
                    localizationPolicyVersion = policy.policyVersion
                )
                localizationPayloads += english.trace
                val requested = if (policy.requestedIsFallback) {
                    null
                } else {
                    integrationProvider.fetchSeriesTranslationWithTrace(
                        tvdbId = tvdbId,
                        language = policy.requestedLanguage.providerCode,
                        fallbackRole = MetadataLocalizationFallbackRole.LOCALIZED,
                        localizationPolicyVersion = policy.policyVersion
                    ).also {
                        localizationPayloads += it.trace
                    }.value
                }
                buildTvdbCoreLocalizedCandidate(
                    provider = this.provider,
                    policy = policy,
                    extended = extended,
                    englishTranslation = english.value,
                    requestedTranslation = requested
                )
            }
            TvdbApiShapes.SERIES_EPISODES_LANGUAGE -> {
                val bundle = integrationProvider.fetchLocalizedSeasonEpisodeBundle(
                    tvdbId = tvdbId,
                    seasonType = "default",
                    requestedLanguage = language,
                    season = route.seasonNumber
                )
                // F-E-01: surface the per-episode fallback counter from the bundle
                traceEvents.emitLocalizationPlan(
                    contentId = "tvdb:$tvdbId",
                    provider = "TVDB",
                    policyVersion = bundle.policy.policyVersion,
                    requestedLanguage = bundle.policy.requestedLanguage.providerCode,
                    fallbackLanguage = bundle.policy.fallbackLanguage.providerCode,
                    requestedIsFallback = bundle.policy.requestedIsFallback,
                    allowProviderFallbackForMissingLocalizedFields = bundle.policy.allowProviderFallbackForMissingLocalizedFields,
                    perEpisodeFallbacksAttempted = bundle.perEpisodeTranslationFallbacksAttempted,
                    perEpisodeFallbacksAllowed = bundle.maxPerEpisodeTranslationFallbacksAllowed
                )
                episodeMetadata += bundle.episodes.mapValues { it.value.metadata }
                localizationPayloads += bundle.localizationPayloads
                val episodeLocalization = bundle.episodes.mapValues { (_, localizedEpisode) ->
                    localizedEpisode.fieldSources.mapKeys { (fieldName, _) ->
                        when (fieldName) {
                            "title" -> ResolvedField.TITLE
                            "overview" -> ResolvedField.OVERVIEW
                            else -> ResolvedField.OVERVIEW
                        }
                    }.mapValues { (field, source) ->
                        source.toMetadataTrace(field = field, provider = this.provider)
                    }
                }
                return ProviderStepResult(
                    step = step,
                    candidate = emptyCandidate(this.provider),
                    episodeMetadata = episodeMetadata,
                    episodeLocalization = episodeLocalization,
                    localizationPayloads = localizationPayloads
                )
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
