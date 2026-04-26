package com.nexio.tv.data.integration.metadata

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

internal fun emptyCandidate(provider: MetadataPrimaryProvider): MetadataCandidate =
    MetadataCandidate(provider = provider, fields = emptyMap())

internal fun KitsuImage?.bestUrl(): String? =
    this?.original ?: this?.large ?: this?.medium ?: this?.small ?: this?.tiny
