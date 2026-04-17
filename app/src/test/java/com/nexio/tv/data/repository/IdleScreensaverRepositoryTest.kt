package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaLink
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.local.TraktDiscoverySnapshotStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleScreensaverRepositoryTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @Test
    fun `findStockCinemetaPopularCatalogRequest matches hidden stock cinemeta popular catalog by type`() {
        val addons = listOf(
            buildAddon(
                baseUrl = "https://v3-cinemeta.strem.io",
                catalogs = listOf(
                    CatalogDescriptor(type = ContentType.MOVIE, id = "top", name = "Top"),
                    CatalogDescriptor(type = ContentType.MOVIE, id = "popular", name = "Popular"),
                    CatalogDescriptor(type = ContentType.SERIES, id = "popularSeries", name = "Popular")
                )
            )
        )

        val movie = findStockCinemetaPopularCatalogRequest(addons, ContentType.MOVIE)
        val series = findStockCinemetaPopularCatalogRequest(addons, ContentType.SERIES)

        requireNotNull(movie)
        requireNotNull(series)
        assertEquals("popular", movie.catalogId)
        assertEquals("popularSeries", series.catalogId)
    }

    @Test
    fun `findStockCinemetaPopularCatalogRequest ignores non cinemeta addons`() {
        val addons = listOf(
            buildAddon(
                baseUrl = "https://example.com",
                catalogs = listOf(
                    CatalogDescriptor(type = ContentType.MOVIE, id = "popular", name = "Popular")
                )
            )
        )

        assertNull(findStockCinemetaPopularCatalogRequest(addons, ContentType.MOVIE))
    }

    @Test
    fun `buildIdleScreensaverSlides keeps top image-limit items per row and drops items without artwork`() = runBlocking {
        val movieRow = buildRow(
            addonBaseUrl = "https://v3-cinemeta.strem.io",
            type = ContentType.MOVIE,
            items = (1..21).map { index ->
                buildPreview(
                    id = "movie-$index",
                    type = ContentType.MOVIE,
                    background = if (index == 21) null else "https://image/$index.jpg",
                    poster = if (index == 21) null else "https://poster/$index.jpg"
                )
            }
        )
        val seriesRow = buildRow(
            addonBaseUrl = "https://v3-cinemeta.strem.io",
            type = ContentType.SERIES,
            items = listOf(
                buildPreview(id = "series-1", type = ContentType.SERIES, background = "https://image/s1.jpg")
            )
        )

        val slides = buildIdleScreensaverSlides(listOf(movieRow, seriesRow))

        assertEquals(6, slides.size)
        assertTrue(slides.none { it.itemId == "movie-21" })
        assertEquals(94.0, slides.first().tomatoesRating ?: 0.0, 0.0)
        assertEquals(
            listOf("movie-1", "movie-2", "movie-3", "movie-4", "movie-5", "series-1"),
            slides.map { it.itemId }
        )
    }

    @Test
    fun `buildIdleScreensaverSlides applies preview enrichment before mapping slides`() = runBlocking {
        val row = buildRow(
            addonBaseUrl = "https://v3-cinemeta.strem.io",
            type = ContentType.MOVIE,
            items = listOf(buildPreview(id = "movie-1", type = ContentType.MOVIE, background = "https://image/1.jpg"))
        )

        val slides = buildIdleScreensaverSlides(listOf(row)) { preview ->
            preview.copy(tomatoesRating = 88.0)
        }

        assertEquals(1, slides.size)
        assertEquals(88.0, slides.single().tomatoesRating ?: 0.0, 0.0)
    }

    @Test
    fun `buildIdleScreensaverSlides honors a custom twenty item per-row limit for trakt-backed rotations`() = runBlocking {
        val movieRow = buildRow(
            addonBaseUrl = "https://api.trakt.tv",
            type = ContentType.MOVIE,
            items = (1..24).map { index ->
                buildPreview("movie-$index", ContentType.MOVIE, "https://image/movie-$index.jpg")
            }
        )
        val showRow = buildRow(
            addonBaseUrl = "https://api.trakt.tv",
            type = ContentType.SERIES,
            items = (1..23).map { index ->
                buildPreview("show-$index", ContentType.SERIES, "https://image/show-$index.jpg")
            }
        )

        val slides = buildIdleScreensaverSlides(
            rows = listOf(movieRow, showRow),
            itemsPerRowLimit = 20
        )

        assertEquals(40, slides.size)
        assertEquals("movie-20", slides[19].itemId)
        assertEquals("show-20", slides.last().itemId)
    }

    @Test
    fun `buildIdleScreensaverSlides exposes mode aware image fallback data and trailer candidates`() = runBlocking {
        val row = buildRow(
            addonBaseUrl = "https://api.trakt.tv",
            type = ContentType.MOVIE,
            items = listOf(
                buildPreview(
                    id = "movie-1",
                    type = ContentType.MOVIE,
                    background = "https://preview/background.jpg",
                    poster = "https://preview/poster.jpg"
                )
            )
        )

        val slides = buildIdleScreensaverSlides(listOf(row)) { preview ->
            preview.copy(
                background = "https://hydrated/background.jpg",
                poster = "https://hydrated/poster.jpg",
                trailerYtIds = listOf("abc123def45")
            )
        }

        val slide = slides.single()
        val modeData = slide.readPropertyOrNull("modeData")
        assertTrue("Expected modeData on IdleScreensaverSlide", modeData != null)

        val imageData = modeData?.readPropertyOrNull("image")
        assertTrue("Expected image mode data on IdleScreensaverSlide.modeData", imageData != null)
        assertEquals(
            listOf("https://hydrated/background.jpg", "https://hydrated/poster.jpg"),
            imageData?.readPropertyOrNull("fallbackArtworkUrls")
        )

        val trailerData = modeData?.readPropertyOrNull("trailer")
        assertTrue("Expected trailer mode data on IdleScreensaverSlide.modeData", trailerData != null)
        assertEquals(
            listOf("abc123def45"),
            trailerData?.readPropertyOrNull("trailerYtIds")
        )
    }

    @Test
    fun `refreshOnColdBoot hydrates trakt source items into trailer ready slides`() = runBlocking {
        val addonRepository = mockk<AddonRepository>()
        val catalogRepository = mockk<CatalogRepository>(relaxed = true)
        val metaRepository = mockk<MetaRepository>()
        val mdbListRepository = mockk<MDBListRepository>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>()
        val traktSnapshotStore = mockk<TraktDiscoverySnapshotStore>()
        val snapshot = TraktDiscoverySnapshot(
            trendingMovieItems = listOf(
                buildPreview("movie-1", ContentType.MOVIE, background = "https://preview/movie.jpg")
            ),
            trendingShowItems = listOf(
                buildPreview("show-1", ContentType.SERIES, background = "https://preview/show.jpg")
            )
        )

        every { addonRepository.getInstalledAddons() } returns flowOf(emptyList())
        every { traktAuthDataStore.isAuthenticated } returns flowOf(true)
        every { traktSettingsDataStore.catalogPreferences } returns flowOf(
            TraktCatalogPreferences(
                enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES, TraktCatalogIds.TRENDING_SHOWS)
            )
        )
        every { traktSnapshotStore.readActiveProfile() } returns snapshot
        coEvery { mdbListRepository.enrichPreview(any()) } answers { firstArg() }
        every {
            metaRepository.getMetaFromAllAddons(
                type = any(),
                id = any(),
                cacheOnDisk = true,
                writeToDisk = true,
                origin = "idle_screensaver"
            )
        } answers {
            val type = firstArg<String>()
            val id = secondArg<String>()
            flowOf(NetworkResult.Success(buildMeta(id = id, type = type, includeTrailer = id == "movie-1")))
        }

        val repository = IdleScreensaverRepository(
            addonRepository = addonRepository,
            catalogRepository = catalogRepository,
            metaRepository = metaRepository,
            mdbListRepository = mdbListRepository,
            traktAuthDataStore = traktAuthDataStore,
            traktSettingsDataStore = traktSettingsDataStore,
            traktDiscoverySnapshotStore = traktSnapshotStore
        )

        repository.refreshOnColdBoot()

        val slides = repository.slides.value
        assertEquals(2, slides.size)
        assertEquals("Hydrated movie-1", slides.first().title)
        assertEquals(listOf("trailer1234"), slides.first().modeData.trailer?.trailerYtIds)
        assertNull(slides.last().modeData.trailer)
        verify(exactly = 2) {
            metaRepository.getMetaFromAllAddons(any(), any(), true, true, "idle_screensaver")
        }
        coVerify(exactly = 0) {
            catalogRepository.refreshCatalogToDisk(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `warmFromCache publishes trakt idle pool from cached metadata without MDBList enrichment`() = runBlocking {
        val addonRepository = mockk<AddonRepository>()
        val catalogRepository = mockk<CatalogRepository>(relaxed = true)
        val metaRepository = mockk<MetaRepository>()
        val mdbListRepository = mockk<MDBListRepository>(relaxed = true)
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>()
        val traktSnapshotStore = mockk<TraktDiscoverySnapshotStore>()
        val snapshot = TraktDiscoverySnapshot(
            trendingMovieItems = listOf(
                buildPreview("movie-1", ContentType.MOVIE, background = "https://preview/movie.jpg")
            ),
            trendingShowItems = listOf(
                buildPreview("show-1", ContentType.SERIES, background = "https://preview/show.jpg")
            )
        )

        every { addonRepository.getInstalledAddons() } returns flowOf(emptyList())
        every { traktAuthDataStore.isAuthenticated } returns flowOf(true)
        every { traktSettingsDataStore.catalogPreferences } returns flowOf(
            TraktCatalogPreferences(
                enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES, TraktCatalogIds.TRENDING_SHOWS)
            )
        )
        every { traktSnapshotStore.readActiveProfile() } returns snapshot
        coEvery {
            metaRepository.getCachedMetaFromAllAddons(
                type = any(),
                id = any(),
                origin = "idle_screensaver_cache"
            )
        } answers {
            val type = firstArg<String>()
            val id = secondArg<String>()
            buildMeta(id = id, type = type, includeTrailer = true)
        }

        val repository = IdleScreensaverRepository(
            addonRepository = addonRepository,
            catalogRepository = catalogRepository,
            metaRepository = metaRepository,
            mdbListRepository = mdbListRepository,
            traktAuthDataStore = traktAuthDataStore,
            traktSettingsDataStore = traktSettingsDataStore,
            traktDiscoverySnapshotStore = traktSnapshotStore
        )

        repository.warmFromCache()

        assertEquals(2, repository.slides.value.size)
        assertEquals(2, repository.trailerCandidates.value.size)
        assertEquals("Hydrated movie-1", repository.slides.value.first().title)
        coVerify(exactly = 2) {
            metaRepository.getCachedMetaFromAllAddons(any(), any(), "idle_screensaver_cache")
        }
        coVerify(exactly = 0) { mdbListRepository.enrichPreview(any()) }
        coVerify(exactly = 0) {
            catalogRepository.refreshCatalogToDisk(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `warmFromCache uses cached cinemeta catalogs without network refresh`() = runBlocking {
        val addonRepository = mockk<AddonRepository>()
        val catalogRepository = mockk<CatalogRepository>()
        val metaRepository = mockk<MetaRepository>()
        val mdbListRepository = mockk<MDBListRepository>(relaxed = true)
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>()
        val traktSnapshotStore = mockk<TraktDiscoverySnapshotStore>()
        val movieRow = buildRow(
            addonBaseUrl = "https://v3-cinemeta.strem.io",
            type = ContentType.MOVIE,
            items = listOf(buildPreview("movie-1", ContentType.MOVIE, "https://image/movie-1.jpg"))
        )
        val showRow = buildRow(
            addonBaseUrl = "https://v3-cinemeta.strem.io",
            type = ContentType.SERIES,
            items = listOf(buildPreview("show-1", ContentType.SERIES, "https://image/show-1.jpg"))
        )

        every { addonRepository.getInstalledAddons() } returns flowOf(
            listOf(
                buildAddon(
                    baseUrl = "https://v3-cinemeta.strem.io",
                    catalogs = listOf(
                        CatalogDescriptor(type = ContentType.MOVIE, id = "popular-movie", name = "Popular"),
                        CatalogDescriptor(type = ContentType.SERIES, id = "popular-series", name = "Popular")
                    )
                )
            )
        )
        coEvery {
            addonRepository.getCachedInstalledAddons()
        } returns listOf(
            buildAddon(
                baseUrl = "https://v3-cinemeta.strem.io",
                catalogs = listOf(
                    CatalogDescriptor(type = ContentType.MOVIE, id = "popular-movie", name = "Popular"),
                    CatalogDescriptor(type = ContentType.SERIES, id = "popular-series", name = "Popular")
                )
            )
        )
        every { traktAuthDataStore.isAuthenticated } returns flowOf(false)
        every { traktSettingsDataStore.catalogPreferences } returns flowOf(TraktCatalogPreferences())
        every { traktSnapshotStore.readActiveProfile() } returns null
        every {
            catalogRepository.getCatalogCachedFirst(
                addonBaseUrl = "https://v3-cinemeta.strem.io",
                addonId = "cinemeta",
                addonName = "Cinemeta",
                catalogId = "popular-movie",
                catalogName = "Popular",
                type = "movie",
                skip = 0,
                skipStep = 100,
                extraArgs = emptyMap(),
                supportsSkip = false,
                allowNetworkRefresh = false
            )
        } returns flowOf(NetworkResult.Success(movieRow))
        every {
            catalogRepository.getCatalogCachedFirst(
                addonBaseUrl = "https://v3-cinemeta.strem.io",
                addonId = "cinemeta",
                addonName = "Cinemeta",
                catalogId = "popular-series",
                catalogName = "Popular",
                type = "series",
                skip = 0,
                skipStep = 100,
                extraArgs = emptyMap(),
                supportsSkip = false,
                allowNetworkRefresh = false
            )
        } returns flowOf(NetworkResult.Success(showRow))
        coEvery {
            metaRepository.getCachedMetaFromAllAddons(
                type = any(),
                id = any(),
                origin = "idle_screensaver_cache"
            )
        } answers {
            val type = firstArg<String>()
            val id = secondArg<String>()
            buildMeta(id = id, type = type, includeTrailer = true)
        }

        val repository = IdleScreensaverRepository(
            addonRepository = addonRepository,
            catalogRepository = catalogRepository,
            metaRepository = metaRepository,
            mdbListRepository = mdbListRepository,
            traktAuthDataStore = traktAuthDataStore,
            traktSettingsDataStore = traktSettingsDataStore,
            traktDiscoverySnapshotStore = traktSnapshotStore
        )

        repository.warmFromCache()

        assertEquals(2, repository.slides.value.size)
        assertEquals(2, repository.trailerCandidates.value.size)
        verify(exactly = 1) {
            catalogRepository.getCatalogCachedFirst(
                addonBaseUrl = "https://v3-cinemeta.strem.io",
                addonId = "cinemeta",
                addonName = "Cinemeta",
                catalogId = "popular-movie",
                catalogName = "Popular",
                type = "movie",
                skip = 0,
                skipStep = 100,
                extraArgs = emptyMap(),
                supportsSkip = false,
                allowNetworkRefresh = false
            )
        }
        verify(exactly = 1) {
            catalogRepository.getCatalogCachedFirst(
                addonBaseUrl = "https://v3-cinemeta.strem.io",
                addonId = "cinemeta",
                addonName = "Cinemeta",
                catalogId = "popular-series",
                catalogName = "Popular",
                type = "series",
                skip = 0,
                skipStep = 100,
                extraArgs = emptyMap(),
                supportsSkip = false,
                allowNetworkRefresh = false
            )
        }
        coVerify(exactly = 1) { addonRepository.getCachedInstalledAddons() }
        verify(exactly = 0) { addonRepository.getInstalledAddons() }
        coVerify(exactly = 0) { mdbListRepository.enrichPreview(any()) }
        coVerify(exactly = 0) {
            catalogRepository.refreshCatalogToDisk(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `refreshOnColdBoot falls back to cinemeta popular movie and series sources when trakt is not eligible even without trailer metadata`() = runBlocking {
        val addonRepository = mockk<AddonRepository>()
        val catalogRepository = mockk<CatalogRepository>()
        val metaRepository = mockk<MetaRepository>()
        val mdbListRepository = mockk<MDBListRepository>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val traktSettingsDataStore = mockk<TraktSettingsDataStore>()
        val traktSnapshotStore = mockk<TraktDiscoverySnapshotStore>()
        val movieRow = buildRow(
            addonBaseUrl = "https://v3-cinemeta.strem.io",
            type = ContentType.MOVIE,
            items = (1..21).map { index ->
                buildPreview("movie-$index", ContentType.MOVIE, "https://image/movie-$index.jpg")
            }
        )
        val showRow = buildRow(
            addonBaseUrl = "https://v3-cinemeta.strem.io",
            type = ContentType.SERIES,
            items = (1..21).map { index ->
                buildPreview("show-$index", ContentType.SERIES, "https://image/show-$index.jpg")
            }
        )

        every { addonRepository.getInstalledAddons() } returns flowOf(
            listOf(
                buildAddon(
                    baseUrl = "https://v3-cinemeta.strem.io",
                    catalogs = listOf(
                        CatalogDescriptor(type = ContentType.MOVIE, id = "popular-movie", name = "Popular"),
                        CatalogDescriptor(type = ContentType.SERIES, id = "popular-series", name = "Popular")
                    )
                )
            )
        )
        every { traktAuthDataStore.isAuthenticated } returns flowOf(false)
        every { traktSettingsDataStore.catalogPreferences } returns flowOf(TraktCatalogPreferences())
        every { traktSnapshotStore.readActiveProfile() } returns null
        coEvery { mdbListRepository.enrichPreview(any()) } answers { firstArg() }
        every {
            metaRepository.getMetaFromAllAddons(
                type = any(),
                id = any(),
                cacheOnDisk = true,
                writeToDisk = true,
                origin = "idle_screensaver"
            )
        } returns flowOf(NetworkResult.Error("missing"))
        coEvery {
            catalogRepository.refreshCatalogToDisk(
                addonBaseUrl = "https://v3-cinemeta.strem.io",
                addonId = "cinemeta",
                addonName = "Cinemeta",
                catalogId = "popular-movie",
                catalogName = "Popular",
                type = "movie",
                skip = 0,
                skipStep = 100,
                extraArgs = emptyMap(),
                supportsSkip = false
            )
        } returns Result.success(movieRow)
        coEvery {
            catalogRepository.refreshCatalogToDisk(
                addonBaseUrl = "https://v3-cinemeta.strem.io",
                addonId = "cinemeta",
                addonName = "Cinemeta",
                catalogId = "popular-series",
                catalogName = "Popular",
                type = "series",
                skip = 0,
                skipStep = 100,
                extraArgs = emptyMap(),
                supportsSkip = false
            )
        } returns Result.success(showRow)

        val repository = IdleScreensaverRepository(
            addonRepository = addonRepository,
            catalogRepository = catalogRepository,
            metaRepository = metaRepository,
            mdbListRepository = mdbListRepository,
            traktAuthDataStore = traktAuthDataStore,
            traktSettingsDataStore = traktSettingsDataStore,
            traktDiscoverySnapshotStore = traktSnapshotStore
        )

        repository.refreshOnColdBoot()

        assertEquals(10, repository.slides.value.size)
        assertEquals(0, repository.trailerCandidates.value.size)
        coVerify(exactly = 1) {
            catalogRepository.refreshCatalogToDisk(
                addonBaseUrl = "https://v3-cinemeta.strem.io",
                addonId = "cinemeta",
                addonName = "Cinemeta",
                catalogId = "popular-movie",
                catalogName = "Popular",
                type = "movie",
                skip = 0,
                skipStep = 100,
                extraArgs = emptyMap(),
                supportsSkip = false
            )
        }
        coVerify(exactly = 1) {
            catalogRepository.refreshCatalogToDisk(
                addonBaseUrl = "https://v3-cinemeta.strem.io",
                addonId = "cinemeta",
                addonName = "Cinemeta",
                catalogId = "popular-series",
                catalogName = "Popular",
                type = "series",
                skip = 0,
                skipStep = 100,
                extraArgs = emptyMap(),
                supportsSkip = false
            )
        }
    }

    @Test
    fun `shouldUseTraktScreensaverSource requires auth both trending rails and populated snapshot`() {
        val prefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES, TraktCatalogIds.TRENDING_SHOWS)
        )
        val snapshot = TraktDiscoverySnapshot(
            trendingMovieItems = listOf(buildPreview("movie-1", ContentType.MOVIE, "https://image/m1.jpg")),
            trendingShowItems = listOf(buildPreview("show-1", ContentType.SERIES, "https://image/s1.jpg"))
        )

        assertTrue(shouldUseTraktScreensaverSource(true, prefs, snapshot))
        assertFalse(shouldUseTraktScreensaverSource(false, prefs, snapshot))
        assertFalse(
            shouldUseTraktScreensaverSource(
                true,
                prefs.copy(enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES)),
                snapshot
            )
        )
        assertFalse(
            shouldUseTraktScreensaverSource(
                true,
                prefs,
                snapshot.copy(trendingShowItems = emptyList())
            )
        )
        assertFalse(shouldUseTraktScreensaverSource(true, prefs, null))
    }

    @Test
    fun `buildTraktScreensaverRows caps movies and shows at twenty items each`() {
        val snapshot = TraktDiscoverySnapshot(
            trendingMovieItems = (1..24).map { index ->
                buildPreview("movie-$index", ContentType.MOVIE, "https://image/movie-$index.jpg")
            },
            trendingShowItems = (1..23).map { index ->
                buildPreview("show-$index", ContentType.SERIES, "https://image/show-$index.jpg")
            }
        )

        val rows = buildTraktScreensaverRows(snapshot)

        assertEquals(2, rows.size)
        assertEquals(20, rows.first { it.type == ContentType.MOVIE }.items.size)
        assertEquals(20, rows.first { it.type == ContentType.SERIES }.items.size)
        assertEquals("https://api.trakt.tv", rows.first().addonBaseUrl)
    }

    private fun buildAddon(
        baseUrl: String,
        catalogs: List<CatalogDescriptor>
    ): Addon {
        return Addon(
            id = "cinemeta",
            name = "Cinemeta",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = baseUrl,
            catalogs = catalogs,
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            resources = listOf(AddonResource(name = "catalog", types = listOf("movie", "series"), idPrefixes = null))
        )
    }

    private fun buildRow(
        addonBaseUrl: String,
        type: ContentType,
        items: List<MetaPreview>
    ): CatalogRow {
        return CatalogRow(
            addonId = "cinemeta",
            addonName = "Cinemeta",
            addonBaseUrl = addonBaseUrl,
            catalogId = "popular",
            catalogName = "Popular",
            type = type,
            items = items
        )
    }

    private fun buildPreview(
        id: String,
        type: ContentType,
        background: String?,
        poster: String? = background
    ): MetaPreview {
        return MetaPreview(
            id = id,
            type = type,
            name = id,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = background,
            logo = null,
            description = "Description for $id",
            releaseInfo = "2024",
            runtime = "110",
            imdbRating = 7.4f,
            tomatoesRating = 94.0,
            genres = emptyList()
        )
    }

    private fun buildMeta(
        id: String,
        type: String,
        includeTrailer: Boolean
    ): Meta {
        val contentType = if (type == "movie") ContentType.MOVIE else ContentType.SERIES
        return Meta(
            id = id,
            type = contentType,
            rawType = type,
            name = "Hydrated $id",
            poster = "https://meta/$id/poster.jpg",
            posterShape = PosterShape.POSTER,
            background = "https://meta/$id/background.jpg",
            logo = "https://meta/$id/logo.png",
            description = "Hydrated description for $id",
            releaseInfo = "2025",
            imdbRating = 8.1f,
            genres = listOf("Drama"),
            runtime = "120m",
            director = emptyList(),
            cast = emptyList(),
            videos = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = listOf(MetaLink(name = "IMDb", category = "external", url = "https://example.com/$id")),
            trailerYtIds = if (includeTrailer) listOf("trailer1234") else emptyList()
        )
    }

    private fun Any.readPropertyOrNull(name: String): Any? {
        val getterName = "get" + name.replaceFirstChar { it.uppercase() }
        val getter = javaClass.methods.firstOrNull { method ->
            method.name == getterName && method.parameterCount == 0
        } ?: return null
        return getter.invoke(this)
    }
}
