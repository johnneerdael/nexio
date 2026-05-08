package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataLocalizationFallbackRole
import com.nexio.tv.core.metadata.router.MetadataLocalizationFieldTrace
import com.nexio.tv.core.metadata.router.MetadataLocalizationRejectedCandidate
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.data.remote.api.KitsuImage
import com.nexio.tv.data.remote.api.TvdbRemoteId
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.data.remote.api.TvdbTranslationRecord

internal fun TmdbEnrichment?.toMetadataCandidate(provider: MetadataPrimaryProvider): MetadataCandidate =
    MetadataCandidate(
        provider = provider,
        fields = buildMap {
            this@toMetadataCandidate ?: return@buildMap
            localizedTitle?.let { put(ResolvedField.TITLE, FieldValue(it, FieldOwner.PRIMARY)) }
            description?.let { put(ResolvedField.OVERVIEW, FieldValue(it, FieldOwner.PRIMARY)) }
            poster?.let { put(ResolvedField.POSTER, FieldValue(it, FieldOwner.PRIMARY)) }
            backdrop?.let { put(ResolvedField.BACKDROP, FieldValue(it, FieldOwner.PRIMARY)) }
            logo?.let { put(ResolvedField.LOGO, FieldValue(it, FieldOwner.PRIMARY)) }
            rating?.let { put(ResolvedField.RATING, FieldValue(it, FieldOwner.PRIMARY)) }
            runtimeMinutes?.let { put(ResolvedField.RUNTIME, FieldValue(it, FieldOwner.PRIMARY)) }
            val remoteIds = buildMap<String, Set<String>> {
                imdbId?.takeIf { it.isNotBlank() }?.let { put("imdb", setOf(it)) }
                tvdbId?.let { put("tvdb", setOf(it.toString())) }
            }
            if (remoteIds.isNotEmpty()) {
                put(ResolvedField.REMOTE_IDS, FieldValue(remoteIds, FieldOwner.PRIMARY))
            }
        }
    )

internal fun TvMetadataEnrichment?.toMetadataCandidate(provider: MetadataPrimaryProvider): MetadataCandidate =
    MetadataCandidate(
        provider = provider,
        fields = buildMap {
            this@toMetadataCandidate ?: return@buildMap
            seriesTvdbId?.let { put(ResolvedField.CANONICAL_ID, FieldValue("tvdb:$it", FieldOwner.PRIMARY)) }
            localizedTitle?.let { put(ResolvedField.TITLE, FieldValue(it, FieldOwner.PRIMARY)) }
            description?.let { put(ResolvedField.OVERVIEW, FieldValue(it, FieldOwner.PRIMARY)) }
            poster?.let { put(ResolvedField.POSTER, FieldValue(it, FieldOwner.PRIMARY)) }
            backdrop?.let { put(ResolvedField.BACKDROP, FieldValue(it, FieldOwner.PRIMARY)) }
            logo?.let { put(ResolvedField.LOGO, FieldValue(it, FieldOwner.PRIMARY)) }
            rating?.let { put(ResolvedField.RATING, FieldValue(it, FieldOwner.PRIMARY)) }
            runtimeMinutes?.let { put(ResolvedField.RUNTIME, FieldValue(it, FieldOwner.PRIMARY)) }
            averageRuntimeMinutes?.let { putIfAbsent(ResolvedField.RUNTIME, FieldValue(it, FieldOwner.PRIMARY)) }
            if (remoteIds.isNotEmpty()) {
                put(ResolvedField.REMOTE_IDS, FieldValue(remoteIds, FieldOwner.PRIMARY))
            }
        }
    )

