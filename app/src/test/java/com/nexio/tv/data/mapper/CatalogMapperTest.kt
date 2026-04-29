package com.nexio.tv.data.mapper

import com.nexio.tv.data.remote.dto.CatalogResponseDto
import com.nexio.tv.data.remote.dto.CatalogBehaviorHintsDto
import com.nexio.tv.data.remote.dto.MetaPreviewDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.PosterShape
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogMapperTest {
    @Test
    fun `tmdb addon search shape maps genre and year for first paint`() {
        val preview = MetaPreviewDto(
            id = "tmdb:687163",
            type = "movie",
            name = "Project Hail Mary",
            poster = "https://image.tmdb.org/t/p/w500/yihdXomYb5kTeSivtFndMy5iDmf.jpg",
            background = "https://image.tmdb.org/t/p/original/8Tfys3mDZVp4tNoH2ktm06a0Tau.jpg",
            posterShape = "regular",
            description = "Science teacher Ryland Grace wakes up on a spaceship light years from home.",
            imdbRating = "8.2",
            genre = listOf("Science Fiction", "Adventure"),
            year = "2026"
        ).toDomain()

        assertEquals("tmdb:687163", preview.id)
        assertEquals(ContentType.MOVIE, preview.type)
        assertEquals("movie", preview.apiType)
        assertEquals("Project Hail Mary", preview.name)
        assertEquals(PosterShape.POSTER, preview.posterShape)
        assertEquals("2026", preview.releaseInfo)
        assertEquals(listOf("Science Fiction", "Adventure"), preview.genres)
        assertEquals(8.2f, preview.imdbRating)
        assertEquals(FirstPaintSource.ADDON_META_PREVIEW, preview.firstPaintSource)
    }

    @Test
    fun `addon catalog preview harvests tmdb and imdb stable ids`() {
        val preview = MetaPreviewDto(
            id = "tmdb:687163",
            type = "movie",
            name = "Project Hail Mary",
            imdbId = "tt12042730",
            behaviorHints = CatalogBehaviorHintsDto(
                defaultVideoId = "tt12042730"
            )
        ).toDomain()

        assertEquals("687163", preview.firstPaintStableIds.tmdb)
        assertEquals("tt12042730", preview.firstPaintStableIds.imdb)
        assertEquals(FirstPaintSource.ADDON_META_PREVIEW, preview.firstPaintSource)
    }

    @Test
    fun `addon imdb id wins over behavior hint imdb id`() {
        val preview = MetaPreviewDto(
            id = "tmdb:687163",
            type = "movie",
            name = "Project Hail Mary",
            imdbId = "tt1111111",
            behaviorHints = CatalogBehaviorHintsDto(
                defaultVideoId = "tt2222222"
            )
        ).toDomain()

        assertEquals("tt1111111", preview.firstPaintStableIds.imdb)
        assertEquals("687163", preview.firstPaintStableIds.tmdb)
    }

    @Test
    fun `addon stable id harvest rejects episode and malformed imdb ids`() {
        val episodeLikePreview = MetaPreviewDto(
            id = "tt1234567:1:1",
            type = "series",
            name = "Episode Like Id",
            imdbId = "ttfoo",
            behaviorHints = CatalogBehaviorHintsDto(
                defaultVideoId = "tt7654321:2:3"
            )
        ).toDomain()

        assertEquals(null, episodeLikePreview.firstPaintStableIds.imdb)
    }

    @Test
    fun `addon raw imdb title id is harvested as stable imdb id`() {
        val preview = MetaPreviewDto(
            id = "tt12042730",
            type = "movie",
            name = "Project Hail Mary"
        ).toDomain()

        assertEquals("tt12042730", preview.firstPaintStableIds.imdb)
        assertEquals(null, preview.firstPaintStableIds.tmdb)
    }

    @Test
    fun `moshi parses tmdb addon alias fields for first paint`() {
        val response = parseCatalogResponse(
            """
            {
              "metas": [
                {
                  "id": "tmdb:687163",
                  "type": "movie",
                  "name": "Project Hail Mary",
                  "genre": ["Science Fiction", "Adventure"],
                  "year": "2026"
                }
              ]
            }
            """.trimIndent()
        )

        val preview = response.metas.single().toDomain()

        assertEquals(listOf("Science Fiction", "Adventure"), preview.genres)
        assertEquals("2026", preview.releaseInfo)
    }

    @Test
    fun `addon behavior hints provide imdb stable id when imdb id is absent`() {
        val response = parseCatalogResponse(
            """
            {
              "metas": [
                {
                  "id": "tmdb:687163",
                  "type": "movie",
                  "name": "Project Hail Mary",
                  "behaviorHints": {
                    "defaultVideoId": "tt12042730"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val preview = response.metas.single().toDomain()

        assertEquals("687163", preview.firstPaintStableIds.tmdb)
        assertEquals("tt12042730", preview.firstPaintStableIds.imdb)
    }

    @Test
    fun `canonical addon fields win over alias fields for first paint`() {
        val response = parseCatalogResponse(
            """
            {
              "metas": [
                {
                  "id": "tmdb:687163",
                  "type": "movie",
                  "name": "Project Hail Mary",
                  "genres": ["Drama"],
                  "genre": ["Science Fiction"],
                  "releaseInfo": "2026-03-15",
                  "year": "2026"
                }
              ]
            }
            """.trimIndent()
        )

        val preview = response.metas.single().toDomain()

        assertEquals(listOf("Drama"), preview.genres)
        assertEquals("2026-03-15", preview.releaseInfo)
    }

    @Test
    fun `addon catalog root preserves mixed item source payload for first paint`() {
        val response = parseCatalogResponse(
            """
            {
              "metas": [
                {
                  "id": "tmdb:687163",
                  "type": "movie",
                  "name": "Project Hail Mary",
                  "poster": "https://image.example/poster-hail-mary.jpg",
                  "background": "https://image.example/backdrop-hail-mary.jpg",
                  "imdb_id": "tt12042730"
                },
                {
                  "id": "tt14403178",
                  "type": "series",
                  "name": "Raw IMDb Series",
                  "poster": "https://image.example/poster-series.jpg",
                  "background": "https://image.example/backdrop-series.jpg",
                  "behaviorHints": {
                    "defaultVideoId": "tt14403178"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val previews = response.metas.map { it.toDomain() }
        val movie = previews[0]
        val series = previews[1]

        assertEquals("tmdb:687163", movie.id)
        assertEquals(ContentType.MOVIE, movie.type)
        assertEquals("movie", movie.rawType)
        assertEquals("Project Hail Mary", movie.name)
        assertEquals("https://image.example/poster-hail-mary.jpg", movie.poster)
        assertEquals("https://image.example/backdrop-hail-mary.jpg", movie.background)
        assertEquals("687163", movie.firstPaintStableIds.tmdb)
        assertEquals("tt12042730", movie.firstPaintStableIds.imdb)
        assertEquals(FirstPaintSource.ADDON_META_PREVIEW, movie.firstPaintSource)

        assertEquals("tt14403178", series.id)
        assertEquals(ContentType.SERIES, series.type)
        assertEquals("series", series.rawType)
        assertEquals("Raw IMDb Series", series.name)
        assertEquals("https://image.example/poster-series.jpg", series.poster)
        assertEquals("https://image.example/backdrop-series.jpg", series.background)
        assertEquals("tt14403178", series.firstPaintStableIds.imdb)
        assertEquals(null, series.firstPaintStableIds.tmdb)
        assertEquals(FirstPaintSource.ADDON_META_PREVIEW, series.firstPaintSource)
    }

    private fun parseCatalogResponse(json: String): CatalogResponseDto {
        return Moshi.Builder()
            .build()
            .adapter(CatalogResponseDto::class.java)
            .fromJson(json)
            ?: error("CatalogResponseDto did not parse")
    }
}
