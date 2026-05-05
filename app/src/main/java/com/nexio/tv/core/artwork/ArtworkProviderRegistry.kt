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
            if (topPostersDescriptor.isAvailableFor(type, settings)) {
                add(ArtworkProviderChoiceKey.TOP_POSTERS)
            }
            if (rpdbDescriptor.isAvailableFor(type, settings)) {
                add(ArtworkProviderChoiceKey.RPDB)
            }
        }

    fun providerIdFor(choice: ArtworkProviderChoiceKey): ArtworkProviderId? =
        when (choice.value) {
            ArtworkProviderChoiceKey.RPDB.value ->
                ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB)
            ArtworkProviderChoiceKey.TOP_POSTERS.value ->
                ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS)
            else -> null
        }
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