internal fun TvdbSeriesExtendedRecord?.toMetadataCandidate(provider: MetadataPrimaryProvider): MetadataCandidate =
    MetadataCandidate(
        provider = provider,
        fields = buildMap {
            this@toMetadataCandidate ?: return@buildMap
            id?.let { put(ResolvedField.CANONICAL_ID, FieldValue("tvdb:$it", FieldOwner.PRIMARY)) }
            name?.let { put(ResolvedField.TITLE, FieldValue(it, FieldOwner.PRIMARY)) }
            overview?.let { put(ResolvedField.OVERVIEW, FieldValue(it, FieldOwner.PRIMARY)) }
            image?.let { put(ResolvedField.POSTER, FieldValue(it, FieldOwner.PRIMARY)) }
            score?.let { put(ResolvedField.RATING, FieldValue(it, FieldOwner.PRIMARY)) }
            averageRuntime?.let { put(ResolvedField.RUNTIME, FieldValue(it, FieldOwner.PRIMARY)) }
            val remoteIds = remoteIds.toRemoteIdsMap(id)
            if (remoteIds.isNotEmpty()) {
                put(ResolvedField.REMOTE_IDS, FieldValue(remoteIds, FieldOwner.PRIMARY))
            }
        }
    )

internal fun TvdbTranslationRecord?.toMetadataCandidate(provider: MetadataPrimaryProvider): MetadataCandidate =
    MetadataCandidate(
        provider = provider,
        fields = buildMap {
            this@toMetadataCandidate ?: return@buildMap
            name?.let { put(ResolvedField.TITLE, FieldValue(it, FieldOwner.PRIMARY)) }
            overview?.let { put(ResolvedField.OVERVIEW, FieldValue(it, FieldOwner.PRIMARY)) }
        }
    )

internal fun tvdbSeriesTranslationCacheKey(
    tvdbId: Int,
    language: String,
    policyVersion: Int
): String = "tvdb:series:$tvdbId:translation:$language:policy:$policyVersion"

internal fun SelectedLocalizedField.toMetadataTrace(): MetadataLocalizationFieldTrace =
    MetadataLocalizationFieldTrace(
        field = field,
        selectedProvider = provider,
        selectedLanguage = language.providerCode,
        fallbackRole = fallbackRole.toMetadataRole(),
        sourceApiShapeId = sourceShape,
        rejectedCandidates = rejectedCandidates.map { it.toMetadataRejection() }
    )

internal fun LocalizedEpisodeFieldSource.toMetadataTrace(
    field: ResolvedField,
    provider: MetadataPrimaryProvider
): MetadataLocalizationFieldTrace =
    MetadataLocalizationFieldTrace(
        field = field,
        selectedProvider = provider,
        selectedLanguage = selectedLanguage.providerCode,
        fallbackRole = fallbackRole.toMetadataRole(),
        sourceApiShapeId = sourceShape,
        rejectedCandidates = rejectedCandidates.map { it.toMetadataRejection() }
    )

private fun LocalizedFieldRejection.toMetadataRejection(): MetadataLocalizationRejectedCandidate =
    MetadataLocalizationRejectedCandidate(
        provider = provider,
        language = language.providerCode,
        fallbackRole = fallbackRole.toMetadataRole(),
        reason = reason
    )

internal fun FallbackRole.toMetadataRole(): MetadataLocalizationFallbackRole =
    when (this) {
        FallbackRole.LOCALIZED -> MetadataLocalizationFallbackRole.LOCALIZED
        FallbackRole.LANGUAGE_FALLBACK -> MetadataLocalizationFallbackRole.LANGUAGE_FALLBACK
        FallbackRole.CANONICAL -> MetadataLocalizationFallbackRole.CANONICAL
        FallbackRole.ADDON_FALLBACK -> MetadataLocalizationFallbackRole.ADDON_FALLBACK
        FallbackRole.PROVIDER_FALLBACK -> MetadataLocalizationFallbackRole.PROVIDER_FALLBACK
    }

