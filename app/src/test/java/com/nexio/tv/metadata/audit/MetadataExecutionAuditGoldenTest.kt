package com.nexio.tv.metadata.audit

import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolverType
import java.io.File
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

        MetadataAuditReportWriter().writeJson(report, java.io.File(outputDir, "metadata-execution-single-report.json"))
        MetadataAuditReportWriter().writeMarkdown(report, java.io.File(outputDir, "metadata-execution-single-report.md"))

        assertTrue(java.io.File(outputDir, "metadata-execution-single-report.json").isFile)
        assertTrue(java.io.File(outputDir, "metadata-execution-single-report.md").readText().contains("Metadata Execution Audit"))
    }

    @Test
    fun `metadata audit bundle exports full production readiness scenario matrix`() = runTest {
        val bundle = MetadataAuditRunner.default().runDefaultScenarioBundle()
        val scenarioNames = bundle.reports.map { it.scenario.name }.toSet()

        assertTrue(scenarioNames.contains("preview-only-disney-mixed"))
        assertTrue(scenarioNames.contains("disney-mixed-visible-items"))
        assertTrue(scenarioNames.contains("crunchyroll-imdb-anime-detail-core"))
        assertTrue(scenarioNames.contains("kitsu-prefix-detail-core"))
        assertTrue(scenarioNames.contains("mal-prefix-detail-core"))
        assertTrue(scenarioNames.contains("tvdb-series-detail-core"))
        assertTrue(scenarioNames.contains("provider-native-conflict"))
        assertTrue(scenarioNames.contains("premium-artwork-topposters"))
        assertTrue(scenarioNames.contains("premium-artwork-rpdb"))
        assertTrue(scenarioNames.contains("continue-watching-local-playback"))
        assertTrue(scenarioNames.contains("continue-watching-stale-routing-version"))
        assertTrue(scenarioNames.contains("field-ownership-conflict"))

        val allItems = bundle.reports.flatMap { it.items }
        assertTrue(allItems.any { it.itemId == "tt26443597" && it.routing?.provider == MetadataPrimaryProvider.TMDB })
        assertTrue(allItems.any { it.itemId == "tt27444205" && it.routing?.provider == MetadataPrimaryProvider.TVDB })
        assertTrue(allItems.any { it.itemId == "tt12343534" && it.routing?.provider == MetadataPrimaryProvider.KITSU })
        assertTrue(allItems.any { it.routing?.reason == MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT })
        assertTrue(bundle.reports.all { it.verdict == AuditVerdict.PASS })
    }

    @Test
    fun `runtime cache decisions use contract TTLs not placeholder TTLs`() = runTest {
        val bundle = MetadataAuditRunner.default().runDefaultScenarioBundle()
        val decisionsByShape = bundle.reports
            .flatMap { it.items }
            .flatMap { it.cacheDecisions }
            .associateBy { it.apiShapeId }

        assertEquals(7.daysMs, decisionsByShape.getValue("tmdb.movie.core").ttlMs)
        assertEquals(30.daysMs, decisionsByShape.getValue("tmdb.movie.core").staleWindowMs)
        assertEquals(7.daysMs, decisionsByShape.getValue("tvdb.series.extended").ttlMs)
        assertEquals(30.daysMs, decisionsByShape.getValue("tvdb.series.extended").staleWindowMs)
        assertEquals(7.daysMs, decisionsByShape.getValue("kitsu.anime.core").ttlMs)
        assertEquals(30.daysMs, decisionsByShape.getValue("kitsu.anime.core").staleWindowMs)
    }

    @Test
    fun `field ownership conflict reports rejected secondary candidate`() = runTest {
        val report = MetadataAuditRunner.default().runCatalogFixture(
            fixtureName = "marvel_movies.json",
            fixtureJson = fixture("metadata/addons/marvel_movies.json"),
            scenario = MetadataAuditScenario(
                name = "field-ownership-conflict",
                depth = MetadataDepth.DETAIL_CORE,
                visibleItemIds = setOf("tt0036697"),
                injectSecondaryTitleOverwrite = true
            )
        )
        val item = report.items.single()
        val title = item.selectedFields.single { it.field == "title" }

        assertTrue(item.forbiddenOverwrites.any { it.field == "title" })
        assertTrue(title.rejectedCandidates.any { it.reason.contains("PRIMARY") })
    }

    @Test
    fun `bundle writer exports coherent combined json and markdown reports`() = runTest {
        val bundle = MetadataAuditRunner.default().runDefaultScenarioBundle()
        val outputDir = File("build/reports/metadata-audit")

        MetadataAuditReportWriter().writeBundleJson(bundle, File(outputDir, "metadata-execution-report.json"))
        MetadataAuditReportWriter().writeBundleMarkdown(bundle, File(outputDir, "metadata-execution-report.md"))

        val json = File(outputDir, "metadata-execution-report.json").readText()
        val markdown = File(outputDir, "metadata-execution-report.md").readText()
        assertTrue(json.contains("crunchyroll-imdb-anime-detail-core"))
        assertTrue(json.contains("field-ownership-conflict"))
        assertTrue(markdown.contains("Metadata Execution Audit Bundle"))
        assertTrue(markdown.contains("provider-native-conflict"))
    }

    private fun fixture(path: String): String {
        val resource = javaClass.classLoader?.getResource(path)
            ?: error("Missing fixture $path")
        return resource.readText()
    }

    private val Int.daysMs: Long get() = this * 24L * 60L * 60L * 1_000L
}
