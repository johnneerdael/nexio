package com.nexio.tv.data.integration.metadata

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
        check(!policy.allowProviderFallbackForMissingLocalizedFields) {
            "TVDB localization fallback must stay within TVDB for missing localized fields"
        }
        val localizedById = localizedSeasonEpisodes
            .mapNotNull { episode -> episode.id?.let { it to episode } }
            .toMap()
        return englishEpisodes
            .mapNotNull { english ->
                val id = english.id ?: return@mapNotNull null
                val season = english.seasonNumber ?: return@mapNotNull null
                val number = english.number ?: return@mapNotNull null
                val localized = localizedById[id]
                val perEpisode = perEpisodeTranslations[id]
                (season to number) to english.toMetadata(
                    policy = policy,
                    localized = localized,
                    perEpisode = perEpisode
                )
            }
            .toMap()
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
            val localizedTitle = localized?.name.clean()
            val localizedOverview = localized?.overview.clean()
            if (localizedTitle == null || localizedOverview == null) id else null
        }
            .take(policy.maxPerEpisodeTranslationFallbacksPerRequest)
    }

    private fun TvdbEpisodeRecord.toMetadata(
        policy: LocalizationPolicy,
        localized: TvdbEpisodeRecord?,
        perEpisode: TvdbTranslationRecord?
    ): TvEpisodeMetadata {
        check(policy.provider == com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TVDB) {
            "TvdbEpisodeLocalization received non-TVDB policy ${policy.provider}"
        }
        return TvEpisodeMetadata(
            providerEpisodeId = id?.let { "tvdb:$it" },
            seasonNumber = seasonNumber,
            episodeNumber = number,
            title = perEpisode?.name.clean() ?: localized?.name.clean() ?: name.clean(),
            overview = perEpisode?.overview.clean() ?: localized?.overview.clean() ?: overview.clean(),
            thumbnail = image.clean(),
            airDate = aired.clean(),
            runtimeMinutes = runtime,
            absoluteNumber = absoluteNumber,
            airsAfterSeason = airsAfterSeason,
            airsBeforeSeason = airsBeforeSeason,
            airsBeforeEpisode = airsBeforeEpisode,
            finaleType = finaleType,
            linkedMovieTvdbId = linkedMovie
        )
    }

    private fun String?.clean(): String? =
        this
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { value ->
                missingPlaceholders.any { placeholder ->
                    value.equals(placeholder, ignoreCase = true)
                }
            }

    private val missingPlaceholders = setOf(
        "N/A",
        "No description available.",
        "No overview available.",
        "No synopsis available."
    )
}