internal fun buildTvdbCoreLocalizedCandidate(
    provider: MetadataPrimaryProvider,
    policy: LocalizationPolicy,
    extended: TvdbSeriesExtendedRecord?,
    englishTranslation: TvdbTranslationRecord?,
    requestedTranslation: TvdbTranslationRecord?,
    artworkFields: Map<ResolvedField, FieldValue> = emptyMap()
): MetadataCandidate {
    val title = LocalizationResolver.selectField(
        field = ResolvedField.TITLE,
        policy = policy,
        candidates = listOfNotNull(
            requestedTranslation?.toLocalizedCandidate(
                field = ResolvedField.TITLE,
                value = requestedTranslation.name,
                language = policy.requestedLanguage,
                provider = provider,
                fallbackRole = FallbackRole.LOCALIZED
            ),
            englishTranslation?.toLocalizedCandidate(
                field = ResolvedField.TITLE,
                value = englishTranslation.name,
                language = policy.fallbackLanguage,
                provider = provider,
                fallbackRole = FallbackRole.LANGUAGE_FALLBACK
            )
        )
    )
    val overview = LocalizationResolver.selectField(
        field = ResolvedField.OVERVIEW,
        policy = policy,
        candidates = listOfNotNull(
            requestedTranslation?.toLocalizedCandidate(
                field = ResolvedField.OVERVIEW,
                value = requestedTranslation.overview,
                language = policy.requestedLanguage,
                provider = provider,
                fallbackRole = FallbackRole.LOCALIZED
            ),
            englishTranslation?.toLocalizedCandidate(
                field = ResolvedField.OVERVIEW,
                value = englishTranslation.overview,
                language = policy.fallbackLanguage,
                provider = provider,
                fallbackRole = FallbackRole.LANGUAGE_FALLBACK
            )
        )
    )

    return MetadataCandidate(
        provider = provider,
        fields = buildMap {
            extended?.id?.let { put(ResolvedField.CANONICAL_ID, FieldValue("tvdb:$it", FieldOwner.PRIMARY)) }
            title?.value?.let { put(ResolvedField.TITLE, FieldValue(it, FieldOwner.PRIMARY)) }
            overview?.value?.let { put(ResolvedField.OVERVIEW, FieldValue(it, FieldOwner.PRIMARY)) }
            putAll(artworkFields)
            extended?.score?.let { put(ResolvedField.RATING, FieldValue(it, FieldOwner.PRIMARY)) }
            extended?.averageRuntime?.let { put(ResolvedField.RUNTIME, FieldValue(it, FieldOwner.PRIMARY)) }
            val remoteIds = extended?.remoteIds.toRemoteIdsMap(extended?.id)
            if (remoteIds.isNotEmpty()) {
                put(ResolvedField.REMOTE_IDS, FieldValue(remoteIds, FieldOwner.PRIMARY))
            }
        },
        localization = buildMap {
            title?.let { put(ResolvedField.TITLE, it.toMetadataTrace()) }
            overview?.let { put(ResolvedField.OVERVIEW, it.toMetadataTrace()) }
        }
    )
}

private fun List<TvdbRemoteId>?.toRemoteIdsMap(tvdbId: Int?): Map<String, Set<String>> {
    val grouped = mutableMapOf<String, MutableSet<String>>()
    tvdbId?.let { grouped.getOrPut("tvdb") { linkedSetOf() } += it.toString() }
    orEmpty().forEach { remoteId ->
        val source = remoteId.sourceName
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return@forEach
        val id = remoteId.id
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return@forEach
        grouped.getOrPut(source) { linkedSetOf() } += id
    }
    return grouped.mapValues { (_, ids) -> ids.toSet() }
}

