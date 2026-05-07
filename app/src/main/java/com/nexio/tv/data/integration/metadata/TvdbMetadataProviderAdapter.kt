package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataLocalizationFallbackRole
import com.nexio.tv.core.metadata.router.MetadataLocalizationPayloadTrace
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.tvdb.TvdbAdvancedMetadataMapper
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import javax.inject.Inject

class TvdbMetadataProviderAdapter @Inject constructor(
    private val integrationProvider: TvdbIntegrationProvider,
    private val traceEvents: TraceMetadataEvents,
    private val artworkCandidateMapper: TvdbArtworkCandidateMapper,
    private val artworkDecisionResolver: MetadataArtworkDecisionResolver,
    private val advancedMetadataMapper: TvdbAdvancedMetadataMapper = TvdbAdvancedMetadataMapper()
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TVDB

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in tvdbShapes

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val tvdbId = MetadataProviderTargetIds.tvdbInt(route.targetIds[MetadataPrimaryProvider.TVDB])
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val language = route.language.orEmpty()
        val policy = LocalizationPolicy.tvdb(language)
        // F-E-02: emit localization_plan immediately after policy construction.
        // F2-E-06: Skip the initial emission for SERIES_EPISODES_LANGUAGE — that branch emits its
        // own post-bundle plan (with the real perEpisodeFallbacksAttempted count) after the bundle
        // is resolved. Emitting here first would always show perEpisodeFallbacksAttempted = 0 and
        // produce a duplicate event in traces.
        if (step.apiShapeId != TvdbApiShapes.SERIES_EPISODES_LANGUAGE) {
            traceEvents.emitLocalizationPlan(
                contentId = "tvdb:$tvdbId",
                provider = "TVDB",
                policyVersion = policy.policyVersion,
                requestedLanguage = policy.requestedLanguage.providerCode,
                fallbackLanguage = policy.fallbackLanguage.providerCode,
                requestedIsFallback = policy.requestedIsFallback,
                allowProviderFallbackForMissingLocalizedFields = policy.allowProviderFallbackForMissingLocalizedFields,
                perEpisodeFallbacksAttempted = 0,
                perEpisodeFallbacksAllowed = policy.maxPerEpisodeTranslationFallbacksPerRequest,
                localeCollapsedToFallback = policy.localeCollapsedToFallback  // F2-E-01
            )
        }
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
                val artworkFields = artworkDecisionResolver.resolveFields(
                    artworkCandidateMapper.mapSeriesArtwork(
                        seriesId = tvdbId,
                        artworks = extended?.artworks.orEmpty(),
                        requestedLanguage = policy.requestedLanguage.providerCode,
                        posterFallbackImage = extended?.image
                    )
                )
                buildTvdbCoreLocalizedCandidate(
                    provider = this.provider,
                    policy = policy,
                    extended = extended,
                    englishTranslation = english.value,
                    requestedTranslation = requested,
                    artworkFields = artworkFields
                ).withAdvancedFields(extended)
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
                    perEpisodeFallbacksAllowed = bundle.maxPerEpisodeTranslationFallbacksAllowed,
                    localeCollapsedToFallback = bundle.policy.localeCollapsedToFallback  // F2-E-01
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
                // F-E-03: emit metadata.field_selected for each per-episode field winner.
                bundle.episodes.forEach { (seasonEp, localizedEpisode) ->
                    val (season, episode) = seasonEp
                    localizedEpisode.fieldSources.forEach { (fieldName, source) ->
                        val resolvedField = when (fieldName) {
                            "title" -> ResolvedField.TITLE
                            "overview" -> ResolvedField.OVERVIEW
                            else -> return@forEach
                        }
                        traceEvents.emitFieldSelected(
                            contentId = "tvdb:$tvdbId:s${season}e${episode}",
                            field = resolvedField.name,
                            selectedProvider = this.provider.name,
                            sourceRole = source.fallbackRole.name,
                            valuePreview = "<episode-$fieldName>",
                            ownershipRule = "localization-resolver: ${source.fallbackRole.name}",
                            rejectedCandidates = source.rejectedCandidates.map { rejection ->
                                mapOf(
                                    "provider" to rejection.provider.name,
                                    "language" to rejection.language.providerCode,
                                    "fallbackRole" to rejection.fallbackRole.name,
                                    "reason" to rejection.reason
                                )
                            }
                        )
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

    private fun MetadataCandidate.withAdvancedFields(
        extended: com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord?
    ): MetadataCandidate {
        if (extended == null) return this
        val advanced = advancedMetadataMapper.mapAdvancedMetadata(
            series = extended,
            preferredCountryCodes = listOf("us", "usa")
        )
        val fields = fields.toMutableMap()
        if (advanced.castMembers.isNotEmpty()) {
            fields[ResolvedField.CAST] = FieldValue(advanced.castMembers, FieldOwner.PRIMARY)
        }
        val organizations = advanced.productionCompanies + advanced.networks
        if (organizations.isNotEmpty()) {
            fields[ResolvedField.ORGANIZATION_LIST] = FieldValue(organizations, FieldOwner.PRIMARY)
        }
        if (advanced.genres.isNotEmpty()) {
            fields[ResolvedField.GENRES] = FieldValue(advanced.genres, FieldOwner.PRIMARY)
        }
        advanced.ageRating?.let { fields[ResolvedField.AGE_RATING] = FieldValue(it, FieldOwner.PRIMARY) }
        return copy(fields = fields)
    }

    private companion object {
        val tvdbShapes = setOf(
            TvdbApiShapes.SERIES_EXTENDED,
            TvdbApiShapes.SERIES_EPISODES_LANGUAGE
        )
    }
}
