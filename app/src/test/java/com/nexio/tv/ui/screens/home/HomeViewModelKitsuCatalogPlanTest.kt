package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.local.KitsuCatalogIds
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.SimklCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.repository.KitsuDiscoverySnapshot
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelKitsuCatalogPlanTest {
    @Test
    fun `enabled kitsu snapshot row is included in publishable order and rails`() {
        val kitsuRow = kitsuRow(
            catalogId = KitsuCatalogIds.TRENDING_ANIME,
            items = listOf(meta("kitsu:1", "Anime"))
        )
        val prefs = KitsuCatalogPreferences(enabledCatalogs = setOf(KitsuCatalogIds.TRENDING_ANIME))

        val plan = planFor(
            kitsuPrefs = prefs,
            kitsuSnapshot = currentKitsuSnapshot(prefs, KitsuCatalogIds.TRENDING_ANIME to kitsuRow)
        )

        assertEquals(listOf(KitsuCatalogIds.TRENDING_ANIME), plan.publishableOrderKeys)
        assertEquals(listOf(KitsuCatalogIds.TRENDING_ANIME), plan.rails.map { it.orderKey })
        assertEquals(kitsuRow, plan.rails.single().toPopulatedRows().single())
    }

    @Test
    fun `disabled kitsu catalog is not included even if snapshot has row`() {
        val kitsuRow = kitsuRow(
            catalogId = KitsuCatalogIds.TRENDING_ANIME,
            items = listOf(meta("kitsu:1", "Anime"))
        )

        val plan = planFor(
            kitsuPrefs = KitsuCatalogPreferences(enabledCatalogs = emptySet()),
            kitsuSnapshot = KitsuDiscoverySnapshot(rowsByCatalog = mapOf(KitsuCatalogIds.TRENDING_ANIME to kitsuRow))
        )

        assertTrue(plan.publishableOrderKeys.isEmpty())
        assertTrue(plan.rails.isEmpty())
    }

    private fun planFor(
        kitsuPrefs: KitsuCatalogPreferences,
        kitsuSnapshot: KitsuDiscoverySnapshot
    ): CatalogPlan {
        return buildConfiguredCatalogPlan(
            addons = emptyList(),
            disabledHomeCatalogKeys = emptySet(),
            availableAddonOrderKeys = emptySet(),
            traktPrefs = TraktCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
            traktSnapshot = TraktDiscoverySnapshot(),
            hasTraktUpNextItems = false,
            simklPrefs = SimklCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
            simklSnapshot = SimklDiscoverySnapshot(),
            mdbPrefs = MDBListCatalogPreferences(),
            mdbSnapshot = MDBListDiscoverySnapshot(),
            kitsuPrefs = kitsuPrefs,
            kitsuSnapshot = kitsuSnapshot
        )
    }

    private fun currentKitsuSnapshot(
        prefs: KitsuCatalogPreferences,
        vararg rows: Pair<String, CatalogRow>
    ): KitsuDiscoverySnapshot {
        return KitsuDiscoverySnapshot(
            rowsByCatalog = rows.toMap(),
            updatedAtMs = 123L,
            catalogIdsWithCurrentPreferences = prefs.enabledCatalogIds()
        )
    }

    private fun kitsuRow(catalogId: String, items: List<MetaPreview>): CatalogRow {
        return CatalogRow(
            addonId = "kitsu",
            addonName = "Kitsu",
            addonBaseUrl = "https://kitsu.io/api/edge",
            catalogId = catalogId,
            catalogName = "Kitsu Trending Anime",
            type = ContentType.SERIES,
            rawType = ContentType.SERIES.toApiString("catalog"),
            items = items,
            isLoading = false,
            hasMore = false,
            supportsSkip = false
        )
    }

    private fun meta(id: String, title: String): MetaPreview {
        // Build with fields that survive the CatalogRow→RailPreviewCatalogRowRecord→CatalogRow
        // round-trip that KitsuDiscoverySnapshot applies. The round-trip sets firstPaintSource
        // to RAIL_PREVIEW, firstPaintRailSource to ADDON_CATALOG, clears ratingSource to null,
        // and populates firstPaintStableIds/firstPaintSourceItemId from the item id.
        val kitsuId = if (id.startsWith("kitsu:")) id.removePrefix("kitsu:") else null
        return MetaPreview(
            id = id,
            type = ContentType.SERIES,
            rawType = "series",
            name = title,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            ratingSource = null,
            genres = emptyList(),
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintRailSource = RailSource.ADDON_CATALOG,
            firstPaintSourceItemId = id,
            firstPaintStableIds = ProviderIds(kitsu = kitsuId)
        )
    }
}
