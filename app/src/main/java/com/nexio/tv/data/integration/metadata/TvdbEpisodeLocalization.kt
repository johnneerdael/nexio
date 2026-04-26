package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.data.remote.api.TvdbEpisodeRecord
import com.nexio.tv.data.remote.api.TvdbTranslationRecord

internal object TvdbEpisodeLocalization {
    fun mergeEnglishBase(
        policy: LocalizationPolicy = LocalizationPolicy.tvdb(requestedLanguage = null),
        englishEpisodes: List<TvdbEpisodeRecord>,
        localizedSeasonEpisodes: List<TvdbEpisodeRecord>,
        perEpisodeTranslations: Map<Int, TvdbTranslationRecord>
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> {
        return mergeEnglishBaseBundle(
            policy = policy,
            englishEpisodes = englishEpisodes,
            localizedSeasonEpisodes = localizedSeasonEpisodes,
            perEpisodeTranslations = perEpisodeTranslations
        ).episodes.mapValues { it.value.metadata }
    }

    fun mergeEnglishBaseBundle(
        policy: LocalizationPolicy = LocalizationPolicy.tvdb(requestedLanguage = null),
        englishEpisodes: List<TvdbEpisodeRecord>,
        localizedSeasonEpisodes: List<TvdbEpisodeRecord>,
        perEpisodeTranslations: Map<Int, TvdbTranslationRecord>
    ): LocalizedEpisodeBundle {
        check(!policy.allowProviderFallbackForMissingLocalizedFields) {
            "TVDB localization fallback must stay within TVDB for missing localized fields"
        }
        check(policy.provider == MetadataPrimaryProvider.TVDB) {
            "TvdbEpisodeLocalization received non-TVDB policy ${policy.provider}"
        }
        val localizedById = localizedSeasonEpisodes
            .mapNotNull { episode -> episode.id?.let { it to episode } }
            .toMap()
        val missingFallbackIds = idsMissingLocalizedFields(
            policy = policy,
            englishEpisodes = englishEpisodes,
            localizedSeasonEpisodes = localizedSeasonEpisodes
        )
        val episodes = englishEpisodes
            .mapNotNull { english ->
                val id = english.id ?: return@mapNotNull null
                val season = english.seasonNumber ?: return@mapNotNull null
                val number = english.number ?: return@mapNotNull null
                val localized = localizedById[id]
                val perEpisode = perEpisodeTranslations[id]
                val title = LocalizationResolver.selectField(
                    field = ResolvedField.TITLE,
                    policy = policy,
                    candidates = episodeTitleCandidates(policy, localized, perEpisode, english)
                )
                val overview = LocalizationResolver.selectField(
                    field = ResolvedField.OVERVIEW,
                    policy = policy,
                    candidates = episodeOverviewCandidates(policy, localized, perEpisode, english)
                )
                val metadata = english.toMetadata(
                    title = title?.value,
                    overview = overview?.value
                )
                (season to number) to LocalizedEpisodeMetadata(
                    metadata = metadata,
                    fieldSources = buildMap {
                        title?.let { put("title", it.toEpisodeFieldSource()) }
                        overview?.let { put("overview", it.toEpisodeFieldSource()) }
                    }
                )
            }
            .toMap()
        return LocalizedEpisodeBundle(
            episodes = episodes,
            policy = policy,
            perEpisodeTranslationFallbacksAttempted = missingFallbackIds.size,
            maxPerEpisodeTranslationFallbacksAllowed = policy.maxPerEpisodeTranslationFallbacksPerRequest
        )
    }

    fun idsMissingLocalizedOverview(
        englishEpisodes: List<TvdbEpisodeRecord>,
        localizedSeasonEpisodes: List<TvdbEpisodeRecord>
    ): List<Int> = idsMissingLocalizedFields(
        policy = LocalizationPolicy.tvdb(requestedLanguage = null),
        englishEpisodes = englishEpisodes,
        localizedSeasonEpisodes = localizedSeasonEpisodes
    )

    fun idsMissingLocalizedFields(
        policy: LocalizationPolicy,
        englishEpisodes: List<TvdbEpisodeRecord>,
        localizedSeasonEpisodes: List<TvdbEpisodeRecord>
    ): List<Int> {
        val localizedById = localizedSeasonEpisodes
            .mapNotNull { episode -> episode.id?.let { it to episode } }
            .toMap()
        return englishEpisodes.mapNotNull { english ->
            val id = english.id ?: return@mapNotNull null
            val localized = localizedById[id]
            val localizedTitle = localized?.name.cleanLocalizedValue()
            val localizedOverview = localized?.overview.cleanLocalizedValue()
            if (localizedTitle == null || localizedOverview == null) id else null
        }
            .take(policy.maxPerEpisodeTranslationFallbacksPerRequest)
    }

    private fun TvdbEpisodeRecord.toMetadata(
        title: String?,
        overview: String?
    ): TvEpisodeMetadata {
        return TvEpisodeMetadata(
            providerEpisodeId = id?.let { "tvdb:$it" },
            seasonNumber = seasonNumber,
            episodeNumber = number,
            title = title ?: name.cleanLocalizedValue(),
            overview = overview ?: this.overview.cleanLocalizedValue(),
            thumbnail = image.cleanLocalizedValue(),
            airDate = aired.cleanLocalizedValue(),
            runtimeMinutes = runtime,
            absoluteNumber = absoluteNumber,
            airsAfterSeason = airsAfterSeason,
            airsBeforeSeason = airsBeforeSeason,
            airsBeforeEpisode = airsBeforeEpisode,
            finaleType = finaleType,
            linkedMovieTvdbId = linkedMovie
        )
    }

    private fun episodeTitleCandidates(
        policy: LocalizationPolicy,
        localized: TvdbEpisodeRecord?,
        perEpisode: TvdbTranslationRecord?,
        english: TvdbEpisodeRecord
    ): List<LocalizedFieldCandidate> =
        episodeCandidates(
            field = ResolvedField.TITLE,
            policy = policy,
            localizedValue = localized?.name,
            perEpisodeValue = perEpisode?.name,
            englishValue = english.name
        )

    private fun episodeOverviewCandidates(
        policy: LocalizationPolicy,
        localized: TvdbEpisodeRecord?,
        perEpisode: TvdbTranslationRecord?,
        english: TvdbEpisodeRecord
    ): List<LocalizedFieldCandidate> =
        episodeCandidates(
            field = ResolvedField.OVERVIEW,
            policy = policy,
            localizedValue = localized?.overview,
            perEpisodeValue = perEpisode?.overview,
            englishValue = english.overview
        )

    private fun episodeCandidates(
        field: ResolvedField,
        policy: LocalizationPolicy,
        localizedValue: String?,
        perEpisodeValue: String?,
        englishValue: String?
    ): List<LocalizedFieldCandidate> =
        listOf(
            LocalizedFieldCandidate(
                field = field,
                value = perEpisodeValue,
                language = policy.requestedLanguage,
                provider = MetadataPrimaryProvider.TVDB,
                sourceShape = TvdbApiShapes.EPISODE_TRANSLATION,
                fallbackRole = FallbackRole.LOCALIZED
            ),
            LocalizedFieldCandidate(
                field = field,
                value = localizedValue,
                language = policy.requestedLanguage,
                provider = MetadataPrimaryProvider.TVDB,
                sourceShape = TvdbApiShapes.SERIES_EPISODES_LANGUAGE,
                fallbackRole = FallbackRole.LOCALIZED
            ),
            LocalizedFieldCandidate(
                field = field,
                value = englishValue,
                language = policy.fallbackLanguage,
                provider = MetadataPrimaryProvider.TVDB,
                sourceShape = TvdbApiShapes.SERIES_EPISODES_LANGUAGE,
                fallbackRole = FallbackRole.LANGUAGE_FALLBACK
            )
        )

    private fun SelectedLocalizedField.toEpisodeFieldSource(): LocalizedEpisodeFieldSource =
        LocalizedEpisodeFieldSource(
            selectedLanguage = language,
            sourceShape = sourceShape,
            fallbackRole = fallbackRole,
            rejectedCandidates = rejectedCandidates
        )
}
