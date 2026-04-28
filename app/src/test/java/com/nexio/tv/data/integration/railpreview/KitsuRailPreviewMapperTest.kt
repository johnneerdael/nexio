package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.data.remote.api.KitsuAnimeAttributes
import com.nexio.tv.data.remote.api.KitsuAnimeResource
import com.nexio.tv.data.remote.api.KitsuImage
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.TrailerHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KitsuRailPreviewMapperTest {
    @Test
    fun `kitsu maps anime to series preview`() {
        val preview = KitsuRailPreviewMapper().mapAnime(
            railId = "kitsu_trending_anime",
            anime = KitsuAnimeResource(
                id = "1",
                attributes = KitsuAnimeAttributes(
                    canonicalTitle = "Cowboy Bebop",
                    titles = mapOf("ja_jp" to "カウボーイビバップ"),
                    synopsis = "Bounty hunters in space.",
                    startDate = "1998-04-03",
                    episodeLength = 24,
                    averageRating = "82.5",
                    posterImage = KitsuImage(large = "https://kitsu.example/poster.jpg"),
                    coverImage = KitsuImage(large = "https://kitsu.example/cover.jpg"),
                    youtubeVideoId = "abc123"
                )
            ),
            position = 0,
            generatedAtMs = 1_000L
        )!!

        assertEquals("kitsu:1", preview.sourceItemId)
        assertEquals(ContentType.SERIES, preview.itemType)
        assertEquals("Cowboy Bebop", preview.display.title)
        assertEquals("カウボーイビバップ", preview.display.originalTitle)
        assertEquals("24 min", preview.display.runtimeText)
        assertEquals("abc123", (preview.display.trailerHint as TrailerHint.YouTube).videoId)
    }

    @Test
    fun `kitsu missing id returns null`() {
        val preview = KitsuRailPreviewMapper().mapAnime(
            railId = "kitsu_trending_anime",
            anime = KitsuAnimeResource(attributes = KitsuAnimeAttributes(canonicalTitle = "No ID")),
            position = 0,
            generatedAtMs = 1_000L
        )

        assertNull(preview)
    }
}
