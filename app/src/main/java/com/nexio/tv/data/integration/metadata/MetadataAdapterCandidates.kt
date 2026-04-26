package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.data.remote.api.KitsuImage
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

internal fun buildTvdbCoreLocalizedCandidate(
    provider: MetadataPrimaryProvider,
    policy: LocalizationPolicy,
    extended: TvdbSeriesExtendedRecord?,
    englishTranslation: TvdbTranslationRecord?,
    requestedTranslation: TvdbTranslationRecord?
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
            extended?.image?.let { put(ResolvedField.POSTER, FieldValue(it, FieldOwner.PRIMARY)) }
            extended?.score?.let { put(ResolvedField.RATING, FieldValue(it, FieldOwner.PRIMARY)) }
            extended?.averageRuntime?.let { put(ResolvedField.RUNTIME, FieldValue(it, FieldOwner.PRIMARY)) }
        }
    )
}

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
