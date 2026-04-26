package com.nexio.tv.metadata.audit

import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolverType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataExecutionAuditGoldenTest {
    @Test
    fun `preview renders addon metadata without router or network`() = runTest {
        val report = MetadataAuditRunner.default().runCatalogFixture(
            fixtureName = "topstreaming_disney_mixed.json",
            fixtureJson = fixture("metadata/addons/topstreaming_disney_mixed.json"),
            scenario = MetadataAuditScenario(
                name = "preview-only",
                depth = MetadataDepth.PREVIEW,
                assertNoNetwork = true
            )
        )

        MetadataAuditAssertions.assertPreviewIsAddonOnly(report)
        assertEquals(AuditVerdict.PASS, report.verdict)
        assertTrue(report.items.all { it.routing == null })
        assertTrue(report.items.all { it.runtimeCalls.isEmpty() })
    }

    @Test
    fun `crunchyroll imdb anime routes to kitsu via mapping and not catalog hint`() = runTest {
        val report = MetadataAuditRunner.default().runCatalogFixture(
            fixtureName = "topstreaming_crunchyroll.json",
            fixtureJson = fixture("metadata/addons/topstreaming_crunchyroll.json"),
            scenario = MetadataAuditScenario(
                name = "detail-core-visible-crunchyroll",
                depth = MetadataDepth.DETAIL_CORE,
                visibleItemIds = setOf("tt12343534")
            )
        )

        val item = report.items.single()
        val route = item.routing

        assertEquals(MetadataPrimaryProvider.KITSU, route?.provider)
        assertEquals(MetadataDecisionReason.ID_MAPPING_TO_KITSU, route?.reason)
        assertFalse(route?.usedInputs.orEmpty().contains("catalog.id"))
        assertFalse(route?.usedInputs.orEmpty().contains("addon.name"))
        assertTrue(item.runtimeCalls.any { it.apiShapeId == "kitsu.anime.core" })
        MetadataAuditAssertions.assertNoCatalogHintsUsedForRouting(report)
        MetadataAuditAssertions.assertEveryRuntimeCallHasApiShape(report)
    }

    @Test
    fun `field resolver is only final output owner`() = runTest {
        val report = MetadataAuditRunner.default().runCatalogFixture(
            fixtureName = "marvel_movies.json",
            fixtureJson = fixture("metadata/addons/marvel_movies.json"),
            scenario = MetadataAuditScenario(
                name = "marvel-detail-core",
                depth = MetadataDepth.DETAIL_CORE,
                visibleItemIds = setOf("tt0036697")
            )
        )

        MetadataAuditAssertions.assertFacadeOwnsOutput(report)
        MetadataAuditAssertions.assertNoLegacyExecution(report.allEvents())
        assertTrue(report.items.single().selectedFields.any { it.field == "title" })
        assertEquals(
            listOf(ResolverType.ADDON_DISPLAY, ResolverType.RATING, ResolverType.ARTWORK),
            report.items.single().resolverSchedule?.resolversScheduled
        )
    }

    @Test
    fun `routing rules match spec for all id types`() = runTest {
        val reports = listOf(
            MetadataAuditRunner.default().runCatalogFixture(
                fixtureName = "anime_kitsu_trending.json",
                fixtureJson = fixture("metadata/addons/anime_kitsu_trending.json"),
                scenario = MetadataAuditScenario(
                    name = "kitsu-prefix",
                    depth = MetadataDepth.DETAIL_CORE,
                    visibleItemIds = setOf("kitsu:7442")
                )
            ),
            MetadataAuditRunner.default().runCatalogFixture(
                fixtureName = "anime_catalogs_mal.json",
                fixtureJson = fixture("metadata/addons/anime_catalogs_mal.json"),
                scenario = MetadataAuditScenario(
                    name = "mal-prefix",
                    depth = MetadataDepth.DETAIL_CORE,
                    visibleItemIds = setOf("mal:21")
                )
            ),
            MetadataAuditRunner.default().runCatalogFixture(
                fixtureName = "netflix_movie_nfx.json",
                fixtureJson = fixture("metadata/addons/netflix_movie_nfx.json"),
                scenario = MetadataAuditScenario(
                    name = "netflix-movie",
                    depth = MetadataDepth.DETAIL_CORE,
                    visibleItemIds = setOf("tt16431404")
                )
            ),
            MetadataAuditRunner.default().runCatalogFixture(
                fixtureName = "netflix_series_nfx.json",
                fixtureJson = fixture("metadata/addons/netflix_series_nfx.json"),
                scenario = MetadataAuditScenario(
                    name = "netflix-series",
                    depth = MetadataDepth.DETAIL_CORE,
                    visibleItemIds = setOf("tt14403178")
                )
            )
        )

        val routes = reports.associate { report -> report.items.single().itemId to report.items.single().routing!! }

        assertEquals(MetadataPrimaryProvider.KITSU, routes.getValue("kitsu:7442").provider)
        assertEquals(MetadataDecisionReason.KITSU_PREFIX_DIRECT, routes.getValue("kitsu:7442").reason)
        assertFalse(routes.getValue("kitsu:7442").usedInputs.contains("AnimeIdentityIndex"))
        assertEquals(MetadataPrimaryProvider.KITSU, routes.getValue("mal:21").provider)
        assertEquals(MetadataDecisionReason.ANIME_PREFIX_MAPPED_TO_KITSU, routes.getValue("mal:21").reason)
        assertEquals(MetadataPrimaryProvider.TMDB, routes.getValue("tt16431404").provider)
        assertEquals(MetadataDecisionReason.ITEM_TYPE_MOVIE, routes.getValue("tt16431404").reason)
        assertEquals(MetadataPrimaryProvider.TVDB, routes.getValue("tt14403178").provider)
        assertEquals(MetadataDecisionReason.ITEM_TYPE_SERIES, routes.getValue("tt14403178").reason)
        reports.forEach(MetadataAuditAssertions::assertNoCatalogHintsUsedForRouting)
    }

    @Test
    fun `report writer emits json and markdown artifacts`() = runTest {
        val report = MetadataAuditRunner.default().runCatalogFixture(
            fixtureName = "netflix_movie_nfx.json",
            fixtureJson = fixture("metadata/addons/netflix_movie_nfx.json"),
            scenario = MetadataAuditScenario(
                name = "netflix-movie-detail-core",
                depth = MetadataDepth.DETAIL_CORE,
                visibleItemIds = setOf("tt16431404")
            )
        )
        val outputDir = java.io.File("build/reports/metadata-audit")

        MetadataAuditReportWriter().writeJson(report, java.io.File(outputDir, "metadata-execution-report.json"))
        MetadataAuditReportWriter().writeMarkdown(report, java.io.File(outputDir, "metadata-execution-report.md"))

        assertTrue(java.io.File(outputDir, "metadata-execution-report.json").isFile)
        assertTrue(java.io.File(outputDir, "metadata-execution-report.md").readText().contains("Metadata Execution Audit"))
    }

    private fun fixture(path: String): String {
        val resource = javaClass.classLoader?.getResource(path)
            ?: error("Missing fixture $path")
        return resource.readText()
    }
}