internal fun buildTmdbLocalizedCandidate(
    provider: MetadataPrimaryProvider,
    policy: LocalizationPolicy,
    requested: TmdbEnrichment?,
    english: TmdbEnrichment?,
    sourceApiShapeId: String = TmdbApiShapes.MOVIE_CORE
): MetadataCandidate {
    val title = LocalizationResolver.selectField(
        field = ResolvedField.TITLE,
        policy = policy,
        candidates = listOf(
            LocalizedFieldCandidate(
                field = ResolvedField.TITLE,
                value = requested?.localizedTitle,
                language = policy.requestedLanguage,
                provider = provider,
                sourceShape = sourceApiShapeId,
                fallbackRole = FallbackRole.LOCALIZED
            ),
            LocalizedFieldCandidate(
                field = ResolvedField.TITLE,
                value = english?.localizedTitle,
                language = policy.fallbackLanguage,
                provider = provider,
                sourceShape = sourceApiShapeId,
                fallbackRole = FallbackRole.LANGUAGE_FALLBACK
            )
        )
    )
    val overview = LocalizationResolver.selectField(
        field = ResolvedField.OVERVIEW,
        policy = policy,
        candidates = listOf(
            LocalizedFieldCandidate(
                field = ResolvedField.OVERVIEW,
                value = requested?.description,
                language = policy.requestedLanguage,
                provider = provider,
                sourceShape = sourceApiShapeId,
                fallbackRole = FallbackRole.LOCALIZED
            ),
            LocalizedFieldCandidate(
                field = ResolvedField.OVERVIEW,
                value = english?.description,
                language = policy.fallbackLanguage,
                provider = provider,
                sourceShape = sourceApiShapeId,
                fallbackRole = FallbackRole.LANGUAGE_FALLBACK
            )
        )
    )
    val source = requested ?: english
    return MetadataCandidate(
        provider = provider,
        fields = buildMap {
            title?.value?.let { put(ResolvedField.TITLE, FieldValue(it, FieldOwner.PRIMARY)) }
            overview?.value?.let { put(ResolvedField.OVERVIEW, FieldValue(it, FieldOwner.PRIMARY)) }
            source?.poster?.let { put(ResolvedField.POSTER, FieldValue(it, FieldOwner.PRIMARY)) }
            source?.backdrop?.let { put(ResolvedField.BACKDROP, FieldValue(it, FieldOwner.PRIMARY)) }
            source?.logo?.let { put(ResolvedField.LOGO, FieldValue(it, FieldOwner.PRIMARY)) }
            source?.rating?.let { put(ResolvedField.RATING, FieldValue(it, FieldOwner.PRIMARY)) }
            source?.runtimeMinutes?.let { put(ResolvedField.RUNTIME, FieldValue(it, FieldOwner.PRIMARY)) }
            source?.genres?.takeIf { it.isNotEmpty() }?.let { put(ResolvedField.GENRES, FieldValue(it, FieldOwner.PRIMARY)) }
            source?.releaseInfo?.let { put(ResolvedField.RELEASE_DATE, FieldValue(it, FieldOwner.PRIMARY)) }
            source?.ageRating?.let { put(ResolvedField.AGE_RATING, FieldValue(it, FieldOwner.PRIMARY)) }
            source?.countries?.takeIf { it.isNotEmpty() }?.let { put(ResolvedField.COUNTRIES, FieldValue(it, FieldOwner.PRIMARY)) }
            source?.language?.let { put(ResolvedField.LANGUAGE, FieldValue(it, FieldOwner.PRIMARY)) }
            val people = buildList {
                source?.directorMembers?.let(::addAll)
                source?.writerMembers?.let(::addAll)
                source?.castMembers?.let(::addAll)
            }.distinctBy { member ->
                member.tmdbId?.toString() ?: "${member.name.lowercase()}|${member.character.orEmpty().lowercase()}"
            }
            if (people.isNotEmpty()) {
                put(ResolvedField.CAST, FieldValue(people, FieldOwner.PRIMARY))
            }
            val organizations = buildList {
                source?.productionCompanies?.let(::addAll)
                source?.networks?.let(::addAll)
            }
            if (organizations.isNotEmpty()) {
                put(ResolvedField.ORGANIZATION_LIST, FieldValue(organizations, FieldOwner.PRIMARY))
            }
            val remoteIds = buildMap<String, Set<String>> {
                source?.imdbId?.takeIf { it.isNotBlank() }?.let { put("imdb", setOf(it)) }
                source?.tvdbId?.let { put("tvdb", setOf(it.toString())) }
            }
            if (remoteIds.isNotEmpty()) {
                put(ResolvedField.REMOTE_IDS, FieldValue(remoteIds, FieldOwner.PRIMARY))
            }
        },
        localization = buildMap {
            title?.let { put(ResolvedField.TITLE, it.toMetadataTrace()) }
            overview?.let { put(ResolvedField.OVERVIEW, it.toMetadataTrace()) }
        }
    )
}

internal fun selectKitsuTitle(
    policy: LocalizationPolicy,
    titles: Map<String, String?>,
    canonicalTitle: String?,
    romanizedTitle: String?
): String? {
    return selectKitsuTitleField(
        policy = policy,
        titles = titles,
        canonicalTitle = canonicalTitle,
        romanizedTitle = romanizedTitle
    )?.value
}

