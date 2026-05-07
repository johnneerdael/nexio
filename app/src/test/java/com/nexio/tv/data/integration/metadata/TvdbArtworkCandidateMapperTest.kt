package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkCacheKeys
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSource
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.data.remote.api.TvdbArtworkRecord
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvdbArtworkCandidateMapperTest {
    private val mapper = TvdbArtworkCandidateMapper()

    @Test
    fun `maps tvdb series artwork types to shared artwork candidates`() {
        val candidates = mapper.mapSeriesArtwork(
            seriesId = 81189,
            requestedLanguage = "nl",
            artworks = listOf(
                artwork(id = 300, type = TvdbArtworkTypes.POSTER, image = " https://art.tvdb.com/poster.jpg "),
                artwork(id = 301, type = TvdbArtworkTypes.BACKDROP, image = "https://art.tvdb.com/backdrop.jpg"),
                artwork(id = 302, type = TvdbArtworkTypes.CLEAR_LOGO, image = "https://art.tvdb.com/logo.png")
            )
        )

        assertEquals(listOf(ArtworkType.POSTER, ArtworkType.BACKDROP, ArtworkType.LOGO), candidates.map { it.imageType })

        candidates.forEach { candidate ->
            assertEquals(ArtworkOwnerKey.CanonicalContent("tvdb:81189"), candidate.ownerKey)
            assertEquals("tvdb:81189", candidate.canonicalContentId)
            assertEquals(ProviderIds(tvdb = "81189"), candidate.providerIds)
            assertEquals(MetadataMediaKind.SERIES, candidate.mediaKind)
            assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB), candidate.provider)
            assertEquals(ArtworkSourceRole.PRIMARY, candidate.sourceRole)
            assertEquals(0, candidate.priority)
            assertTrue(candidate.requiresRuntimeFetch)
            assertEquals("en", candidate.imageLanguage)
            assertEquals("TVDB", candidate.trace.selectedProvider)
            assertEquals("PRIMARY", candidate.trace.sourceRole)
        }

        val poster = candidates.single { it.imageType == ArtworkType.POSTER }
        assertEquals("tvdb_series_extended_artwork_type_2", poster.trace.reason)
        assertEquals(
            ArtworkSource.RemoteUrl.of(
                rawUrl = com.nexio.tv.core.artwork.SensitiveArtworkUrl.of("https://art.tvdb.com/poster.jpg"),
                normalizedUrlHash = ArtworkCacheKeys.normalizedUrlHash("https://art.tvdb.com/poster.jpg")
            ),
            poster.source
        )
    }

    @Test
    fun `ignores unsupported and blank tvdb artwork records`() {
        val candidates = mapper.mapSeriesArtwork(
            seriesId = 81189,
            requestedLanguage = null,
            artworks = listOf(
                artwork(id = 1, type = 22, image = "https://art.tvdb.com/unsupported.jpg"),
                artwork(id = 2, type = TvdbArtworkTypes.POSTER, image = null),
                artwork(id = 3, type = TvdbArtworkTypes.BACKDROP, image = "   "),
                artwork(id = 4, type = TvdbArtworkTypes.CLEAR_LOGO, image = "https://art.tvdb.com/logo.png")
            )
        )

        assertEquals(listOf(ArtworkType.LOGO), candidates.map { it.imageType })
        assertEquals("https://art.tvdb.com/<redacted>", (candidates.single().source as ArtworkSource.RemoteUrl).redactedUrlForTrace)
    }

    @Test
    fun `prefers english artwork for global image language policy then score then stable id`() {
        val candidates = mapper.mapSeriesArtwork(
            seriesId = 81189,
            requestedLanguage = "spa",
            artworks = listOf(
                artwork(
                    id = 10,
                    type = TvdbArtworkTypes.POSTER,
                    language = "spa",
                    score = 100.0,
                    image = "https://art.tvdb.com/spanish-poster.jpg"
                ),
                artwork(
                    id = 8,
                    type = TvdbArtworkTypes.POSTER,
                    language = null,
                    score = 80.0,
                    image = "https://art.tvdb.com/no-language-poster.jpg"
                ),
                artwork(
                    id = 7,
                    type = TvdbArtworkTypes.POSTER,
                    language = "eng",
                    score = 10.0,
                    image = "https://art.tvdb.com/english-low-score-poster.jpg"
                ),
                artwork(
                    id = 9,
                    type = TvdbArtworkTypes.POSTER,
                    language = "eng",
                    score = 15.0,
                    image = "https://art.tvdb.com/english-high-score-poster.jpg"
                ),
                artwork(
                    id = 5,
                    type = TvdbArtworkTypes.BACKDROP,
                    language = null,
                    score = 10.0,
                    image = "https://art.tvdb.com/no-language-low-id-backdrop.jpg"
                ),
                artwork(
                    id = 6,
                    type = TvdbArtworkTypes.BACKDROP,
                    language = null,
                    score = 10.0,
                    image = "https://art.tvdb.com/no-language-high-id-backdrop.jpg"
                ),
                artwork(
                    id = 20,
                    type = TvdbArtworkTypes.CLEAR_LOGO,
                    language = null,
                    score = 20.0,
                    image = "https://art.tvdb.com/no-language-logo.jpg"
                ),
                artwork(
                    id = 21,
                    type = TvdbArtworkTypes.CLEAR_LOGO,
                    language = "fra",
                    score = 100.0,
                    image = "https://art.tvdb.com/french-logo.jpg"
                )
            )
        )

        assertEquals(
            "https://art.tvdb.com/english-high-score-poster.jpg",
            (candidates.single { it.imageType == ArtworkType.POSTER }.source as ArtworkSource.RemoteUrl).rawUrl.value
        )
        assertEquals(
            "https://art.tvdb.com/no-language-low-id-backdrop.jpg",
            (candidates.single { it.imageType == ArtworkType.BACKDROP }.source as ArtworkSource.RemoteUrl).rawUrl.value
        )
        assertEquals(
            "https://art.tvdb.com/no-language-logo.jpg",
            (candidates.single { it.imageType == ArtworkType.LOGO }.source as ArtworkSource.RemoteUrl).rawUrl.value
        )
    }

    @Test
    fun `uses extended image as lower priority poster fallback when type 2 artwork is absent`() {
        val candidates = mapper.mapSeriesArtwork(
            seriesId = 81189,
            requestedLanguage = "eng",
            posterFallbackImage = " https://art.tvdb.com/fallback-poster.jpg ",
            artworks = listOf(
                artwork(id = 301, type = TvdbArtworkTypes.BACKDROP, image = "https://art.tvdb.com/backdrop.jpg"),
                artwork(id = 302, type = TvdbArtworkTypes.CLEAR_LOGO, image = "https://art.tvdb.com/logo.png")
            )
        )

        val poster = candidates.single { it.imageType == ArtworkType.POSTER }
        assertEquals(100, poster.priority)
        assertTrue(poster.requiresRuntimeFetch)
        assertEquals("tvdb_series_extended_image_fallback", poster.trace.reason)
        assertEquals(
            "https://art.tvdb.com/fallback-poster.jpg",
            (poster.source as ArtworkSource.RemoteUrl).rawUrl.value
        )
    }

    @Test
    fun `uses shared normalized url hash for remote artwork sources`() {
        val candidates = mapper.mapSeriesArtwork(
            seriesId = 81189,
            requestedLanguage = "eng",
            artworks = listOf(
                artwork(
                    id = 300,
                    type = TvdbArtworkTypes.POSTER,
                    image = " HTTPS://ART.TVDB.COM/poster.jpg?utm_source=test&v=1 "
                )
            )
        )

        val source = candidates.single().source as ArtworkSource.RemoteUrl
        assertEquals(
            ArtworkCacheKeys.normalizedUrlHash("https://art.tvdb.com/poster.jpg?v=1"),
            source.normalizedUrlHash
        )
    }

    private fun artwork(
        id: Int?,
        type: Int?,
        image: String?,
        language: String? = "eng",
        score: Double? = 0.0
    ): TvdbArtworkRecord =
        TvdbArtworkRecord(
            id = id,
            type = type,
            image = image,
            language = language,
            score = score
        )
}
