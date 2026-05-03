package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.SimklCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TraktCatalogPreferences
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.SimklDiscoverySnapshot
import com.nexio.tv.data.repository.TmdbDiscoverySnapshot
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelTmdbCatalogPlanTest {
    @Test
    fun `enabled tmdb snapshot row is included in publishable order and rails`() {
        val tmdbRow = tmdbRow(
            catalogId = TmdbCatalogIds.TRENDING_MOVIES,
            items = listOf(meta("tt0000001", "Movie"))
        )
        val prefs = TmdbCatalogPreferences(enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES))

        val plan = planFor(
            tmdbPrefs = prefs,
            tmdbSnapshot = currentTmdbSnapshot(prefs, TmdbCatalogIds.TRENDING_MOVIES to tmdbRow)
        )

        assertEquals(listOf(TmdbCatalogIds.TRENDING_MOVIES), plan.publishableOrderKeys)
        assertEquals(listOf(TmdbCatalogIds.TRENDING_MOVIES), plan.rails.map { it.orderKey })
        assertEquals(tmdbRow, plan.rails.single().toPopulatedRows().single())
    }

    @Test
    fun `disabled tmdb catalog is not included even if snapshot has a row`() {
        val tmdbRow = tmdbRow(
            catalogId = TmdbCatalogIds.TRENDING_MOVIES,
            items = listOf(meta("tt0000001", "Movie"))
        )

        val plan = planFor(
            tmdbPrefs = TmdbCatalogPreferences(enabledCatalogs = emptySet()),
            tmdbSnapshot = TmdbDiscoverySnapshot(rowsByCatalog = mapOf(TmdbCatalogIds.TRENDING_MOVIES to tmdbRow))
        )

        assertTrue(plan.publishableOrderKeys.isEmpty())
        assertTrue(plan.rails.isEmpty())
        assertTrue(plan.descriptors.none { it.addonId == TMDB_HOME_ADDON_ID })
    }

    @Test
    fun `empty tmdb row is not publishable`() {
        val tmdbRow = tmdbRow(
            catalogId = TmdbCatalogIds.TRENDING_MOVIES,
            items = emptyList()
        )
        val prefs = TmdbCatalogPreferences(enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES))

        val plan = planFor(
            tmdbPrefs = prefs,
            tmdbSnapshot = currentTmdbSnapshot(prefs, TmdbCatalogIds.TRENDING_MOVIES to tmdbRow)
        )

        assertEquals(listOf(TmdbCatalogIds.TRENDING_MOVIES), plan.expectedOrderKeys)
        assertTrue(plan.publishableOrderKeys.isEmpty())
        assertTrue(plan.rails.isEmpty())
    }

    @Test
    fun `tmdb plan excludes rows not current for preferences`() {
        val tmdbRow = tmdbRow(
            catalogId = TmdbCatalogIds.TRENDING_MOVIES,
            items = listOf(meta("tt0000001", "Movie"))
        )
        val prefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES),
            includeAdult = true
        )

        val plan = planFor(
            tmdbPrefs = prefs,
            tmdbSnapshot = TmdbDiscoverySnapshot(
                rowsByCatalog = mapOf(TmdbCatalogIds.TRENDING_MOVIES to tmdbRow),
                updatedAtMs = 123L,
                includeAdult = false,
                catalogIdsWithCurrentPreferences = setOf(TmdbCatalogIds.TRENDING_MOVIES)
            )
        )

        assertEquals(listOf(TmdbCatalogIds.TRENDING_MOVIES), plan.expectedOrderKeys)
        assertTrue(plan.publishableOrderKeys.isEmpty())
        assertTrue(plan.rails.isEmpty())
    }

    @Test
    fun `tmdb row global key remains configured catalog key`() {
        val tmdbRow = tmdbRow(
            catalogId = TmdbCatalogIds.TRENDING_MOVIES,
            items = listOf(meta("tt0000001", "Movie"))
        )

        assertEquals(TmdbCatalogIds.TRENDING_MOVIES, homeCatalogGlobalKey(tmdbRow))
    }

    private fun planFor(
        tmdbPrefs: TmdbCatalogPreferences,
        tmdbSnapshot: TmdbDiscoverySnapshot
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
            tmdbPrefs = tmdbPrefs,
            tmdbSnapshot = tmdbSnapshot
        )
    }

    private fun tmdbRow(
        catalogId: String,
        items: List<MetaPreview>
    ): CatalogRow {
        return CatalogRow(
            addonId = "tmdb",
            addonName = "TMDB",
            addonBaseUrl = "https://api.themoviedb.org/3",
            catalogId = catalogId,
            catalogName = "TMDB Trending Movies",
            type = ContentType.MOVIE,
            rawType = ContentType.MOVIE.toApiString("catalog"),
            items = items,
            isLoading = false,
            hasMore = false,
            supportsSkip = false
        )
    }

    private fun currentTmdbSnapshot(
        prefs: TmdbCatalogPreferences,
        vararg rows: Pair<String, CatalogRow>
    ): TmdbDiscoverySnapshot {
        val sanitized = prefs.sanitized()
        return TmdbDiscoverySnapshot(
            rowsByCatalog = rows.toMap(),
            updatedAtMs = 123L,
            includeAdult = sanitized.includeAdult,
            catalogIdsWithCurrentPreferences = rows.map { it.first }.toSet()
        )
    }

    private fun meta(id: String, title: String): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            rawType = "movie",
            name = title,
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
