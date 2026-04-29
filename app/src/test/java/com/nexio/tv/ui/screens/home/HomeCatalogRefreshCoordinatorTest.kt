package com.nexio.tv.ui.screens.home

import android.content.Context
import android.content.SharedPreferences
import com.nexio.tv.core.metadata.router.testMetadataRouterFacade
import com.nexio.tv.core.player.PlaybackActivityTracker
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.tvdb.ProviderLocalizedMetadataResolver
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.repository.TitleRatingOverrideRepository
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.repository.CatalogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogRefreshCoordinatorTest {

    @Test
    fun `diffCatalogItems marks new and changed entries as addedOrChanged`() {
        val oldItems = listOf(
            preview(id = "a", poster = "posterA"),
            preview(id = "b", poster = "posterB")
        )
        val newItems = listOf(
            preview(id = "a", poster = "posterA_v2"),
            preview(id = "b", poster = "posterB"),
            preview(id = "c", poster = "posterC")
        )

        val diff = diffCatalogItems(oldItems, newItems)

        val changedIds = diff.addedOrChanged.map { it.id }.toSet()
        assertEquals(setOf("a", "c"), changedIds)
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun `diffCatalogItems marks removed entries`() {
        val oldItems = listOf(
            preview(id = "a", poster = "posterA"),
            preview(id = "b", poster = "posterB")
        )
        val newItems = listOf(
            preview(id = "a", poster = "posterA")
        )

        val diff = diffCatalogItems(oldItems, newItems)

        assertEquals(0, diff.addedOrChanged.size)
        assertEquals(1, diff.removed.size)
        assertEquals("b", diff.removed.first().id)
    }

    @Test
    fun `diffCatalogItems marks metadata only changes as addedOrChanged`() {
        val oldItems = listOf(
            preview(id = "a", poster = "posterA").copy(description = "old", runtime = "90m")
        )
        val newItems = listOf(
            preview(id = "a", poster = "posterA").copy(description = "new", runtime = "95m")
        )

        val diff = diffCatalogItems(oldItems, newItems)

        assertEquals(1, diff.addedOrChanged.size)
        assertEquals("a", diff.addedOrChanged.first().id)
    }

    @Test
    fun `tvdb home enrichment carries runtime into preview`() {
        val enriched = preview(id = "a", poster = "posterA")
            .applyTvMetadataEnrichmentForHome(
                TvMetadataEnrichment(
                    seriesTvdbId = 121361,
                    runtimeMinutes = 52
                )
            )

        assertEquals("52 min", enriched.runtime)
    }

    @Test
    fun `shouldReusePersistedHomeItem only reuses unchanged rows with tomatoes persisted`() {
        assertTrue(
            shouldReusePersistedHomeItem(
                itemChanged = false,
                persistedFallback = preview(id = "a", poster = "posterA").copy(tomatoesRating = 71.0)
            )
        )
        assertEquals(
            false,
            shouldReusePersistedHomeItem(
                itemChanged = false,
                persistedFallback = preview(id = "a", poster = "posterA")
            )
        )
        assertEquals(
            false,
            shouldReusePersistedHomeItem(
                itemChanged = true,
                persistedFallback = preview(id = "a", poster = "posterA").copy(tomatoesRating = 71.0)
            )
        )
    }

    @Test
    fun `refresh first paint publishes catalog row without addon detail metadata fetch`() = runTest {
        val catalogRepository = mockk<CatalogRepository>()
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val publishedRows = mutableListOf<CatalogRow>()
        val catalogPreview = preview(id = "tt-first-paint", poster = null).copy(
            name = "Catalog payload title",
            description = "Catalog payload description",
            releaseInfo = "2026"
        )
        val catalogRow = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(catalogPreview),
            hasMore = false
        )
        coEvery {
            catalogRepository.refreshCatalogToDisk(
                addonBaseUrl = "https://addon.example",
                addonId = "addon",
                addonName = "Addon",
                catalogId = "popular",
                catalogName = "Popular",
                type = "movie",
                skip = 0,
                skipStep = 100,
                supportsSkip = false
            )
        } returns Result.success(catalogRow)
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_INACTIVE,
            value = null
        )

        val refreshed = coordinator(
            catalogRepository = catalogRepository,
            tvMetadataRouter = tvMetadataRouter
        ).refreshSerially(
            addons = listOf(addon()),
            telemetryEnabled = true,
            isCatalogDisabled = { _, _ -> false },
            getCurrentRow = { null },
            isItemReferencedElsewhere = { _, _ -> false },
            onCatalogReady = { _, row, _ -> publishedRows += row },
            onLog = { _, _ -> }
        )

        assertEquals(1, refreshed)
        assertEquals(1, publishedRows.size)
        assertEquals("tt-first-paint", publishedRows.single().items.single().id)
        assertEquals("Catalog payload description", publishedRows.single().items.single().description)
        assertEquals("2026", publishedRows.single().items.single().releaseInfo)
        coVerify(exactly = 1) {
            catalogRepository.refreshCatalogToDisk(
                addonBaseUrl = "https://addon.example",
                addonId = "addon",
                addonName = "Addon",
                catalogId = "popular",
                catalogName = "Popular",
                type = "movie",
                skip = 0,
                skipStep = 100,
                supportsSkip = false
            )
        }
        coVerify(exactly = 1) { tvMetadataRouter.fetchEnrichment(any()) }
        assertRefreshSeriallyDoesNotCallAddonDetailMetadata()
    }

    private fun preview(id: String, poster: String?): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Item $id",
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        )
    }

    private fun addon(): Addon {
        return Addon(
            id = "addon",
            name = "Addon",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://addon.example",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = "popular",
                    name = "Popular"
                )
            ),
            types = listOf(ContentType.MOVIE),
            resources = listOf(AddonResource(name = "catalog", types = listOf("movie"), idPrefixes = null))
        )
    }

    private fun coordinator(
        catalogRepository: CatalogRepository,
        tvMetadataRouter: TvMetadataRouter
    ): HomeCatalogRefreshCoordinator {
        val titleRatingOverrideRepository = mockk<TitleRatingOverrideRepository>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>(relaxed = true)
        val profileBoundary = mockk<ProfileBoundary>()
        val playbackActivityTracker = mockk<PlaybackActivityTracker>()
        val context = mockLocaleContext()
        coEvery { titleRatingOverrideRepository.enrichPreview(any()) } answers { firstArg() }
        every { posterRatingsUrlResolver.apply(any<MetaPreview>(), any()) } answers { firstArg() }
        every { profileBoundary.currentLanguageTag() } returns "en"
        every { playbackActivityTracker.isActive } returns MutableStateFlow(true)

        return HomeCatalogRefreshCoordinator(
            catalogRepository = catalogRepository,
            titleRatingOverrideRepository = titleRatingOverrideRepository,
            metadataDiskCacheStore = metadataDiskCacheStore,
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter),
            providerLocalizedMetadataResolver = ProviderLocalizedMetadataResolver(
                metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter)
            ),
            posterRatingsUrlResolver = posterRatingsUrlResolver,
            profileBoundary = profileBoundary,
            playbackActivityTracker = playbackActivityTracker,
            appContext = context
        )
    }

    private fun mockLocaleContext(): Context {
        val context = mockk<Context>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { context.getSharedPreferences("app_locale", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getString(any(), any()) } returns "en"
        every { prefs.getInt(any(), any()) } returns 1
        return context
    }

    private fun assertRefreshSeriallyDoesNotCallAddonDetailMetadata() {
        val source = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt").readText()
        val start = source.indexOf("    internal suspend fun refreshSerially(")
        val end = source.indexOf("\n    internal fun evictCachedImageUrls", start)

        assertTrue("refreshSerially source should exist", start >= 0)
        assertTrue("refreshSerially source boundary should exist", end > start)
        assertFalse(
            "First-paint row publication may fetch catalog pages, but must not run per-item add-on detail metadata before publish",
            source.substring(start, end).contains("getMetaFromAllAddons(")
        )
    }
}
