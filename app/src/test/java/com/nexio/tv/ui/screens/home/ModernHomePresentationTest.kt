package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.toArtworkBundleFromDisplayFields
import com.nexio.tv.ui.components.ContinueWatchingResolvedDisplayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernHomePresentationTest {
    @Test
    fun `builds continue watching before catalog rows`() {
        val cache = ModernCarouselRowBuildCache()
        val popularRow = catalogRow("popular", "Popular", ContentType.MOVIE, listOf(meta("movie-1")))
        val state = buildModernHomePresentation(
            input = presentationInput(
                catalogRows = listOf(popularRow),
                continueWatchingItems = listOf(resolvedCwInProgress(inProgress("tt-cw-1", "Resume Me")))
            ),
            cache = cache
        )

        assertEquals(listOf("continue_watching", "addon_movie_popular"), state.rows.map { it.key })
        assertEquals("Continue watching", state.rows[0].title)
        assertEquals("Popular - Movie", state.rows[1].title)
        assertEquals(setOf("continue_watching", "addon_movie_popular"), state.lookups.activeRowKeys)
        assertTrue(state.lookups.activeCatalogItemIds.contains("movie-1"))
    }

    @Test
    fun `reuses cached row when catalog input is unchanged`() {
        val cache = ModernCarouselRowBuildCache()
        val row = catalogRow("popular", "Popular", ContentType.MOVIE, listOf(meta("movie-1")))
        val input = presentationInput(catalogRows = listOf(row))

        val first = buildModernHomePresentation(input, cache)
        val second = buildModernHomePresentation(input, cache)

        assertSame(first.rows.single(), second.rows.single())
        assertSame(first.rows.single().items.single(), second.rows.single().items.single())
    }

    @Test
    fun `removes stale catalog cache entries when row disappears`() {
        val cache = ModernCarouselRowBuildCache()
        buildModernHomePresentation(
            input = presentationInput(
                catalogRows = listOf(catalogRow("popular", "Popular", ContentType.MOVIE, listOf(meta("movie-1"))))
            ),
            cache = cache
        )
        buildModernHomePresentation(
            input = presentationInput(catalogRows = emptyList()),
            cache = cache
        )

        assertTrue(cache.catalogRows.isEmpty())
        assertTrue(cache.catalogItemCache.isEmpty())
    }

    @Test
    fun `deduplicates visible catalog rows by stable row key`() {
        val cache = ModernCarouselRowBuildCache()
        val state = buildModernHomePresentation(
            input = presentationInput(
                catalogRows = listOf(
                    catalogRow("trakt_trending_movies", "Trakt Trending Movies", ContentType.MOVIE, listOf(meta("movie-1"))),
                    catalogRow("trakt_trending_movies", "Trakt Trending Movies Duplicate", ContentType.MOVIE, listOf(meta("movie-2")))
                )
            ),
            cache = cache
        )

        assertEquals(listOf("addon_movie_trakt_trending_movies"), state.rows.map { it.key })
        assertEquals(setOf("addon_movie_trakt_trending_movies"), state.lookups.activeRowKeys)
    }

    @Test
    fun `continue watching meta preview preserves hydrated artwork strings as artwork refs`() {
        val metadata = HomeDisplayMetadata(
            title = "The Boys",
            poster = "nexio-artwork://decision/artwork-decision:poster:canonical:tmdb:series-76479:provider:RPDB:premium:true:policy:1",
            posterProviderTag = "rpdb",
            backdrop = "nexio-artwork://asset/artwork-asset:TVDB:backdrop:urlHash:abc:variant:none:imageLang:en:policy:1",
            logo = "nexio-artwork://asset/artwork-asset:TVDB:logo:urlHash:def:variant:none:imageLang:en:policy:1"
        )
        val item = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt1190634",
                contentType = "series",
                name = "The Boys",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tt1190634:1:1",
                season = 1,
                episode = 1,
                episodeTitle = null,
                position = 1,
                duration = 10,
                lastWatched = 100,
                progressPercent = 10f
            ),
            displayMetadata = metadata
        )

        val preview = continueWatchingInProgressToMetaPreview(item)

        assertTrue(preview.poster.orEmpty().contains("provider:RPDB"))
        assertEquals("rpdb", preview.posterProviderTag)
        assertNotNull(preview.artwork?.poster)
        assertNotNull(preview.artwork?.backdrop)
        assertNotNull(preview.artwork?.logo)
        assertEquals("tt1190634", preview.firstPaintStableIds.imdb)

        val poster = preview.artwork?.poster
        assertTrue(poster is ArtworkDisplayRef.LegacyString)
        poster as ArtworkDisplayRef.LegacyString
        assertEquals(ArtworkType.POSTER, poster.imageType)
        assertTrue(poster.value.contains("provider:RPDB"))

        val backdrop = preview.artwork?.backdrop
        assertTrue(backdrop is ArtworkDisplayRef.LegacyString)
        backdrop as ArtworkDisplayRef.LegacyString
        assertEquals(ArtworkType.BACKDROP, backdrop.imageType)

        val logo = preview.artwork?.logo
        assertTrue(logo is ArtworkDisplayRef.LegacyString)
        logo as ArtworkDisplayRef.LegacyString
        assertEquals(ArtworkType.LOGO, logo.imageType)
    }

    @Test
    fun `provider ids derived from mixed case tmdb tv content id still extract numeric id`() {
        val ids = providerIdsFromContinueWatchingContentId("TMDB:TV:76479")
        assertEquals("76479", ids.tmdb)
        assertNull(ids.tvdb)
        assertNull(ids.imdb)
    }

    @Test
    fun `artwork reconstruction rejects raw premium urls`() {
        val metadata = HomeDisplayMetadata(
            poster = "https://api.top-posters.example/poster/foo",
            backdrop = "https://api.ratingposterdb.com/backdrop/foo",
            logo = "nexio-artwork://asset/artwork-asset:TVDB:logo:urlHash:def:variant:none:imageLang:en:policy:1"
        )

        val bundle = metadata.toArtworkBundleFromDisplayFields()

        assertNull(bundle?.poster)
        assertNull(bundle?.backdrop)
        assertNotNull(bundle?.logo)
    }

    @Test
    fun `artwork reconstruction accepts durable nexio decision and asset refs`() {
        val metadata = HomeDisplayMetadata(
            poster = "nexio-artwork://decision/artwork-decision:poster:canonical:tmdb:series-76479:provider:RPDB:premium:true:policy:1",
            backdrop = "nexio-artwork://asset/artwork-asset:TVDB:backdrop:urlHash:abc:variant:none:imageLang:en:policy:1"
        )

        val bundle = metadata.toArtworkBundleFromDisplayFields()

        assertNotNull(bundle?.poster)
        assertNotNull(bundle?.backdrop)

        val poster = bundle?.poster
        assertTrue(poster is ArtworkDisplayRef.LegacyString)
        poster as ArtworkDisplayRef.LegacyString
        assertEquals(ArtworkType.POSTER, poster.imageType)
        assertTrue(poster.value.contains("provider:RPDB"))

        val backdrop = bundle?.backdrop
        assertTrue(backdrop is ArtworkDisplayRef.LegacyString)
        backdrop as ArtworkDisplayRef.LegacyString
        assertEquals(ArtworkType.BACKDROP, backdrop.imageType)
    }

    @Test
    fun `portrait continue watching card ignores backdrop when poster is missing`() {
        val metadata = HomeDisplayMetadata(
            title = "The Boys",
            poster = null,
            backdrop = "nexio-artwork://asset/artwork-asset:TVDB:backdrop:urlHash:abc:variant:none:imageLang:en:policy:1",
            logo = "nexio-artwork://asset/artwork-asset:TVDB:logo:urlHash:def:variant:none:imageLang:en:policy:1"
        )
        val item = ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "tt1190634",
                contentType = "series",
                name = "The Boys",
                poster = null,
                backdrop = metadata.displayBackdrop,
                logo = metadata.displayLogo,
                displayMetadata = metadata,
                videoId = "tt1190634:1:1",
                season = 1,
                episode = 1,
                episodeTitle = null,
                episodeDescription = null,
                thumbnail = null,
                released = null,
                hasAired = true,
                airDateLabel = null,
                lastWatched = 100,
                imdbRating = null,
                genres = emptyList(),
                releaseInfo = null
            )
        )

        val carousel = buildContinueWatchingItem(
            resolved = resolvedCwNextUp(item),
            useLandscapePosters = false,
            airsDateTemplate = "%s",
            upcomingLabel = "upcoming"
        )

        assertNull(carousel.imageUrl)
    }

    private fun catalogRow(
        catalogId: String,
        catalogName: String,
        type: ContentType,
        items: List<MetaPreview>
    ): CatalogRow = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://addon.example",
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        items = items
    )

    /**
     * Builds a [ModernHomePresentationInput] with synthetic resolved rails matching the
     * provided catalog rows. The synthetic resolved items mirror MetaPreview titles so
     * the rendered HeroCarouselRow keeps the same shape as the legacy MetaPreview-driven
     * pipeline (Plan B Task 4 migration).
     */
    private fun presentationInput(
        catalogRows: List<CatalogRow>,
        continueWatchingItems: List<ContinueWatchingResolvedDisplayItem> = emptyList(),
        useLandscapePosters: Boolean = false,
        showCatalogTypeSuffix: Boolean = true,
        continueWatchingTitle: String = "Continue watching",
        airsDateTemplate: String = "Airs %s",
        upcomingLabel: String = "Upcoming"
    ): ModernHomePresentationInput {
        // Mirror catalog rows into resolved rails. Deduplicate on catalogId to match the
        // visible-rows behavior that runs upstream of the resolved publisher in production.
        val resolvedByCatalogId = LinkedHashMap<String, ResolvedRailRow>()
        catalogRows.forEach { row ->
            if (resolvedByCatalogId.containsKey(row.catalogId)) return@forEach
            val resolvedItems = row.items.map { meta ->
                ModernHomeRowItem.from(syntheticResolvedDisplayItem(meta))
            }
            resolvedByCatalogId[row.catalogId] = ResolvedRailRow(
                catalogId = row.catalogId,
                title = row.catalogName,
                items = resolvedItems
            )
        }
        // Plan B Task 5e-pre — the surface-level MetaPreview lookup is now an
        // explicit input to the build function (produced by HomeViewModel at the
        // same site that publishes _displayCatalogRows). Mirror that behavior here
        // by building the map from the synthetic catalog rows the test supplies.
        val metaByItemKey = HashMap<String, MetaPreview>()
        catalogRows.forEach { row ->
            row.items.forEach { meta ->
                metaByItemKey[homeDisplayItemKey(meta.apiType, meta.id)] = meta
            }
        }
        return ModernHomePresentationInput(
            catalogRows = catalogRows,
            resolvedRailRows = resolvedByCatalogId.values.toList(),
            continueWatchingItems = continueWatchingItems,
            metaByItemKey = metaByItemKey,
            useLandscapePosters = useLandscapePosters,
            showCatalogTypeSuffix = showCatalogTypeSuffix,
            continueWatchingTitle = continueWatchingTitle,
            airsDateTemplate = airsDateTemplate,
            upcomingLabel = upcomingLabel
        )
    }

    private fun syntheticResolvedDisplayItem(meta: MetaPreview): ResolvedDisplayItem {
        val itemKey = homeDisplayItemKey(meta.apiType, meta.id)
        return ResolvedDisplayItem(
            itemKey = itemKey,
            contentId = meta.id,
            parentId = meta.id,
            itemType = meta.type,
            mediaKind = MetadataMediaKind.UNKNOWN,
            canonicalProvider = null,
            canonicalId = null,
            imdbId = null,
            stableIds = ProviderIds(),
            display = ResolvedDisplayFields(
                title = meta.name,
                originalTitle = null,
                year = null,
                releaseDate = null,
                overview = meta.description,
                genres = meta.genres,
                runtimeText = null
            ),
            artwork = ArtworkBundle(
                poster = meta.poster?.takeIf { it.isNotBlank() }
                    ?.let { ArtworkDisplayRef.LegacyString(it, ArtworkType.POSTER, ArtworkTrace.empty()) },
                backdrop = meta.background?.takeIf { it.isNotBlank() }
                    ?.let { ArtworkDisplayRef.LegacyString(it, ArtworkType.BACKDROP, ArtworkTrace.empty()) },
                logo = meta.logo?.takeIf { it.isNotBlank() }
                    ?.let { ArtworkDisplayRef.LegacyString(it, ArtworkType.LOGO, ArtworkTrace.empty()) }
            ),
            rating = null,
            trailer = TrailerDisplayState(),
            hydrationState = HydrationState.PREVIEW_ONLY,
            sourceTrace = emptyList(),
            updatedAtMs = 0L,
            slots = null
        )
    }

    private fun meta(id: String): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = "Title $id",
        poster = "https://img.example/$id.jpg",
        posterShape = PosterShape.POSTER,
        background = "https://img.example/$id-bg.jpg",
        logo = null,
        description = "Description $id",
        releaseInfo = "2026",
        imdbRating = null,
        genres = emptyList(),
        posterProviderTag = null
    )

    private fun resolvedCwInProgress(item: ContinueWatchingItem.InProgress): ContinueWatchingResolvedDisplayItem.InProgress {
        val display = item.displayMetadata()
        return ContinueWatchingResolvedDisplayItem.fromInProgress(
            resolved = resolvedDisplayItemForCw(
                contentId = item.progress.contentId,
                contentType = item.progress.contentType,
                title = display.title ?: item.progress.name,
                posterUrl = display.displayPoster,
                backdropUrl = display.displayBackdrop,
                logoUrl = display.displayLogo
            ),
            source = item
        )
    }

    private fun resolvedCwNextUp(item: ContinueWatchingItem.NextUp): ContinueWatchingResolvedDisplayItem.NextUp {
        val display = item.displayMetadata()
        return ContinueWatchingResolvedDisplayItem.fromNextUp(
            resolved = resolvedDisplayItemForCw(
                contentId = item.info.contentId,
                contentType = item.info.contentType,
                title = display.title ?: item.info.name,
                posterUrl = display.displayPoster,
                backdropUrl = display.displayBackdrop,
                logoUrl = display.displayLogo
            ),
            source = item
        )
    }

    private fun resolvedDisplayItemForCw(
        contentId: String,
        contentType: String,
        title: String,
        posterUrl: String?,
        backdropUrl: String?,
        logoUrl: String?
    ): ResolvedDisplayItem {
        val itemKey = homeDisplayItemKey(contentType, contentId)
        val resolvedType = if (contentType.equals("series", ignoreCase = true)) {
            ContentType.SERIES
        } else {
            ContentType.MOVIE
        }
        return ResolvedDisplayItem(
            itemKey = itemKey,
            contentId = contentId,
            parentId = contentId,
            itemType = resolvedType,
            mediaKind = MetadataMediaKind.UNKNOWN,
            canonicalProvider = null,
            canonicalId = null,
            imdbId = null,
            stableIds = ProviderIds(),
            display = ResolvedDisplayFields(
                title = title,
                originalTitle = null,
                year = null,
                releaseDate = null,
                overview = null,
                genres = emptyList(),
                runtimeText = null
            ),
            artwork = ArtworkBundle(
                poster = posterUrl?.takeIf { it.isNotBlank() }
                    ?.let { ArtworkDisplayRef.LegacyString(it, ArtworkType.POSTER, ArtworkTrace.empty()) },
                backdrop = backdropUrl?.takeIf { it.isNotBlank() }
                    ?.let { ArtworkDisplayRef.LegacyString(it, ArtworkType.BACKDROP, ArtworkTrace.empty()) },
                logo = logoUrl?.takeIf { it.isNotBlank() }
                    ?.let { ArtworkDisplayRef.LegacyString(it, ArtworkType.LOGO, ArtworkTrace.empty()) }
            ),
            rating = null,
            trailer = TrailerDisplayState(),
            hydrationState = HydrationState.PREVIEW_ONLY,
            sourceTrace = emptyList(),
            updatedAtMs = 0L,
            slots = null
        )
    }

    private fun inProgress(id: String, name: String): ContinueWatchingItem.InProgress =
        ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = id,
                contentType = "series",
                name = name,
                poster = "https://img.example/$id.jpg",
                backdrop = "https://img.example/$id-bg.jpg",
                logo = null,
                videoId = "$id:1:1",
                season = 1,
                episode = 1,
                episodeTitle = "Episode 1",
                position = 10_000L,
                duration = 100_000L,
                lastWatched = 1_000L,
                progressPercent = 10f
            ),
            displayMetadata = HomeDisplayMetadata(title = name)
        )
}
