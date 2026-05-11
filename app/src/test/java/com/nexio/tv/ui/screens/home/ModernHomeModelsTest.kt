package com.nexio.tv.ui.screens.home

import android.view.KeyEvent
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.CatalogRow
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
import com.nexio.tv.ui.components.ContinueWatchingResolvedDisplayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernHomeModelsTest {

    @Test
    fun `buildContinueWatchingItem prefers shared display metadata for in progress items`() {
        val item = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt123",
                contentType = "movie",
                name = "Raw Title",
                poster = "rawPoster",
                backdrop = "rawBackdrop",
                logo = "rawLogo",
                videoId = "tt123",
                season = null,
                episode = null,
                episodeTitle = null,
                position = 100L,
                duration = 1_000L,
                lastWatched = 1L
            ),
            displayMetadata = HomeDisplayMetadata(
                title = "Display Title",
                logo = "displayLogo",
                description = "Display Description",
                genres = listOf("Drama"),
                releaseInfo = "2025",
                imdbRating = 8.5f,
                tomatoesRating = 96.0,
                poster = "displayPoster",
                backdrop = "displayBackdrop"
            )
        )

        val built = buildContinueWatchingItem(
            resolved = resolvedCwInProgress(item),
            useLandscapePosters = true,
            airsDateTemplate = "Airs %s",
            upcomingLabel = "Upcoming"
        )

        assertEquals("Display Title", built.title)
        assertEquals("Display Title", built.heroPreview.title)
        assertEquals("displayLogo", built.heroPreview.logo)
        assertEquals("Display Description", built.heroPreview.description)
        assertEquals("8.5", built.heroPreview.imdbText)
        assertEquals("96", built.heroPreview.tomatoesText)
        assertEquals("displayBackdrop", built.imageUrl)
        assertEquals("tt123", built.metaPreview?.id)
        assertEquals(96.0, built.metaPreview?.tomatoesRating ?: 0.0, 0.0)
        assertTrue(built.heroPreview.genres.contains("Drama"))
    }

    @Test
    fun `nextUpToMetaPreview uses persisted display metadata`() {
        val nextUp = ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "tt456",
                contentType = "series",
                name = "Raw Show",
                poster = "rawPoster",
                backdrop = "rawBackdrop",
                logo = "rawLogo",
                displayMetadata = HomeDisplayMetadata(
                    title = "Localized Show",
                    logo = "displayLogo",
                    description = "Localized overview",
                    genres = listOf("Comedy"),
                    releaseInfo = "2024",
                    imdbRating = 8.2f,
                    tomatoesRating = 91.0,
                    poster = "displayPoster",
                    backdrop = "displayBackdrop"
                ),
                videoId = "tt456:1:2",
                season = 1,
                episode = 2,
                episodeTitle = "Episode 2",
                thumbnail = null,
                lastWatched = 1L
            )
        )

        val preview = nextUpToMetaPreview(nextUp)

        assertTrue(preview.name.startsWith("Localized Show"))
        assertEquals("displayPoster", preview.poster)
        assertEquals("displayBackdrop", preview.background)
        assertEquals("displayLogo", preview.logo)
        assertEquals("Localized overview", preview.description)
        assertEquals("2024", preview.releaseInfo)
        assertEquals(8.2f, preview.imdbRating)
        assertEquals(91.0, preview.tomatoesRating ?: 0.0, 0.0)
        assertEquals(listOf("Comedy"), preview.genres)
        assertEquals(ContentType.SERIES, preview.type)
    }

    @Test
    fun `heroPreviewContentKey changes when tomatoes text changes for the active item`() {
        val withoutTomatoes = buildModernCarouselItem(tomatoesText = null)
        val withTomatoes = buildModernCarouselItem(tomatoesText = "88")

        assertNotEquals(
            heroPreviewContentKey(withoutTomatoes),
            heroPreviewContentKey(withTomatoes)
        )
    }

    @Test
    fun `resolveDisplayedHeroPreview prefers live preview updates for the same focused item`() {
        val stalePreview = buildModernCarouselItem(tomatoesText = null).heroPreview
        val livePreview = buildModernCarouselItem(tomatoesText = "88").heroPreview

        val resolved = resolveDisplayedHeroPreview(
            displayedHeroItemKey = "item_1",
            activeHeroItemKey = "item_1",
            displayedHeroPreview = stalePreview,
            liveActiveHeroPreview = livePreview
        )

        assertEquals("88", resolved?.tomatoesText)
    }

    @Test
    fun `buildCatalogItem carries frozen artwork from previous cached item`() {
        val row = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "catalog",
            catalogName = "Catalog",
            type = ContentType.MOVIE,
            items = emptyList()
        )
        val original = MetaPreview(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Original",
            poster = "poster",
            posterShape = PosterShape.POSTER,
            background = "original-backdrop",
            logo = "original-logo",
            description = null,
            releaseInfo = "2025",
            runtime = null,
            imdbRating = null,
            genres = emptyList()
        )
        val originalResolved = resolvedRowItem(
            meta = original,
            backdropUrl = "original-backdrop",
            logoUrl = "original-logo",
            posterUrl = "poster"
        )
        val cached = buildCatalogItem(
            resolved = originalResolved,
            metaPreview = original,
            row = row,
            useLandscapePosters = true,
            occurrence = 0
        )
        val enrichedResolved = resolvedRowItem(
            meta = original,
            backdropUrl = "enriched-backdrop",
            logoUrl = "enriched-logo",
            posterUrl = "poster"
        )
        val enrichedMeta = original.copy(
            background = "enriched-backdrop",
            logo = "enriched-logo"
        )

        val rebuilt = buildCatalogItem(
            resolved = enrichedResolved,
            metaPreview = enrichedMeta,
            row = row,
            useLandscapePosters = true,
            occurrence = 0,
            previousCachedItem = cached
        )

        assertEquals("original-backdrop", rebuilt.heroPreview.frozenBackdropUrl)
        assertEquals("original-logo", rebuilt.heroPreview.frozenLogoUrl)
        assertEquals("enriched-backdrop", rebuilt.heroPreview.backdrop)
        assertEquals("enriched-logo", rebuilt.heroPreview.logo)
    }

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

    private fun resolvedRowItem(
        meta: MetaPreview,
        posterUrl: String? = meta.poster,
        backdropUrl: String? = meta.background,
        logoUrl: String? = meta.logo
    ): ModernHomeRowItem {
        val itemKey = homeDisplayItemKey(meta.apiType, meta.id)
        return ModernHomeRowItem.from(
            ResolvedDisplayItem(
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
        )
    }

    @Test
    fun `modern carousel card uses poster image until focused backdrop expands`() {
        assertEquals(
            "rpdb-poster",
            resolveModernCarouselCardImageUrl(
                focusedPosterBackdropExpandEnabled = true,
                isBackdropExpanded = false,
                frozenBackdropUrl = "stock-backdrop",
                itemImageUrl = "rpdb-poster",
                heroPoster = "rpdb-poster",
                heroBackdrop = "stock-backdrop"
            )
        )
        assertEquals(
            "stock-backdrop",
            resolveModernCarouselCardImageUrl(
                focusedPosterBackdropExpandEnabled = true,
                isBackdropExpanded = true,
                frozenBackdropUrl = "stock-backdrop",
                itemImageUrl = "rpdb-poster",
                heroPoster = "rpdb-poster",
                heroBackdrop = "stock-backdrop"
            )
        )
    }

    @Test
    fun `modern visible catalog rows include loading rows and exclude empty settled rows`() {
        val hydrated = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "hydrated",
            catalogName = "Hydrated",
            type = ContentType.MOVIE,
            items = listOf(
                MetaPreview(
                    id = "tt123",
                    type = ContentType.MOVIE,
                    name = "Hydrated Movie",
                    poster = null,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = null,
                    imdbRating = null,
                    genres = emptyList()
                )
            )
        )
        val loading = hydrated.copy(catalogId = "loading", catalogName = "Loading", items = emptyList(), isLoading = true)
        val emptySettled = hydrated.copy(catalogId = "empty", catalogName = "Empty", items = emptyList(), isLoading = false)

        assertEquals(listOf(hydrated, loading), modernVisibleCatalogRows(listOf(hydrated, loading, emptySettled)))
    }

    @Test
    fun `loading placeholder count is stable for modern catalog rows`() {
        assertEquals(8, modernLoadingPlaceholderCount())
    }

    @Test
    fun `applyTomatoesToContinueWatchingItem updates persisted display metadata`() {
        val item = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt123",
                contentType = "movie",
                name = "Raw Title",
                poster = "rawPoster",
                backdrop = "rawBackdrop",
                logo = "rawLogo",
                videoId = "tt123",
                season = null,
                episode = null,
                episodeTitle = null,
                position = 100L,
                duration = 1_000L,
                lastWatched = 1L
            ),
            displayMetadata = HomeDisplayMetadata(
                title = "Display Title",
                tomatoesRating = null
            )
        )

        val updated = applyTomatoesToContinueWatchingItem(item, 88.0)

        assertEquals(88.0, updated.displayMetadata().tomatoesRating ?: 0.0, 0.0)
    }

    @Test
    fun `resolveFocusedTrailerSelection returns continue watching trailer selection`() {
        val item = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt123",
                contentType = "series",
                name = "Paradise",
                poster = "poster",
                backdrop = "backdrop",
                logo = "logo",
                videoId = "tt123:1:1",
                season = 1,
                episode = 1,
                episodeTitle = "Episode 1",
                position = 100L,
                duration = 1_000L,
                lastWatched = 1L
            ),
            displayMetadata = HomeDisplayMetadata(
                title = "Paradise",
                releaseInfo = "2025"
            )
        )

        val builtItem = buildContinueWatchingItem(
            resolved = resolvedCwInProgress(item),
            useLandscapePosters = true,
            airsDateTemplate = "Airs %s",
            upcomingLabel = "Upcoming"
        )
        val selection = resolveFocusedTrailerSelection(
            rowKey = "continue_watching",
            item = builtItem
        )

        assertEquals("continue_watching", selection?.rowKey)
        assertEquals("tt123", selection?.itemId)
        assertEquals("series", selection?.itemType)
        assertEquals("Paradise", selection?.trailerTitle)
        assertEquals(builtItem.subtitle, selection?.trailerReleaseInfo)
        assertNull(selection?.fallbackTrailerYtId)
    }

    @Test
    fun `modern row prefetch only runs for the active row while vertical scrolling is idle`() {
        assertEquals(true, shouldPrefetchModernRow(isActiveRow = true, isVerticalRowsScrolling = false))
        assertEquals(false, shouldPrefetchModernRow(isActiveRow = false, isVerticalRowsScrolling = false))
        assertEquals(false, shouldPrefetchModernRow(isActiveRow = true, isVerticalRowsScrolling = true))
    }

    @Test
    fun `modern home repeat focus handling only converts dpad repeat keys`() {
        assertEquals(
            ModernHomeRepeatFocusDirection.Down,
            modernHomeRepeatFocusDirectionForKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
        )
        assertEquals(
            ModernHomeRepeatFocusDirection.Up,
            modernHomeRepeatFocusDirectionForKeyCode(KeyEvent.KEYCODE_DPAD_UP)
        )
        assertEquals(
            ModernHomeRepeatFocusDirection.Left,
            modernHomeRepeatFocusDirectionForKeyCode(KeyEvent.KEYCODE_DPAD_LEFT)
        )
        assertEquals(
            ModernHomeRepeatFocusDirection.Right,
            modernHomeRepeatFocusDirectionForKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
        )
        assertNull(modernHomeRepeatFocusDirectionForKeyCode(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(modernHomeRepeatFocusDirectionForKeyCode(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun `modern home repeat focus handling uses slower vertical throttle`() {
        assertEquals(112L, modernHomeRepeatThrottleMs(ModernHomeRepeatFocusDirection.Down))
        assertEquals(112L, modernHomeRepeatThrottleMs(ModernHomeRepeatFocusDirection.Up))
        assertEquals(80L, modernHomeRepeatThrottleMs(ModernHomeRepeatFocusDirection.Left))
        assertEquals(80L, modernHomeRepeatThrottleMs(ModernHomeRepeatFocusDirection.Right))
    }

    private fun buildModernCarouselItem(tomatoesText: String?): ModernCarouselItem {
        return ModernCarouselItem(
            key = "item_1",
            itemKey = com.nexio.tv.domain.model.homeDisplayItemKey("movie", "tt123"),
            title = "Paradise",
            subtitle = "2025",
            imageUrl = "background",
            heroPreview = HeroPreview(
                title = "Paradise",
                logo = "logo",
                description = "desc",
                contentTypeText = "Movie",
                yearText = "2025",
                imdbText = "7.8",
                tomatoesText = tomatoesText,
                genres = listOf("Action"),
                poster = "poster",
                backdrop = "background",
                imageUrl = "background"
            ),
            payload = ModernPayload.Catalog(
                focusKey = "focus",
                itemId = "tt123",
                itemType = "movie",
                addonBaseUrl = "https://api.example.com",
                trailerTitle = "Paradise",
                trailerReleaseInfo = "2025",
                trailerApiType = "movie"
            ),
            metaPreview = MetaPreview(
                id = "tt123",
                type = ContentType.MOVIE,
                name = "Paradise",
                poster = "poster",
                posterShape = PosterShape.POSTER,
                background = "background",
                logo = "logo",
                description = "desc",
                releaseInfo = "2025",
                runtime = null,
                imdbRating = 7.8f,
                tomatoesRating = tomatoesText?.toDouble(),
                genres = listOf("Action")
            )
        )
    }
}
