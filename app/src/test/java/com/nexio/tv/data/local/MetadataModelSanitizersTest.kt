package com.nexio.tv.data.local

import com.google.gson.Gson
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaLink
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataModelSanitizersTest {
    private val gson = Gson()

    @Test
    fun `sanitize legacy preview without rating source`() {
        val preview = legacyPreviewJson()

        val sanitized = preview.sanitizedForCache()

        sanitized.hashCode()
        assertEquals(TitleRatingSource.IMDB, sanitized.ratingSource)
        assertEquals(emptyList<String>(), sanitized.trailerYtIds)
        assertEquals(emptyList<String>(), sanitized.genres)
    }

    @Test
    fun `sanitize legacy home display metadata without rating source`() {
        val metadata = gson.fromJson(
            """
            {
              "title":"Movie",
              "logo":null,
              "description":null,
              "genres":[],
              "releaseInfo":"2025",
              "runtime":null,
              "imdbRating":8.3,
              "tomatoesRating":null,
              "poster":null,
              "posterProviderTag":null,
              "backdrop":null
            }
            """.trimIndent(),
            HomeDisplayMetadata::class.java
        )

        val sanitized = metadata.sanitizedForCache()

        sanitized.hashCode()
        assertEquals(TitleRatingSource.IMDB, sanitized.ratingSource)
        assertEquals(emptyList<String>(), sanitized.genres)
    }

    @Test
    fun `sanitize legacy tmdb enrichment without rating source`() {
        val enrichment = gson.fromJson(
            """
            {
              "localizedTitle":"Movie",
              "description":null,
              "genres":[],
              "backdrop":null,
              "logo":null,
              "poster":null,
              "directorMembers":[],
              "writerMembers":[],
              "castMembers":[],
              "releaseInfo":"2025",
              "rating":8.1,
              "runtimeMinutes":120,
              "director":[],
              "writer":[],
              "productionCompanies":[],
              "networks":[],
              "ageRating":null,
              "countries":null,
              "language":"en",
              "collectionId":null,
              "collectionName":null
            }
            """.trimIndent(),
            TmdbEnrichment::class.java
        )

        val sanitized = enrichment.sanitizedForCache()

        sanitized.hashCode()
        assertEquals(TitleRatingSource.TMDB, sanitized.ratingSource)
    }

    @Test
    fun `sanitize catalog row items`() {
        val row = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://example.com",
            catalogId = "row",
            catalogName = "Row",
            type = ContentType.MOVIE,
            items = listOf(legacyPreviewJson())
        )

        val sanitized = row.sanitizedForCache()

        sanitized.hashCode()
        assertEquals(TitleRatingSource.IMDB, sanitized.items.single().ratingSource)
    }

    @Test
    fun `sanitize tv metadata enrichment without rating source`() {
        val enrichment = TvMetadataEnrichment(
            seriesTvdbId = 1,
            localizedTitle = "Show",
            rating = 8.0,
            ratingSource = null
        )

        val sanitized = enrichment.sanitizedForCache()

        sanitized.hashCode()
        assertEquals(TitleRatingSource.IMDB, sanitized.ratingSource)
    }

    @Test
    fun `sanitize meta without rating source`() {
        val meta = Meta(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Movie",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2025",
            imdbRating = 8.3f,
            ratingSource = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            writer = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = emptyList<Video>(),
            productionCompanies = emptyList<MetaCompany>(),
            networks = emptyList<MetaCompany>(),
            ageRating = null,
            country = null,
            awards = null,
            language = null,
            links = emptyList<MetaLink>(),
            trailerYtIds = emptyList()
        )

        val sanitized = meta.sanitizedForCache()

        sanitized.hashCode()
        assertEquals(TitleRatingSource.IMDB, sanitized.ratingSource)
    }

    @Test
    fun `sanitize meta removes legacy premium poster refs`() {
        val meta = sampleMeta(
            poster = "integration-poster://fetch?provider=RPDB",
            posterProviderTag = "rpdb"
        )

        val sanitized = meta.sanitizedForCache()

        assertNull(sanitized.poster)
        assertNull(sanitized.posterProviderTag)
    }

    @Test
    fun `sanitize meta removes raw premium poster urls`() {
        val meta = sampleMeta(
            poster = "https://api.ratingposterdb.com/secret/imdb/poster-default/tt123.jpg",
            posterProviderTag = "rpdb"
        )

        val sanitized = meta.sanitizedForCache()

        assertNull(sanitized.poster)
        assertNull(sanitized.posterProviderTag)
    }

    @Test
    fun `sanitize home display metadata removes raw premium poster urls`() {
        val metadata = HomeDisplayMetadata(
            poster = "https://api.top-posters.com/secret/imdb/poster/tt123.jpg",
            posterProviderTag = "top_posters"
        )

        val sanitized = metadata.sanitizedForCache()

        assertNull(sanitized.poster)
        assertNull(sanitized.posterProviderTag)
    }

    @Test
    fun `sanitize meta preserves nexio artwork refs`() {
        val meta = sampleMeta(
            poster = "nexio-artwork://decision/premium-poster",
            posterProviderTag = "rpdb"
        )

        val sanitized = meta.sanitizedForCache()

        assertEquals("nexio-artwork://decision/premium-poster", sanitized.poster)
        assertEquals("rpdb", sanitized.posterProviderTag)
    }

    private fun legacyPreviewJson(): MetaPreview {
        return gson.fromJson(
            """
            {
              "id":"tt123",
              "type":"MOVIE",
              "rawType":"movie",
              "name":"Movie",
              "poster":null,
              "posterShape":"POSTER",
              "background":null,
              "logo":null,
              "description":null,
              "releaseInfo":"2025",
              "runtime":null,
              "imdbRating":8.3,
              "tomatoesRating":null,
              "genres":[],
              "trailerYtIds":[],
              "language":null,
              "posterProviderTag":null
            }
            """.trimIndent(),
            MetaPreview::class.java
        )
    }

    private fun sampleMeta(
        poster: String?,
        posterProviderTag: String?
    ): Meta =
        Meta(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Movie",
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2025",
            imdbRating = 8.3f,
            ratingSource = TitleRatingSource.IMDB,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            writer = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = emptyList<Video>(),
            productionCompanies = emptyList<MetaCompany>(),
            networks = emptyList<MetaCompany>(),
            ageRating = null,
            country = null,
            awards = null,
            language = null,
            links = emptyList<MetaLink>(),
            trailerYtIds = emptyList(),
            posterProviderTag = posterProviderTag
        )
}
