package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.artwork.ArtworkCandidate
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolver
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSource
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.SensitiveArtworkUrl
import com.nexio.tv.core.artwork.toSettingsKey
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ProviderIds
import java.security.MessageDigest
import javax.inject.Inject

class FanartTvCandidateGenerator @Inject constructor(
    private val availabilityProvider: () -> FanartTvAvailability,
    private val idSelector: FanartTvIdSelector,
    private val picker: FanartTvImagePicker,
    private val lookup: FanartTvLookup,
    private val capabilityResolver: ArtworkProviderCapabilityResolver
) {
    suspend fun generate(
        ownerKey: ArtworkOwnerKey,
        canonicalContentId: String?,
        mediaKind: MetadataMediaKind,
        providerIds: ProviderIds,
        requestedTypes: Set<ArtworkType>,
        settings: ArtworkProviderSettings
    ): List<ArtworkCandidate> {
        val availability = availabilityProvider() as? FanartTvAvailability.Available
            ?: return emptyList()

        val typesToTry = requestedTypes
            .filter { it != ArtworkType.THUMBNAIL }
            .filter { settings.selection.providerFor(it.toSettingsKey()) == ArtworkProviderChoiceKey.FANART_TV }
            .filter { type ->
                capabilityResolver.evaluate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
                    imageType = type,
                    ids = providerIds,
                    mediaKind = mediaKind,
                    settings = settings
                ).supported
            }
        if (typesToTry.isEmpty()) return emptyList()

        val callId = idSelector.select(mediaKind, providerIds) ?: return emptyList()

        val result = lookup.fetch(callId, availability.apiKey)
        val doc = (result as? FanartTvLookupResult.Success)?.document ?: return emptyList()

        val out = mutableListOf<ArtworkCandidate>()
        for (i in typesToTry.indices) {
            val type = typesToTry[i]
            val url = picker.pickFor(doc, callId.type, type) ?: continue
            out += buildCandidate(ownerKey, canonicalContentId, mediaKind, providerIds, type, url)
        }
        return out
    }

    private fun buildCandidate(
        ownerKey: ArtworkOwnerKey,
        canonicalContentId: String?,
        mediaKind: MetadataMediaKind,
        providerIds: ProviderIds,
        type: ArtworkType,
        url: String
    ): ArtworkCandidate {
        val sensitive = SensitiveArtworkUrl.of(url)
        val source = ArtworkSource.RemoteUrl.of(
            rawUrl = sensitive,
            normalizedUrlHash = url.sha256()
        )
        return ArtworkCandidate(
            ownerKey = ownerKey,
            canonicalContentId = canonicalContentId,
            providerIds = providerIds,
            mediaKind = mediaKind,
            imageType = type,
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV),
            sourceRole = ArtworkSourceRole.PREMIUM,
            source = source,
            priority = PRIORITY,
            requiresRuntimeFetch = true,
            imageLanguage = "en"
        )
    }

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    companion object {
        const val PRIORITY = 15
    }
}
