package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.core.metadata.router.resolver.TrailerResolver
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HomeResolvedDisplayMapperTest {
    @Test
    fun `mapper uses final home item and overlay trace without applying overlays again`() {
        val finalItem = preview(
            id = "tmdb:550",
            title = "Final Home Title",
            overview = "Final Home Overview",
            rating = 8.8f,
            artwork = ArtworkBundle(backdrop = artworkRef("backdrop-550"))
        )
        val overlay = overlay(
            itemKey = "movie:tmdb:550",
            fields = HomeDisplayMetadata(
                title = "Overlay Title That Must Not Be Reapplied",
                description = "Overlay Overview That Must Not Be Reapplied",
                imdbRating = 8.8f,
                ratingSource = TitleRatingSource.IMDB,
                posterProviderTag = "top_posters",
                artwork = ArtworkBundle(backdrop = artworkRef("backdrop-550"))
            )
        )

        val resolved = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(finalItem)),
            overlaysByItemKey = mapOf("movie:tmdb:550" to overlay),
            nowMs = 10_000L
        ).single()

        assertEquals("Final Home Title", resolved.display.title)
        assertEquals("Final Home Overview", resolved.display.overview)
        assertEquals("tt0137523", resolved.imdbId)
        assertEquals("550", resolved.stableIds.tmdb)
        assertEquals(8.8, resolved.rating?.value ?: 0.0, 0.0)
        assertNotNull(resolved.artwork.backdrop)
        assertEquals("top_posters", resolved.artwork.backdrop?.trace?.selectedProvider)
        assertEquals(HydrationState.CANONICAL_READY, resolved.hydrationState)
        assertEquals("POSTER", resolved.sourceTrace.single().field)
    }

    @Test
    fun `mapper maps overlay canonical IDs to matching stable ID fields`() {
        val firstPaintStableIds = ProviderIds(
            imdb = "tt-first",
            tmdb = "tmdb-first",
            tvdb = "tvdb-first",
            trakt = "trakt-first",
            simkl = "simkl-first",
            kitsu = "kitsu-first"
        )
        val cases = listOf(
            StableIdCase(ProviderId.TMDB, "550", imdbId = "tt0137523"),
            StableIdCase(ProviderId.TVDB, "81189", imdbId = "tt0903747"),
            StableIdCase(ProviderId.IMDB, "tt1375666", imdbId = null),
            StableIdCase(ProviderId.TRAKT, "12345", imdbId = "tt1856010"),
            StableIdCase(ProviderId.SIMKL, "67890", imdbId = "tt2861424"),
            StableIdCase(ProviderId.KITSU, "7442", imdbId = null)
        )
        val items = cases.map { case ->
            preview(
                id = "source:${case.provider.name.lowercase()}",
                title = "${case.provider.name} Title",
                overview = "Overview",
                rating = null,
                artwork = ArtworkBundle(),
                stableIds = firstPaintStableIds
            )
        }
        val overlays = cases.associate { case ->
            val itemKey = "movie:source:${case.provider.name.lowercase()}"
            itemKey to overlay(
                itemKey = itemKey,
                canonicalProvider = case.provider,
                canonicalId = case.canonicalId,
                imdbId = case.imdbId,
                fields = HomeDisplayMetadata(title = "Overlay title")
            )
        }

        val resolvedByProvider = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(items)),
            overlaysByItemKey = overlays,
            nowMs = 10_000L
        ).associateBy { item -> item.canonicalProvider }

        resolvedByProvider.getValue(ProviderId.TMDB.name).stableIds.also { ids ->
            assertEquals("550", ids.tmdb)
            assertEquals("tt0137523", ids.imdb)
            assertEquals("tvdb-first", ids.tvdb)
        }
        resolvedByProvider.getValue(ProviderId.TVDB.name).stableIds.also { ids ->
            assertEquals("81189", ids.tvdb)
            assertEquals("tt0903747", ids.imdb)
            assertEquals("tmdb-first", ids.tmdb)
        }
        resolvedByProvider.getValue(ProviderId.IMDB.name).stableIds.also { ids ->
            assertEquals("tt1375666", ids.imdb)
            assertEquals("tmdb-first", ids.tmdb)
        }
        resolvedByProvider.getValue(ProviderId.TRAKT.name).stableIds.also { ids ->
            assertEquals("12345", ids.trakt)
            assertEquals("tt1856010", ids.imdb)
            assertEquals("simkl-first", ids.simkl)
        }
        resolvedByProvider.getValue(ProviderId.SIMKL.name).stableIds.also { ids ->
            assertEquals("67890", ids.simkl)
            assertEquals("tt2861424", ids.imdb)
            assertEquals("trakt-first", ids.trakt)
        }
        resolvedByProvider.getValue(ProviderId.KITSU.name).stableIds.also { ids ->
            assertEquals("7442", ids.kitsu)
            assertEquals("tt-first", ids.imdb)
            assertEquals("tmdb-first", ids.tmdb)
        }
    }

    @Test
    fun `mapper does not publish unresolved home trailer fallbacks into display surface trailer state`() {
        val finalItem = preview(
            id = "tmdb:550",
            title = "Final Home Title",
            overview = "Final Home Overview",
            rating = null,
            artwork = ArtworkBundle(),
            trailerYtIds = listOf("trailer-a", "trailer-b")
        )

        val resolved = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(finalItem)),
            overlaysByItemKey = emptyMap(),
            nowMs = 10_000L
        ).single()

        assertEquals(emptyList<String>(), resolved.trailer.fallbackTrailerYtIds)
    }

    @Test
    fun `mapper publishes home trailer state only from resolver selected refs`() {
        val finalItem = preview(
            id = "tmdb:550",
            title = "Final Home Title",
            overview = "Final Home Overview",
            rating = null,
            artwork = ArtworkBundle(),
            trailerYtIds = listOf(" trailer-a ")
        )
        val resolver = TrailerResolver(
            TraceMetadataEvents(RecordingTraceSink(), sessionId = { "s1" })
        )

        val resolved = HomeResolvedDisplayMapper.toResolvedDisplayItems(
            rows = listOf(row(finalItem)),
            overlaysByItemKey = emptyMap(),
            nowMs = 10_000L,
            resolveTrailer = resolver::resolveTrailer
        ).single()

        assertEquals(listOf("trailer-a"), resolved.trailer.fallbackTrailerYtIds)
        assertEquals(TrailerPlaybackRef.YouTubeId("trailer-a"), resolved.trailer.selectedPlaybackRef)
        assertEquals("fallback_youtube_id", resolved.trailer.availabilityReason)
        assertEquals("home", resolved.trailer.surface)
    }

    private fun preview(
        id: String,
        title: String,
        overview: String,
        rating: Float?,
        artwork: ArtworkBundle,
        stableIds: ProviderIds = ProviderIds(),
        trailerYtIds: List<String> = emptyList()
    ) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = "movie",
        name = title,
        poster = "legacy-poster",
        posterShape = PosterShape.POSTER,
        background = "legacy-backdrop",
        logo = null,
        description = overview,
        releaseInfo = "1999",
        runtime = "139m",
        imdbRating = rating,
        ratingSource = TitleRatingSource.IMDB,
        genres = listOf("Drama"),
        trailerYtIds = trailerYtIds,
        artwork = artwork,
        firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
        firstPaintStableIds = stableIds
    )

    private fun row(items: List<MetaPreview>) = CatalogRow(
        addonId = "home",
        addonName = "Home",
        addonBaseUrl = "https://home.example",
        catalogId = "popular",
        catalogName = "Popular",
        type = ContentType.MOVIE,
        items = items,
        hasMore = false
    )

    private fun row(item: MetaPreview) = CatalogRow(
        addonId = "home",
        addonName = "Home",
        addonBaseUrl = "https://home.example",
        catalogId = "popular",
        catalogName = "Popular",
        type = item.type,
        items = listOf(item),
        hasMore = false
    )

    private fun overlay(
        itemKey: String,
        fields: HomeDisplayMetadata,
        canonicalProvider: ProviderId = ProviderId.TMDB,
        canonicalId: String = "550",
        imdbId: String? = "tt0137523"
    ) = HydratedHomeOverlay(
        overlayKey = "canonical:${canonicalProvider.name}:$canonicalId:type:MOVIE:lang:en:policy:1",
        itemKey = itemKey,
        canonicalProvider = canonicalProvider,
        canonicalId = canonicalId,
        imdbId = imdbId,
        contentType = ContentType.MOVIE,
        languageTag = "en",
        fields = fields,
        fieldTrace = listOf(HydratedHomeFieldTrace("POSTER", "TOP_POSTERS", "ARTWORK")),
        displayHash = fields.hydratedHomeDisplayHash(),
        updatedAtMs = 9_000L,
        staleAtMs = 20_000L,
        expiresAtMs = 30_000L
    )

    private fun artworkRef(key: String) = ArtworkDisplayRef.RuntimeAsset(
        decisionKey = ArtworkDecisionKey(key),
        assetKey = null,
        imageType = ArtworkType.BACKDROP,
        selectedProvider = null,
        sourceRole = ArtworkSourceRole.PREMIUM,
        trace = ArtworkTrace(selectedProvider = "top_posters", sourceRole = "ARTWORK")
    )

    private data class StableIdCase(
        val provider: ProviderId,
        val canonicalId: String,
        val imdbId: String?
    )
}
