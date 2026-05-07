package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.artwork.ArtworkCandidate
import com.nexio.tv.core.artwork.ArtworkCacheKeys
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSource
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.SensitiveArtworkUrl
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.data.remote.api.TvdbArtworkRecord
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject

object TvdbArtworkTypes {
    const val POSTER = 2
    const val BACKDROP = 3
    const val CLEAR_LOGO = 23
}

class TvdbArtworkCandidateMapper @Inject constructor() {
    fun mapSeriesArtwork(
        seriesId: Int,
        artworks: List<TvdbArtworkRecord>,
        requestedLanguage: String?,
        posterFallbackImage: String? = null
    ): List<ArtworkCandidate> {
        val canonicalContentId = "tvdb:$seriesId"
        val ownerKey = ArtworkOwnerKey.CanonicalContent(canonicalContentId)
        val providerIds = ProviderIds(tvdb = seriesId.toString())
        val provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB)

        return supportedTypeOrder.mapNotNull { (tvdbType, imageType) ->
            artworks
                .filter { artwork -> artwork.type == tvdbType }
                .mapNotNull { artwork -> artwork.toCandidateInput(imageType) }
                .sortedWith(tvdbArtworkRanking)
                .firstOrNull()
                ?.toArtworkCandidate(
                    ownerKey = ownerKey,
                    canonicalContentId = canonicalContentId,
                    providerIds = providerIds,
                    provider = provider,
                    tvdbType = tvdbType,
                    priority = 0
                )
        }.withPosterFallback(
            posterFallbackImage = posterFallbackImage,
            ownerKey = ownerKey,
            canonicalContentId = canonicalContentId,
            providerIds = providerIds,
            provider = provider
        )
    }

    private fun List<ArtworkCandidate>.withPosterFallback(
        posterFallbackImage: String?,
        ownerKey: ArtworkOwnerKey,
        canonicalContentId: String,
        providerIds: ProviderIds,
        provider: ArtworkProviderId
    ): List<ArtworkCandidate> {
        if (any { it.imageType == ArtworkType.POSTER }) return this
        val trimmedFallback = posterFallbackImage?.trim()?.takeIf { it.isNotEmpty() } ?: return this
        val fallback = createArtworkCandidate(
            input = ArtworkInput(
                imageType = ArtworkType.POSTER,
                imageUrl = trimmedFallback
            ),
            ownerKey = ownerKey,
            canonicalContentId = canonicalContentId,
            providerIds = providerIds,
            provider = provider,
            tvdbType = TvdbArtworkTypes.POSTER,
            priority = 100,
            traceReason = "tvdb_series_extended_image_fallback"
        )
        return this + fallback
    }

    private fun TvdbArtworkRecord.toCandidateInput(imageType: ArtworkType): CandidateInput? {
        val trimmedImageUrl = image?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return CandidateInput(
            record = this,
            imageType = imageType,
            imageUrl = trimmedImageUrl
        )
    }

    private fun createArtworkCandidate(
        input: ArtworkInput,
        ownerKey: ArtworkOwnerKey,
        canonicalContentId: String,
        providerIds: ProviderIds,
        provider: ArtworkProviderId,
        tvdbType: Int,
        priority: Int,
        traceReason: String = "tvdb_series_extended_artwork_type_$tvdbType"
    ): ArtworkCandidate =
        ArtworkCandidate(
            ownerKey = ownerKey,
            canonicalContentId = canonicalContentId,
            providerIds = providerIds,
            mediaKind = MetadataMediaKind.SERIES,
            imageType = input.imageType,
            provider = provider,
            sourceRole = ArtworkSourceRole.PRIMARY,
            source = ArtworkSource.RemoteUrl.of(
                rawUrl = SensitiveArtworkUrl.of(input.imageUrl),
                normalizedUrlHash = ArtworkCacheKeys.normalizedUrlHash(input.imageUrl)
            ),
            priority = priority,
            requiresRuntimeFetch = true,
            imageLanguage = "en",
            trace = ArtworkTrace(
                selectedProvider = "TVDB",
                sourceRole = ArtworkSourceRole.PRIMARY.name,
                reason = traceReason
            )
        )

    private data class CandidateInput(
        val record: TvdbArtworkRecord,
        val imageType: ArtworkType,
        val imageUrl: String
    )

    private fun CandidateInput.toArtworkCandidate(
        ownerKey: ArtworkOwnerKey,
        canonicalContentId: String,
        providerIds: ProviderIds,
        provider: ArtworkProviderId,
        tvdbType: Int,
        priority: Int
    ): ArtworkCandidate =
        createArtworkCandidate(
            input = ArtworkInput(
                imageType = imageType,
                imageUrl = imageUrl
            ),
            ownerKey = ownerKey,
            canonicalContentId = canonicalContentId,
            providerIds = providerIds,
            provider = provider,
            tvdbType = tvdbType,
            priority = priority
        )

    private data class ArtworkInput(
        val imageType: ArtworkType,
        val imageUrl: String
    )

    private companion object {
        val supportedTypeOrder = listOf(
            TvdbArtworkTypes.POSTER to ArtworkType.POSTER,
            TvdbArtworkTypes.BACKDROP to ArtworkType.BACKDROP,
            TvdbArtworkTypes.CLEAR_LOGO to ArtworkType.LOGO
        )

        val tvdbArtworkRanking: Comparator<CandidateInput> =
            compareBy<CandidateInput> { languageRank(it.record.language) }
                .thenByDescending { it.record.score ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.record.id ?: Int.MAX_VALUE }

        fun languageRank(language: String?): Int =
            when (language?.trim()?.lowercase()) {
                "eng" -> 0
                null, "" -> 1
                else -> 2
            }
    }
}
