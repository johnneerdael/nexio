package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ArtworkTypeKey

class ArtworkProviderRegistry {
    fun availableChoices(
        type: ArtworkType,
        settings: ArtworkProviderSettings
    ): List<ArtworkProviderChoiceKey> =
        buildList {
            add(ArtworkProviderChoiceKey.DEFAULT)
            artworkProviderDescriptors
                .filter { descriptor -> descriptor.isAvailableFor(type, settings) }
                .forEach { descriptor -> add(descriptor.choice) }
        }

    fun providerIdFor(choice: ArtworkProviderChoiceKey): ArtworkProviderId? =
        artworkProviderDescriptors
            .firstOrNull { descriptor -> descriptor.choice == choice }
            ?.let { descriptor -> ArtworkProviderId.RuntimeProvider(descriptor.provider) }
}

fun ArtworkType.toSettingsKey(): ArtworkTypeKey =
    when (this) {
        ArtworkType.POSTER -> ArtworkTypeKey.POSTER
        ArtworkType.BACKDROP -> ArtworkTypeKey.BACKDROP
        ArtworkType.LOGO -> ArtworkTypeKey.LOGO
        ArtworkType.THUMBNAIL -> ArtworkTypeKey.THUMBNAIL
    }

internal enum class ArtworkProviderStableIdType {
    IMDB,
    TMDB,
    TVDB,
    TRAKT,
    MAL,
    KITSU,
    ANILIST,
    ANIDB
}

internal data class ArtworkProviderDescriptor(
    val choice: ArtworkProviderChoiceKey,
    val provider: IntegrationProvider,
    val supportedArtworkTypes: Set<ArtworkType>,
    val supportedIdTypes: Set<ArtworkProviderStableIdType>,
    val embedsRatings: Boolean,
    val isConfigured: (ArtworkProviderSettings) -> Boolean,
    val canExposeChoiceFor: (ArtworkType, ArtworkProviderSettings) -> Boolean = { _, settings ->
        isConfigured(settings)
    }
) {
    fun isAvailableFor(type: ArtworkType, settings: ArtworkProviderSettings): Boolean =
        type in supportedArtworkTypes && canExposeChoiceFor(type, settings)
}

internal val rpdbDescriptor = ArtworkProviderDescriptor(
    choice = ArtworkProviderChoiceKey.RPDB,
    provider = IntegrationProvider.RPDB,
    supportedArtworkTypes = setOf(ArtworkType.POSTER),
    supportedIdTypes = setOf(
        ArtworkProviderStableIdType.IMDB,
        ArtworkProviderStableIdType.TMDB,
        ArtworkProviderStableIdType.TVDB
    ),
    embedsRatings = true,
    isConfigured = { it.hasRpdbKey }
)

internal val topPostersDescriptor = ArtworkProviderDescriptor(
    choice = ArtworkProviderChoiceKey.TOP_POSTERS,
    provider = IntegrationProvider.TOP_POSTERS,
    supportedArtworkTypes = setOf(ArtworkType.POSTER, ArtworkType.THUMBNAIL),
    supportedIdTypes = setOf(
        ArtworkProviderStableIdType.IMDB,
        ArtworkProviderStableIdType.TMDB,
        ArtworkProviderStableIdType.TVDB,
        ArtworkProviderStableIdType.TRAKT,
        ArtworkProviderStableIdType.MAL,
        ArtworkProviderStableIdType.KITSU,
        ArtworkProviderStableIdType.ANILIST,
        ArtworkProviderStableIdType.ANIDB
    ),
    embedsRatings = true,
    isConfigured = { it.hasTopPostersKey },
    canExposeChoiceFor = { type, settings ->
        settings.hasTopPostersKey &&
            (type != ArtworkType.THUMBNAIL || settings.topPostersCanProvideThumbnails)
    }
)

internal val fanartTvDescriptor = ArtworkProviderDescriptor(
    choice = ArtworkProviderChoiceKey.FANART_TV,
    provider = IntegrationProvider.FANART_TV,
    supportedArtworkTypes = setOf(
        ArtworkType.POSTER,
        ArtworkType.LOGO,
        ArtworkType.BACKDROP
    ),
    supportedIdTypes = setOf(
        ArtworkProviderStableIdType.TMDB,
        ArtworkProviderStableIdType.TVDB
    ),
    embedsRatings = false,
    isConfigured = { _ ->
        (com.nexio.tv.core.artwork.fanarttv.FanartTvAvailability
            .from(com.nexio.tv.BuildConfig.FANARTTV_API_KEY)
                is com.nexio.tv.core.artwork.fanarttv.FanartTvAvailability.Available)
    }
)

internal val artworkProviderDescriptors = listOf(
    topPostersDescriptor,
    rpdbDescriptor,
    fanartTvDescriptor
)