internal fun selectKitsuTitleField(
    policy: LocalizationPolicy,
    titles: Map<String, String?>,
    canonicalTitle: String?,
    romanizedTitle: String?
): SelectedLocalizedField? {
    val requestedCode = policy.requestedLanguage.providerCode
    return LocalizationResolver.selectField(
        field = ResolvedField.TITLE,
        policy = policy,
        candidates = listOf(
            LocalizedFieldCandidate(
                field = ResolvedField.TITLE,
                value = titles[requestedCode]
                    ?: titles[requestedCode.replace("-", "_")]
                    ?: titles[requestedCode.replace("_", "-")],
                language = policy.requestedLanguage,
                provider = MetadataPrimaryProvider.KITSU,
                sourceShape = KitsuApiShapes.ANIME_CORE,
                fallbackRole = FallbackRole.LOCALIZED
            ),
            LocalizedFieldCandidate(
                field = ResolvedField.TITLE,
                value = titles["en"] ?: titles["en_us"] ?: titles["en-US"] ?: titles["en_jp"],
                language = policy.fallbackLanguage,
                provider = MetadataPrimaryProvider.KITSU,
                sourceShape = KitsuApiShapes.ANIME_CORE,
                fallbackRole = FallbackRole.LANGUAGE_FALLBACK
            ),
            LocalizedFieldCandidate(
                field = ResolvedField.TITLE,
                value = canonicalTitle,
                language = policy.fallbackLanguage,
                provider = MetadataPrimaryProvider.KITSU,
                sourceShape = KitsuApiShapes.ANIME_CORE,
                fallbackRole = FallbackRole.CANONICAL
            ),
            LocalizedFieldCandidate(
                field = ResolvedField.TITLE,
                value = romanizedTitle,
                language = policy.fallbackLanguage,
                provider = MetadataPrimaryProvider.KITSU,
                sourceShape = KitsuApiShapes.ANIME_CORE,
                fallbackRole = FallbackRole.CANONICAL
            )
        )
    )
}

internal fun selectKitsuSynopsis(
    policy: LocalizationPolicy,
    synopsis: String?,
    description: String?
): String? =
    selectKitsuSynopsisField(
        policy = policy,
        synopsis = synopsis,
        description = description
    )?.value

// F2-E-05: Kitsu's `synopsis` field is single-language (English only). No localization
// applies, so no emitFieldSelected for provider competition is needed.
internal fun selectKitsuSynopsisField(
    policy: LocalizationPolicy,
    synopsis: String?,
    description: String?
): SelectedLocalizedField? =
    LocalizationResolver.selectField(
        field = ResolvedField.OVERVIEW,
        policy = policy,
        candidates = listOf(
            LocalizedFieldCandidate(
                field = ResolvedField.OVERVIEW,
                value = null,
                language = policy.requestedLanguage,
                provider = MetadataPrimaryProvider.KITSU,
                sourceShape = KitsuApiShapes.ANIME_CORE,
                fallbackRole = FallbackRole.LOCALIZED
            ),
            LocalizedFieldCandidate(
                field = ResolvedField.OVERVIEW,
                value = synopsis,
                language = policy.fallbackLanguage,
                provider = MetadataPrimaryProvider.KITSU,
                sourceShape = KitsuApiShapes.ANIME_CORE,
                fallbackRole = FallbackRole.LANGUAGE_FALLBACK
            ),
            LocalizedFieldCandidate(
                field = ResolvedField.OVERVIEW,
                value = description,
                language = policy.fallbackLanguage,
                provider = MetadataPrimaryProvider.KITSU,
                sourceShape = KitsuApiShapes.ANIME_CORE,
                fallbackRole = FallbackRole.CANONICAL
            )
        )
    )

private fun TvdbTranslationRecord.toLocalizedCandidate(
    field: ResolvedField,
    value: String?,
    language: NormalizedLanguage,
    provider: MetadataPrimaryProvider,
    fallbackRole: FallbackRole
): LocalizedFieldCandidate =
    LocalizedFieldCandidate(
        field = field,
        value = value,
        language = language,
        provider = provider,
        sourceShape = TvdbApiShapes.SERIES_TRANSLATION,
        fallbackRole = fallbackRole
    )

internal fun emptyCandidate(provider: MetadataPrimaryProvider): MetadataCandidate =
    MetadataCandidate(provider = provider, fields = emptyMap())

internal fun KitsuImage?.bestUrl(): String? =
    this?.original ?: this?.large ?: this?.medium ?: this?.small ?: this?.tiny
