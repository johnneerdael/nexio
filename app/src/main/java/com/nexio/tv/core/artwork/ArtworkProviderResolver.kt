package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.toMetadataMediaKind
import com.nexio.tv.domain.model.toRuntimeProviderId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtworkProviderResolver @Inject constructor(
    private val capabilityResolver: ArtworkProviderCapabilityResolver
) {
    fun resolve(
        artworkType: ArtworkType,
        contentType: ContentType,
        isAnime: Boolean,
        availableIds: ProviderIds,
        settings: ArtworkProviderSettings
    ): ArtworkProviderId {
        val explicit = settings.selection.providerFor(artworkType.toSettingsKey())
        if (explicit != ArtworkProviderChoiceKey.DEFAULT) {
            val provider = explicit.toRuntimeProviderId()
            val capable = capabilityResolver.evaluate(
                provider = provider,
                imageType = artworkType,
                ids = availableIds,
                mediaKind = contentType.toMetadataMediaKind(),
                settings = settings
            )
            if (capable.supported) return provider
        }
        return ContentTypeDefaults.resolve(artworkType, isAnime)
    }
}

internal object ContentTypeDefaults {
    private val addonProvider =
        ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON)

    fun resolve(artworkType: ArtworkType, isAnime: Boolean): ArtworkProviderId =
        when (artworkType) {
            ArtworkType.POSTER,
            ArtworkType.BACKDROP,
            ArtworkType.LOGO ->
                if (isAnime) addonProvider else addonProvider
                //  ↑ fanart.tv lands → else fanartProvider
            ArtworkType.THUMBNAIL -> addonProvider
        }
}

const val DEFAULTS_TABLE_VERSION = 1
