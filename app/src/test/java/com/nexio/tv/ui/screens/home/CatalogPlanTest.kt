package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.SimklCatalogIds
import com.nexio.tv.data.local.SimklCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPlanTest {
    @Test
    fun `enabled simkl catalog missing from saved order is still planned`() {
        val plan = buildConfiguredCatalogPlan(
            addons = emptyList(),
            disabledHomeCatalogKeys = emptySet(),
            availableAddonOrderKeys = emptySet(),
            traktPrefs = TraktCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
            traktSnapshot = TraktDiscoverySnapshot(),
            hasTraktUpNextItems = false,
            simklPrefs = SimklCatalogPreferences(
                enabledCatalogs = setOf(SimklCatalogIds.MOVIE_TRENDING_MONTH),
                catalogOrder = listOf(SimklCatalogIds.TV_TRENDING_TODAY)
            ),
            simklSnapshot = SimklDiscoverySnapshot(
                itemsByCatalog = mapOf(SimklCatalogIds.MOVIE_TRENDING_MONTH to listOf(sampleItem()))
            ),
            mdbPrefs = MDBListCatalogPreferences(),
            mdbSnapshot = MDBListDiscoverySnapshot()
        )

        assertEquals(listOf(SimklCatalogIds.MOVIE_TRENDING_MONTH), plan.expectedOrderKeys)
        assertEquals(listOf(SimklCatalogIds.MOVIE_TRENDING_MONTH), plan.publishableOrderKeys)
        assertEquals(listOf(SimklCatalogIds.MOVIE_TRENDING_MONTH), plan.descriptors.map { it.orderKey })
    }

    @Test
    fun `disabled catalog key is removed from all plan outputs`() {
        val plan = buildConfiguredCatalogPlan(
            addons = emptyList(),
            disabledHomeCatalogKeys = setOf(SimklCatalogIds.MOVIE_TRENDING_MONTH),
            availableAddonOrderKeys = emptySet(),
            traktPrefs = TraktCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
            traktSnapshot = TraktDiscoverySnapshot(),
            hasTraktUpNextItems = false,
            simklPrefs = SimklCatalogPreferences(
                enabledCatalogs = setOf(SimklCatalogIds.MOVIE_TRENDING_MONTH),
                catalogOrder = listOf(SimklCatalogIds.MOVIE_TRENDING_MONTH)
            ),
            simklSnapshot = SimklDiscoverySnapshot(
                itemsByCatalog = mapOf(SimklCatalogIds.MOVIE_TRENDING_MONTH to listOf(sampleItem()))
            ),
            mdbPrefs = MDBListCatalogPreferences(),
            mdbSnapshot = MDBListDiscoverySnapshot()
        )

        assertTrue(plan.expectedOrderKeys.isEmpty())
        assertTrue(plan.publishableOrderKeys.isEmpty())
        assertTrue(plan.descriptors.isEmpty())
    }

    @Test
    fun `trakt rows are not planned when routed auth disables trakt prefs`() {
        val plan = buildConfiguredCatalogPlan(
            addons = emptyList(),
            disabledHomeCatalogKeys = emptySet(),
            availableAddonOrderKeys = emptySet(),
            traktPrefs = TraktCatalogPreferences(
                enabledCatalogs = emptySet(),
                catalogOrder = TraktCatalogIds.BUILT_IN_ORDER
            ),
            traktSnapshot = TraktDiscoverySnapshot(
                trendingMovieItems = listOf(sampleItem()),
                updatedAtMs = 1L
            ),
            hasTraktUpNextItems = true,
            simklPrefs = SimklCatalogPreferences(),
            simklSnapshot = SimklDiscoverySnapshot(),
            mdbPrefs = MDBListCatalogPreferences(),
            mdbSnapshot = MDBListDiscoverySnapshot()
        )

        assertFalse(plan.expectedOrderKeys.any { it.startsWith("trakt_") })
        assertFalse(plan.publishableOrderKeys.any { it.startsWith("trakt_") })
        assertFalse(plan.descriptors.any { it.addonId == TRAKT_HOME_ADDON_ID })
    }

    @Test
    fun `planned simkl rail builds matching loading descriptor and populated row`() {
        val plan = buildConfiguredCatalogPlan(
            addons = emptyList(),
            disabledHomeCatalogKeys = emptySet(),
            availableAddonOrderKeys = emptySet(),
            traktPrefs = TraktCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
            traktSnapshot = TraktDiscoverySnapshot(),
            hasTraktUpNextItems = false,
            simklPrefs = SimklCatalogPreferences(
                enabledCatalogs = setOf(SimklCatalogIds.MOVIE_TRENDING_MONTH),
                catalogOrder = listOf(SimklCatalogIds.MOVIE_TRENDING_MONTH)
            ),
            simklSnapshot = SimklDiscoverySnapshot(
                itemsByCatalog = mapOf(SimklCatalogIds.MOVIE_TRENDING_MONTH to listOf(sampleItem()))
            ),
            mdbPrefs = MDBListCatalogPreferences(),
            mdbSnapshot = MDBListDiscoverySnapshot()
        )

        val rail = plan.rails.single()
        val loading = rail.toLoadingCatalogRow()
        val populated = rail.toPopulatedRows().single()

        assertEquals(loading.addonId, populated.addonId)
        assertEquals(loading.catalogId, populated.catalogId)
        assertEquals(loading.catalogName, populated.catalogName)
        assertFalse(populated.isLoading)
        assertEquals(1, populated.items.size)
    }

    @Test
    fun `populated trakt rails become synthetic groups for immediate restore`() {
        val plan = buildConfiguredCatalogPlan(
            addons = emptyList(),
            disabledHomeCatalogKeys = emptySet(),
            availableAddonOrderKeys = emptySet(),
            traktPrefs = TraktCatalogPreferences(
                enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES),
                catalogOrder = listOf(TraktCatalogIds.TRENDING_MOVIES)
            ),
            traktSnapshot = TraktDiscoverySnapshot(
                trendingMovieItems = listOf(sampleItem()),
                updatedAtMs = 1L
            ),
            hasTraktUpNextItems = false,
            simklPrefs = SimklCatalogPreferences(),
            simklSnapshot = SimklDiscoverySnapshot(),
            mdbPrefs = MDBListCatalogPreferences(),
            mdbSnapshot = MDBListDiscoverySnapshot()
        )

        val groups = plan.toPersistedSyntheticCatalogGroups()

        assertEquals(listOf(TraktCatalogIds.TRENDING_MOVIES), groups.map { it.orderKey })
        val row = groups.single().rows.single()
        assertEquals(TRAKT_HOME_ADDON_ID, row.addonId)
        assertEquals(TraktCatalogIds.TRENDING_MOVIES, row.catalogId)
        assertFalse(row.isLoading)
        assertEquals(1, row.items.size)
    }

    private fun sampleItem(): MetaPreview {
        return MetaPreview(
            id = "tt1234567",
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Sample",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        )
    }
}
