package com.nexio.tv.domain.model

import com.google.gson.Gson
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeDisplayMetadataTest {

    @Test
    fun `poster string is derived from typed artwork when artwork exists`() {
        val metadata = HomeDisplayMetadata(
            title = "Fight Club",
            poster = "https://image.tmdb.org/raw.jpg",
            artwork = ArtworkBundle(
                poster = ArtworkDisplayRef.RuntimeAsset(
                    decisionKey = ArtworkDecisionKey("decision"),
                    assetKey = ArtworkAssetKey("asset"),
                    imageType = ArtworkType.POSTER,
                    selectedProvider = null,
                    sourceRole = ArtworkSourceRole.PREMIUM,
                    trace = ArtworkTrace.empty()
                )
            )
        )

        assertEquals("nexio-artwork://asset/asset", metadata.displayPoster)
    }

    @Test
    fun `applyTo uses typed artwork projection before raw artwork strings`() {
        val base = MetaPreview(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Movie",
            poster = "basePoster",
            posterShape = PosterShape.POSTER,
            background = "baseBackdrop",
            logo = "baseLogo",
            description = null,
            releaseInfo = null,
            runtime = null,
            imdbRating = null,
            genres = emptyList()
        )
        val metadata = HomeDisplayMetadata(
            poster = "rawPoster",
            backdrop = "rawBackdrop",
            logo = "rawLogo",
            artwork = ArtworkBundle(
                poster = ArtworkDisplayRef.RuntimeAsset(
                    decisionKey = ArtworkDecisionKey("posterDecision"),
                    assetKey = ArtworkAssetKey("posterAsset"),
                    imageType = ArtworkType.POSTER,
                    selectedProvider = null,
                    sourceRole = ArtworkSourceRole.PREMIUM,
                    trace = ArtworkTrace.empty()
                ),
                backdrop = ArtworkDisplayRef.RuntimeAsset(
                    decisionKey = ArtworkDecisionKey("backdropDecision"),
                    assetKey = ArtworkAssetKey("backdropAsset"),
                    imageType = ArtworkType.BACKDROP,
                    selectedProvider = null,
                    sourceRole = ArtworkSourceRole.PRIMARY,
                    trace = ArtworkTrace.empty()
                ),
                logo = ArtworkDisplayRef.RuntimeAsset(
                    decisionKey = ArtworkDecisionKey("logoDecision"),
                    assetKey = ArtworkAssetKey("logoAsset"),
                    imageType = ArtworkType.LOGO,
                    selectedProvider = null,
                    sourceRole = ArtworkSourceRole.PRIMARY,
                    trace = ArtworkTrace.empty()
                )
            )
        )

        val applied = metadata.applyTo(base)

        assertEquals("nexio-artwork://asset/posterAsset", applied.poster)
        assertEquals("nexio-artwork://asset/backdropAsset", applied.background)
        assertEquals("nexio-artwork://asset/logoAsset", applied.logo)
    }

    @Test
    fun `mergeFallback preserves primary artwork bundle`() {
        val artwork = ArtworkBundle(
            poster = ArtworkDisplayRef.RuntimeAsset(
                decisionKey = ArtworkDecisionKey("decision"),
                assetKey = ArtworkAssetKey("asset"),
                imageType = ArtworkType.POSTER,
                selectedProvider = null,
                sourceRole = ArtworkSourceRole.PRIMARY,
                trace = ArtworkTrace.empty()
            )
        )

        val merged = HomeDisplayMetadata(artwork = artwork).mergeFallback(
            HomeDisplayMetadata(poster = "fallbackPoster")
        )

        assertEquals(artwork, merged.artwork)
        assertEquals("nexio-artwork://asset/asset", merged.displayPoster)
    }

    @Test
    fun `mergeFallback does not let fallback typed artwork override primary raw poster`() {
        val fallback = HomeDisplayMetadata(
            artwork = ArtworkBundle(
                poster = ArtworkDisplayRef.RuntimeAsset(
                    decisionKey = ArtworkDecisionKey("fallbackDecision"),
                    assetKey = ArtworkAssetKey("fallbackAsset"),
                    imageType = ArtworkType.POSTER,
                    selectedProvider = null,
                    sourceRole = ArtworkSourceRole.PRIMARY,
                    trace = ArtworkTrace.empty()
                )
            )
        )

        val merged = HomeDisplayMetadata(poster = "primaryPoster").mergeFallback(fallback)

        assertEquals("primaryPoster", merged.displayPoster)
    }

    @Test
    fun `mergeFallback does not attach fallback poster provider tag to primary raw poster`() {
        val merged = HomeDisplayMetadata(
            poster = "primaryPoster",
            posterProviderTag = null
        ).mergeFallback(
            HomeDisplayMetadata(
                poster = "premiumPoster",
                posterProviderTag = "rpdb"
            )
        )
        val applied = merged.applyTo(
            metaPreview().copy(
                poster = "oldPremiumPoster",
                posterProviderTag = "rpdb"
            )
        )

        assertEquals("primaryPoster", merged.displayPoster)
        assertNull(merged.posterProviderTag)
        assertEquals("primaryPoster", applied.poster)
        assertNull(applied.posterProviderTag)
    }

    @Test
    fun `toHomeDisplayMetadata and applyTo preserve tomatoes rating`() {
        val preview = MetaPreview(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Movie",
            poster = "poster",
            posterShape = PosterShape.POSTER,
            background = "background",
            logo = "logo",
            description = "description",
            releaseInfo = "2025",
            runtime = "120",
            imdbRating = 8.3f,
            tomatoesRating = 93.0,
            genres = listOf("Drama")
        )

        val displayMetadata = preview.toHomeDisplayMetadata()
        val roundTripped = displayMetadata.applyTo(
            preview.copy(tomatoesRating = null)
        )

        assertEquals(93.0, displayMetadata.tomatoesRating ?: 0.0, 0.0)
        assertEquals(93.0, roundTripped.tomatoesRating ?: 0.0, 0.0)
    }

    @Test
    fun `toHomeDisplayMetadata and applyTo preserve poster provider tag`() {
        val preview = MetaPreview(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Movie",
            poster = "https://api.ratingposterdb.com/key/imdb/poster-default/tt123.jpg",
            posterShape = PosterShape.POSTER,
            background = "background",
            logo = "logo",
            description = "description",
            releaseInfo = "2025",
            runtime = "120",
            imdbRating = 8.3f,
            tomatoesRating = 93.0,
            genres = listOf("Drama"),
            posterProviderTag = "rpdb"
        )

        val displayMetadata = preview.toHomeDisplayMetadata()
        val roundTripped = displayMetadata.applyTo(
            preview.copy(posterProviderTag = null)
        )

        assertEquals("rpdb", displayMetadata.posterProviderTag)
        assertEquals("rpdb", roundTripped.posterProviderTag)
    }

    @Test
    fun `applyTo clears stale poster provider tag when metadata supplies raw poster without tag`() {
        val preview = MetaPreview(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Movie",
            poster = "premiumPoster",
            posterShape = PosterShape.POSTER,
            background = "background",
            logo = "logo",
            description = "description",
            releaseInfo = "2025",
            runtime = "120",
            imdbRating = 8.3f,
            genres = listOf("Drama"),
            posterProviderTag = "rpdb"
        )

        val updated = HomeDisplayMetadata(
            poster = "primaryPoster",
            posterProviderTag = null
        ).applyTo(preview)

        assertEquals("primaryPoster", updated.poster)
        assertNull(updated.posterProviderTag)
    }

    @Test
    fun `applyTo preserves base poster provider tag when metadata supplies no display poster`() {
        val preview = MetaPreview(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Movie",
            poster = "premiumPoster",
            posterShape = PosterShape.POSTER,
            background = "background",
            logo = "logo",
            description = "description",
            releaseInfo = "2025",
            imdbRating = 8.3f,
            genres = listOf("Drama"),
            posterProviderTag = "rpdb"
        )

        val updated = HomeDisplayMetadata(
            title = "Updated title",
            posterProviderTag = null
        ).applyTo(preview)

        assertEquals("premiumPoster", updated.poster)
        assertEquals("rpdb", updated.posterProviderTag)
    }

    @Test
    fun `toHomeDisplayMetadata and applyTo preserve rating source`() {
        val preview = MetaPreview(
            id = "tmdb:1399",
            type = ContentType.SERIES,
            name = "Game of Thrones",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2011",
            imdbRating = 8.9f,
            ratingSource = TitleRatingSource.TMDB,
            genres = emptyList()
        )

        val displayMetadata = preview.toHomeDisplayMetadata()
        val roundTripped = displayMetadata.applyTo(
            preview.copy(imdbRating = null, ratingSource = TitleRatingSource.IMDB)
        )

        assertEquals(TitleRatingSource.TMDB, displayMetadata.ratingSource)
        assertEquals(TitleRatingSource.TMDB, roundTripped.ratingSource)
    }

    @Test
    fun `Meta toHomeDisplayMetadata carries typed artwork and displayPoster prefers projection`() {
        val artwork = ArtworkBundle(
            poster = artworkRef("metaPosterDecision", "metaPosterAsset", ArtworkType.POSTER)
        )
        val meta = meta(artwork = artwork)

        val metadata = meta.toHomeDisplayMetadata()

        assertEquals(artwork, metadata.artwork)
        assertEquals("nexio-artwork://asset/metaPosterAsset", meta.displayPoster)
        assertEquals("nexio-artwork://asset/metaPosterAsset", metadata.displayPoster)
        assertEquals("legacyPoster", metadata.poster)
    }

    @Test
    fun `MetaPreview toHomeDisplayMetadata carries typed artwork and display fields prefer projection`() {
        val artwork = ArtworkBundle(
            poster = artworkRef("previewPosterDecision", "previewPosterAsset", ArtworkType.POSTER),
            backdrop = artworkRef("previewBackdropDecision", "previewBackdropAsset", ArtworkType.BACKDROP),
            logo = artworkRef("previewLogoDecision", "previewLogoAsset", ArtworkType.LOGO)
        )
        val preview = metaPreview(artwork = artwork)

        val metadata = preview.toHomeDisplayMetadata()

        assertEquals(artwork, metadata.artwork)
        assertEquals("nexio-artwork://asset/previewPosterAsset", preview.displayPoster)
        assertEquals("nexio-artwork://asset/previewBackdropAsset", preview.displayBackground)
        assertEquals("nexio-artwork://asset/previewLogoAsset", preview.displayLogo)
        assertEquals("nexio-artwork://asset/previewPosterAsset", metadata.displayPoster)
        assertEquals("nexio-artwork://asset/previewBackdropAsset", metadata.displayBackdrop)
        assertEquals("nexio-artwork://asset/previewLogoAsset", metadata.displayLogo)
    }

    @Test
    fun `HomeDisplayMetadata applyTo propagates typed artwork and legacy strings become internal refs`() {
        val artwork = ArtworkBundle(
            poster = artworkRef("applyPosterDecision", "applyPosterAsset", ArtworkType.POSTER),
            backdrop = artworkRef("applyBackdropDecision", "applyBackdropAsset", ArtworkType.BACKDROP),
            logo = artworkRef("applyLogoDecision", "applyLogoAsset", ArtworkType.LOGO)
        )
        val base = metaPreview()

        val applied = HomeDisplayMetadata(
            poster = "rawPoster",
            backdrop = "rawBackdrop",
            logo = "rawLogo",
            artwork = artwork
        ).applyTo(base)

        assertEquals(artwork, applied.artwork)
        assertEquals("nexio-artwork://asset/applyPosterAsset", applied.poster)
        assertEquals("nexio-artwork://asset/applyBackdropAsset", applied.background)
        assertEquals("nexio-artwork://asset/applyLogoAsset", applied.logo)
        assertEquals("nexio-artwork://asset/applyPosterAsset", applied.displayPoster)
    }

    @Test
    fun `HomeDisplayMetadata applyTo does not let base fallback typed artwork override incoming raw poster`() {
        val fallbackArtwork = ArtworkBundle(
            poster = artworkRef("fallbackPosterDecision", "fallbackPosterAsset", ArtworkType.POSTER)
        )
        val base = metaPreview(artwork = fallbackArtwork)

        val applied = HomeDisplayMetadata(
            poster = "primaryRawPoster"
        ).applyTo(base)

        assertNull(applied.artwork?.poster)
        assertEquals("primaryRawPoster", applied.poster)
        assertEquals("primaryRawPoster", applied.displayPoster)
    }

    @Test
    fun `MetaPreview equality includes typed artwork safely`() {
        val first = metaPreview(
            artwork = ArtworkBundle(
                poster = artworkRef("firstDecision", "firstAsset", ArtworkType.POSTER)
            )
        )
        val second = first.copy(
            artwork = ArtworkBundle(
                poster = artworkRef("secondDecision", "secondAsset", ArtworkType.POSTER)
            )
        )

        first.hashCode()
        second.hashCode()

        assertNotEquals(first, second)
        assertNotEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `Video displayThumbnail prefers typed thumbnail projection`() {
        val video = Video(
            id = "episode-1",
            title = "Episode 1",
            released = null,
            thumbnail = "legacyThumbnail",
            season = 1,
            episode = 1,
            overview = null,
            thumbnailArtwork = artworkRef("thumbnailDecision", "thumbnailAsset", ArtworkType.THUMBNAIL)
        )

        assertEquals("nexio-artwork://asset/thumbnailAsset", video.displayThumbnail)
    }

    @Test
    fun `Video reports whether thumbnail artwork embeds rating overlay`() {
        val video = Video(
            id = "episode-1",
            title = "Episode 1",
            released = null,
            thumbnail = "legacyThumbnail",
            season = 1,
            episode = 1,
            overview = null,
            thumbnailArtwork = artworkRef(
                decisionKey = "thumbnailDecision",
                assetKey = "thumbnailAsset",
                imageType = ArtworkType.THUMBNAIL,
                displayHints = ArtworkDisplayHints(embedsRatingOverlay = true)
            )
        )

        assertEquals(true, video.thumbnailArtworkEmbedsRatingOverlay)
    }

    @Test
    fun `Video reports no rating overlay when thumbnail artwork is absent or default`() {
        val missingArtwork = Video(
            id = "episode-1",
            title = "Episode 1",
            released = null,
            thumbnail = "legacyThumbnail",
            season = 1,
            episode = 1,
            overview = null,
            thumbnailArtwork = null
        )
        val defaultArtwork = missingArtwork.copy(
            thumbnailArtwork = artworkRef("thumbnailDecision", "thumbnailAsset", ArtworkType.THUMBNAIL)
        )

        assertEquals(false, missingArtwork.thumbnailArtworkEmbedsRatingOverlay)
        assertEquals(false, defaultArtwork.thumbnailArtworkEmbedsRatingOverlay)
    }

    @Test
    fun `Gson persistence ignores transient typed artwork on metadata models`() {
        val gson = Gson()
        val artwork = ArtworkBundle(
            poster = artworkRef("persistDecision", "persistAsset", ArtworkType.POSTER)
        )
        val displayMetadata = HomeDisplayMetadata(
            poster = "nexio-artwork://asset/persistAsset",
            artwork = artwork
        )
        val video = Video(
            id = "episode-1",
            title = "Episode 1",
            released = null,
            thumbnail = "legacyThumbnail",
            season = 1,
            episode = 1,
            overview = null,
            thumbnailArtwork = artworkRef("thumbnailDecision", "thumbnailAsset", ArtworkType.THUMBNAIL)
        )

        val metaJson = gson.toJson(meta(artwork = artwork).copy(videos = listOf(video)))
        val previewJson = gson.toJson(metaPreview(artwork = artwork))
        val displayMetadataJson = gson.toJson(displayMetadata)

        assertFalse(metaJson.contains("\"artwork\""))
        assertFalse(metaJson.contains("\"thumbnailArtwork\""))
        assertFalse(previewJson.contains("\"artwork\""))
        assertFalse(displayMetadataJson.contains("\"artwork\""))
        assertNull(gson.fromJson(metaJson, Meta::class.java).artwork)
        assertNull(gson.fromJson(metaJson, Meta::class.java).videos.first().thumbnailArtwork)
        assertNull(gson.fromJson(previewJson, MetaPreview::class.java).artwork)
        assertNull(gson.fromJson(displayMetadataJson, HomeDisplayMetadata::class.java).artwork)
        assertEquals(
            "nexio-artwork://asset/persistAsset",
            gson.fromJson(displayMetadataJson, HomeDisplayMetadata::class.java).displayPoster
        )
    }

    @Test
    fun `legacy preview without rating source can be hashed and converted`() {
        val preview = Gson().fromJson(
            """
            {
              "id": "tt123",
              "type": "MOVIE",
              "rawType": "movie",
              "name": "Movie",
              "poster": null,
              "posterShape": "POSTER",
              "background": null,
              "logo": null,
              "description": null,
              "releaseInfo": "2025",
              "runtime": null,
              "imdbRating": 8.3,
              "tomatoesRating": null,
              "genres": [],
              "trailerYtIds": [],
              "language": null,
              "posterProviderTag": null
            }
            """.trimIndent(),
            MetaPreview::class.java
        )

        preview.hashCode()
        val displayMetadata = preview.toHomeDisplayMetadata()

        assertEquals(TitleRatingSource.IMDB, displayMetadata.ratingSource)
    }

    private fun artworkRef(
        decisionKey: String,
        assetKey: String,
        imageType: ArtworkType,
        displayHints: ArtworkDisplayHints = ArtworkDisplayHints()
    ): ArtworkDisplayRef.RuntimeAsset {
        return ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey(decisionKey),
            assetKey = ArtworkAssetKey(assetKey),
            imageType = imageType,
            selectedProvider = null,
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty(),
            displayHints = displayHints
        )
    }

    private fun metaPreview(artwork: ArtworkBundle? = null): MetaPreview {
        return MetaPreview(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Movie",
            poster = "legacyPoster",
            posterShape = PosterShape.POSTER,
            background = "legacyBackdrop",
            logo = "legacyLogo",
            description = "description",
            releaseInfo = "2025",
            runtime = "120",
            imdbRating = 8.3f,
            genres = listOf("Drama"),
            artwork = artwork
        )
    }

    private fun meta(artwork: ArtworkBundle? = null): Meta {
        return Meta(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Movie",
            poster = "legacyPoster",
            posterShape = PosterShape.POSTER,
            background = "legacyBackdrop",
            logo = "legacyLogo",
            description = "description",
            releaseInfo = "2025",
            imdbRating = 8.3f,
            genres = listOf("Drama"),
            runtime = "120",
            director = listOf("Director"),
            cast = listOf("Actor"),
            videos = emptyList(),
            country = "US",
            awards = null,
            language = "en",
            links = emptyList(),
            artwork = artwork
        )
    }
}
