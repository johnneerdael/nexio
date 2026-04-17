package com.nexio.tv.ui.screens.player

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.domain.model.Meta

internal fun PlayerUiState.withLocalizedPlaybackMetadata(
    enrichment: TvMetadataEnrichment?,
    currentEpisodeMetadata: TvEpisodeMetadata?
): PlayerUiState {
    val localizedTitle = enrichment?.localizedTitle.nonBlank()
    val localizedDescription = currentEpisodeMetadata?.overview.nonBlank()
        ?: enrichment?.description.nonBlank()
    val localizedEpisodeTitle = currentEpisodeMetadata?.title.nonBlank()
    val localizedBackdrop = enrichment?.backdrop.nonBlank()
    val localizedLogo = enrichment?.logo.nonBlank()
    val localizedCast = enrichment?.castMembers
        ?.takeIf { it.isNotEmpty() }
        ?: castMembers

    return copy(
        title = localizedTitle ?: title,
        contentName = localizedTitle ?: contentName,
        description = localizedDescription ?: description,
        currentEpisodeTitle = localizedEpisodeTitle ?: currentEpisodeTitle,
        backdrop = localizedBackdrop ?: backdrop,
        logo = localizedLogo ?: logo,
        castMembers = localizedCast
    )
}

internal fun PlayerUiState.withAddonMetaArtwork(meta: Meta): PlayerUiState {
    val addonBackdrop = meta.background.nonBlank()
    val addonLogo = meta.logo.nonBlank()

    return copy(
        backdrop = backdrop.nonBlank() ?: addonBackdrop,
        logo = logo.nonBlank() ?: addonLogo
    )
}

private fun String?.nonBlank(): String? = this
    ?.trim()
    ?.takeIf { it.isNotBlank() }
