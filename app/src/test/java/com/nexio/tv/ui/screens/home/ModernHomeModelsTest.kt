package com.nexio.tv.ui.screens.home

import android.view.KeyEvent
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
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
        // ModernCarouselItem.metaPreview was dropped in Phase 4 of the Plan B
        // migration; the CW-specific projection still lives in
        // continueWatchingInProgressToMetaPreview and is asserted here directly.
        val cwPreview = continueWatchingInProgressToMetaPreview(item)
        assertEquals("tt123", cwPreview.id)
        assertEquals(96.0, cwPreview.tomatoesRating ?: 0.0, 0.0)
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
    fun `resolved continue watching next up hero does not mix stale snapshot metadata`() {
        val staleNextUp = ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "tmdb:308014",
                contentType = "series",
                name = "Berlin and the Lady with an Ermine",
                poster = null,
                backdrop = null,
                logo = null,
                displayMetadata = HomeDisplayMetadata(
                    title = "Berlin and the Lady with an Ermine",
                    description = "English overview",
                    genres = listOf("Drama", "Crime"),
                    releaseInfo = "2026-05-15",
                    imdbRating = 7.0f,
                    ratingSource = TitleRatingSource.IMDB
                ),
                videoId = "tmdb:308014:1:4",
                season = 1,
                episode = 4,
                episodeTitle = "Oranges from China",
                episodeDescription = "English overview",
                thumbnail = null,
                imdbRating = 7.0f,
                genres = listOf("Drama", "Crime"),
                releaseInfo = "2026-05-15",
                lastWatched = 1L
            )
        )
        val resolved = ContinueWatchingResolvedDisplayItem.fromNextUp(
            resolved = resolvedDisplayItemForCw(
                contentId = "tmdb:308014",
                contentType = "series",
                title = "Berlín y la dama del armiño",
                overview = "Berlijn zint op een nieuwe overval.",
                genres = listOf("Drama", "Misdaad"),
                rating = TitleRating(8.04, TitleRatingSource.TMDB),
                posterUrl = null,
                backdropUrl = null,
                logoUrl = null
            ),
            source = staleNextUp
        )

        val built = buildContinueWatchingItem(
            resolved = resolved,
            useLandscapePosters = true,
            airsDateTemplate = "Airs %s",
            upcomingLabel = "Upcoming"
        )

        assertEquals("Berlín y la dama del armiño", built.heroPreview.title)
        assertEquals("Berlijn zint op een nieuwe overval.", built.heroPreview.description)
        assertEquals("7.0", built.heroPreview.imdbText)
        assertEquals(TitleRatingSource.IMDB, built.heroPreview.ratingSource)
        assertEquals(listOf("Drama", "Misdaad"), built.heroPreview.genres)
    }

    @Test
    fun `legacy continue watching movie title rating does not render as imdb episode rating`() {
        val movie = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt28650488",
                contentType = "movie",
                name = "The Super Mario Galaxy Movie",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tt28650488",
                season = null,
                episode = null,
                episodeTitle = null,
                position = 0L,
                duration = 0L,
                lastWatched = 1L
            ),
            displayMetadata = HomeDisplayMetadata(
                title = "The Super Mario Galaxy Movie",
                imdbRating = 7.2f,
                ratingSource = TitleRatingSource.TMDB
            ),
            episodeImdbRating = 7.2f
        )
        val resolved = ContinueWatchingResolvedDisplayItem.fromInProgressLegacy(movie)

        val built = buildContinueWatchingItem(
            resolved = resolved,
            useLandscapePosters = true,
            airsDateTemplate = "Airs %s",
            upcomingLabel = "Upcoming"
        )

        assertEquals("7.2", built.heroPreview.imdbText)
        assertEquals(TitleRatingSource.TMDB, built.heroPreview.ratingSource)
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
    fun `resolveDisplayedHeroPreview drops stale foreground when active item changes`() {
        val previousPreview = buildModernCarouselItem(tomatoesText = "70").heroPreview.copy(
            title = "Berlin",
            description = "English overview",
            textSourceRank = DisplaySourceRank.FIRST_PAINT,
            textLanguageTag = "en-US"
        )
        val activePreview = buildModernCarouselItem(tomatoesText = "88").heroPreview.copy(
            title = "Berlín y la dama del armiño",
            description = "Nederlandse beschrijving",
            textSourceRank = DisplaySourceRank.RESOLVED,
            textLanguageTag = "nl-NL"
        )

        val resolved = resolveDisplayedHeroPreview(
            displayedHeroItemKey = "previous_item",
            activeHeroItemKey = "active_item",
            displayedHeroPreview = previousPreview,
            liveActiveHeroPreview = activePreview,
            preferredLanguageTag = "nl-NL"
        )

        assertEquals("Berlín y la dama del armiño", resolved?.title)
        assertEquals("Nederlandse beschrijving", resolved?.description)
        assertEquals("88", resolved?.tomatoesText)
        assertEquals("nl-NL", resolved?.textLanguageTag)
    }

    @Test
    fun `resolveDisplayedHeroPreview keeps foreground text set when fallback metadata races later`() {
        val localizedPreview = buildModernCarouselItem(tomatoesText = null).heroPreview.copy(
            title = "Berlijn",
            description = "Nederlandse beschrijving",
            contentTypeText = "Drama",
            yearText = "2026",
            genres = listOf("Drama"),
            textSourceRank = DisplaySourceRank.RESOLVED
        )
        val fallbackPreview = buildModernCarouselItem(tomatoesText = "88").heroPreview.copy(
            title = "Berlin",
            description = "English overview",
            contentTypeText = "Series",
            yearText = "2026",
            genres = listOf("Crime"),
            textSourceRank = DisplaySourceRank.FIRST_PAINT
        )

        val resolved = resolveDisplayedHeroPreview(
            displayedHeroItemKey = "item_1",
            activeHeroItemKey = "item_1",
            displayedHeroPreview = localizedPreview,
            liveActiveHeroPreview = fallbackPreview
        )

        assertEquals("Berlijn", resolved?.title)
        assertEquals("Nederlandse beschrijving", resolved?.description)
        assertEquals("Drama", resolved?.contentTypeText)
        assertEquals(listOf("Drama"), resolved?.genres)
        assertEquals("88", resolved?.tomatoesText)
    }

    @Test
    fun `resolveDisplayedHeroPreview keeps foreground imdb rating when fallback metadata races later`() {
        val resolvedPreview = buildModernCarouselItem(tomatoesText = null).heroPreview.copy(
            title = "La Brea",
            imdbText = "5.8",
            ratingSource = TitleRatingSource.IMDB,
            textSourceRank = DisplaySourceRank.RESOLVED
        )
        val fallbackPreview = buildModernCarouselItem(tomatoesText = null).heroPreview.copy(
            title = "La Brea",
            imdbText = "7.4",
            ratingSource = TitleRatingSource.TMDB,
            textSourceRank = DisplaySourceRank.FIRST_PAINT
        )

        val resolved = resolveDisplayedHeroPreview(
            displayedHeroItemKey = "item_1",
            activeHeroItemKey = "item_1",
            displayedHeroPreview = resolvedPreview,
            liveActiveHeroPreview = fallbackPreview
        )

        assertEquals("5.8", resolved?.imdbText)
        assertEquals(TitleRatingSource.IMDB, resolved?.ratingSource)
    }

    @Test
    fun `selectForegroundHeroPreview prefers requested language when equal rank metadata races`() {
        val englishPreview = buildModernCarouselItem(tomatoesText = "70").heroPreview.copy(
            title = "Berlin and the Lady with an Ermine",
            description = "English overview",
            genres = listOf("Crime"),
            textSourceRank = DisplaySourceRank.RESOLVED,
            textLanguageTag = "en-US"
        )
        val dutchPreview = buildModernCarouselItem(tomatoesText = "88").heroPreview.copy(
            title = "Berlijn en de Dame met de Hermelijn",
            description = "Nederlandse beschrijving",
            genres = listOf("Misdaad"),
            textSourceRank = DisplaySourceRank.RESOLVED,
            textLanguageTag = "nl-NL"
        )

        val selected = selectForegroundHeroPreview(
            current = englishPreview,
            candidate = dutchPreview,
            preferredLanguageTag = "nl-NL"
        )

        assertEquals("Berlijn en de Dame met de Hermelijn", selected?.title)
        assertEquals("Nederlandse beschrijving", selected?.description)
        assertEquals(listOf("Misdaad"), selected?.genres)
        assertEquals("88", selected?.tomatoesText)
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
        overview: String? = null,
        genres: List<String> = emptyList(),
        rating: TitleRating? = null,
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
                overview = overview,
                genres = genres,
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
            rating = rating,
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
            )
        )
    }
}
