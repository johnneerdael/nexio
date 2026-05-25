package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
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
    private val capabilityResolver: ArtworkProviderCapabilityResolver,
    private val traceEvents: TraceMetadataEvents = TraceMetadataEvents(
        sink = NoopRuntimeTraceSink,
        sessionId = { null }
    )
) {
    fun resolve(
        artworkType: ArtworkType,
        contentType: ContentType,
        isAnime: Boolean,
        availableIds: ProviderIds,
        settings: ArtworkProviderSettings
    ): ArtworkProviderId {
        val explicit = settings.selection.providerFor(artworkType.toSettingsKey())
        var capabilitySupported = true
        var fellThroughTo: String? = null
        val mediaKind = if (isAnime) {
            MetadataMediaKind.ANIME
        } else {
            contentType.toMetadataMediaKind()
        }
        val chosen: ArtworkProviderId = if (explicit != ArtworkProviderChoiceKey.DEFAULT) {
            val provider = explicit.toRuntimeProviderId()
            val capable = capabilityResolver.evaluate(
                provider = provider,
                imageType = artworkType,
                ids = availableIds,
                mediaKind = mediaKind,
                settings = settings
            )
            capabilitySupported = capable.supported
            if (capable.supported) {
                provider
            } else {
                val fallback = ContentTypeDefaults.resolve(artworkType, isAnime)
                fellThroughTo = fallback.key
                fallback
            }
        } else {
            ContentTypeDefaults.resolve(artworkType, isAnime)
        }

        traceEvents.emitArtworkResolverDecision(
            itemKeyHash = null,
            artworkType = artworkType.name,
            contentType = contentType.name,
            isAnime = isAnime,
            explicit = explicit.value,
            fellThroughTo = fellThroughTo,
            chosenProvider = chosen.key,
            capabilitySupported = capabilitySupported
        )

        return chosen
    }
}

internal object ContentTypeDefaults {
    private val tmdbProvider =
        ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB)
    private val kitsuProvider =
        ArtworkProviderId.RuntimeProvider(IntegrationProvider.KITSU)

    fun resolve(artworkType: ArtworkType, isAnime: Boolean): ArtworkProviderId =
        when (artworkType) {
            ArtworkType.POSTER,
            ArtworkType.BACKDROP,
            ArtworkType.LOGO,
            ArtworkType.THUMBNAIL ->
                if (isAnime) kitsuProvider else tmdbProvider
        }
}

const val DEFAULTS_TABLE_VERSION = 2
