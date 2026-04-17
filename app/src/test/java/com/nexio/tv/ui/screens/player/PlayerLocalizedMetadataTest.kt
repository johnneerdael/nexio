package com.nexio.tv.ui.screens.player

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerLocalizedMetadataTest {

    @Test
    fun `provider localized metadata replaces english pause overlay metadata`() {
        val addonCast = listOf(MetaCastMember(name = "Addon Actor"))
        val localizedCast = listOf(MetaCastMember(name = "Localized Actor", character = "Rechercheur"))
        val state = PlayerUiState(
            title = "English Show",
            contentName = "English Show",
            description = "English series overview",
            castMembers = addonCast,
            backdrop = "english-backdrop",
            logo = "english-logo",
            currentSeason = 1,
            currentEpisode = 2,
            currentEpisodeTitle = "English episode title"
        )

        val localized = state.withLocalizedPlaybackMetadata(
            enrichment = TvMetadataEnrichment(
                seriesTvdbId = 12,
                localizedTitle = "Nederlandse serie",
                description = "Nederlandse serie beschrijving",
                backdrop = "localized-backdrop",
                logo = "localized-logo",
                castMembers = localizedCast
            ),
            currentEpisodeMetadata = TvEpisodeMetadata(
                seasonNumber = 1,
                episodeNumber = 2,
                title = "Nederlandse aflevering",
                overview = "Nederlandse aflevering beschrijving"
            )
        )

        assertEquals("Nederlandse serie", localized.title)
        assertEquals("Nederlandse serie", localized.contentName)
        assertEquals("Nederlandse aflevering", localized.currentEpisodeTitle)
        assertEquals("Nederlandse aflevering beschrijving", localized.description)
        assertEquals("localized-backdrop", localized.backdrop)
        assertEquals("localized-logo", localized.logo)
        assertEquals(localizedCast, localized.castMembers)
    }

    @Test
    fun `blank localized fields keep existing pause overlay metadata`() {
        val addonCast = listOf(MetaCastMember(name = "Addon Actor"))
        val state = PlayerUiState(
            title = "English Movie",
            contentName = "English Movie",
            description = "English overview",
            castMembers = addonCast,
            backdrop = "english-backdrop",
            logo = "english-logo"
        )

        val localized = state.withLocalizedPlaybackMetadata(
            enrichment = TvMetadataEnrichment(
                seriesTvdbId = null,
                localizedTitle = "",
                description = "",
                backdrop = "",
                logo = "",
                castMembers = emptyList()
            ),
            currentEpisodeMetadata = null
        )

        assertEquals("English Movie", localized.title)
        assertEquals("English Movie", localized.contentName)
        assertEquals("English overview", localized.description)
        assertEquals("english-backdrop", localized.backdrop)
        assertEquals("english-logo", localized.logo)
        assertEquals(addonCast, localized.castMembers)
    }

    @Test
    fun `addon metadata fills blank playback artwork from existing meta fetch`() {
        val state = PlayerUiState(
            title = "Playback Title",
            backdrop = null,
            logo = ""
        )

        val updated = state.withAddonMetaArtwork(
            meta = testMeta(
                background = "detail-backdrop",
                logo = "detail-logo"
            )
        )

        assertEquals("detail-backdrop", updated.backdrop)
        assertEquals("detail-logo", updated.logo)
    }

    @Test
    fun `addon metadata does not replace existing playback artwork`() {
        val state = PlayerUiState(
            title = "Playback Title",
            backdrop = "route-backdrop",
            logo = "route-logo"
        )

        val updated = state.withAddonMetaArtwork(
            meta = testMeta(
                background = "detail-backdrop",
                logo = "detail-logo"
            )
        )

        assertEquals("route-backdrop", updated.backdrop)
        assertEquals("route-logo", updated.logo)
    }
}

private fun testMeta(
    background: String?,
    logo: String?
): Meta {
    return Meta(
        id = "movie-1",
        type = ContentType.MOVIE,
        name = "Playback Title",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = background,
        logo = logo,
        description = "Description",
        releaseInfo = "2024",
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )
}
