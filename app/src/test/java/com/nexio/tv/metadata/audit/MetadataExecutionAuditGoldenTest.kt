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
        assertTrue(scenarioNames.contains("tmdb-movie-core-warm-cache"))
        assertTrue(scenarioNames.contains("tvdb-series-core-warm-cache"))
        assertTrue(scenarioNames.contains("kitsu-anime-core-warm-cache"))
        assertTrue(scenarioNames.contains("stale-on-429"))
        assertTrue(scenarioNames.contains("production-caller-ownership"))
        assertTrue(scenarioNames.contains("tvdb-localized-english-fallback"))
        assertTrue(scenarioNames.contains("tmdb-localized-english-fallback"))
        assertTrue(scenarioNames.contains("kitsu-localized-field-fallback"))

        val allItems = bundle.reports.flatMap { it.items }
        assertTrue(allItems.any { it.itemId == "tt26443597" && it.routing?.provider == MetadataPrimaryProvider.TMDB })
        assertTrue(allItems.any { it.itemId == "tt27444205" && it.routing?.provider == MetadataPrimaryProvider.TVDB })
        assertTrue(allItems.any { it.itemId == "tt12343534" && it.routing?.provider == MetadataPrimaryProvider.KITSU })
        assertTrue(allItems.any { it.routing?.reason == MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT })
        assertTrue(bundle.reports.all { it.verdict == AuditVerdict.PASS })
        bundle.reports.forEach(MetadataAuditAssertions::assertLocalizationFallbackStaysWithinProvider)
    }

    @Test
    fun `built in rail preview scenarios are present in aggregate report`() = runTest {
        val bundle = MetadataAuditRunner.default().runDefaultScenarioBundle()
        val scenarioNames = bundle.reports.map { it.scenario.name }.toSet()

        assertTrue(scenarioNames.contains("trakt-rail-first-paint-title-year"))
        assertTrue(scenarioNames.contains("trakt-rail-visible-hydrates-tvdb"))
        assertTrue(scenarioNames.contains("mdblist-rail-first-paint-rich-preview"))
        assertTrue(scenarioNames.contains("tmdb-movie-rail-first-paint-rich-preview"))
        assertTrue(scenarioNames.contains("tmdb-tv-rail-preview-then-tvdb-hydration"))
        assertTrue(scenarioNames.contains("kitsu-rail-first-paint-rich-preview"))
        assertTrue(scenarioNames.contains("simkl-json-rail-first-paint-rich-preview"))
        assertTrue(scenarioNames.contains("simkl-json-rail-visible-hydrates-tmdb"))
    }

    @Test
    fun `localized audit scenarios record same provider english fallback policy`() = runTest {
        val bundle = MetadataAuditRunner.default().runDefaultScenarioBundle()

        val tvdb = bundle.localizedScenario("tvdb-localized-english-fallback")
        val tmdb = bundle.localizedScenario("tmdb-localized-english-fallback")
        val kitsu = bundle.localizedScenario("kitsu-localized-field-fallback")

        assertEquals(MetadataPrimaryProvider.TVDB, tvdb.provider)
        assertEquals("nld", tvdb.requestedLanguage)
        assertEquals("eng", tvdb.fallbackLanguage)
        assertFalse(tvdb.providerFallbackUsed)
        assertTrue(tvdb.payloads.any { it.language == "nld" && it.fallbackRole == "LOCALIZED" })
        assertTrue(tvdb.payloads.any { it.language == "eng" && it.fallbackRole == "LANGUAGE_FALLBACK" })

        assertEquals(MetadataPrimaryProvider.TMDB, tmdb.provider)
        assertEquals("nl-NL", tmdb.requestedLanguage)
        assertEquals("en-US", tmdb.fallbackLanguage)
        assertFalse(tmdb.providerFallbackUsed)

        assertEquals(MetadataPrimaryProvider.KITSU, kitsu.provider)
        assertEquals("nl", kitsu.requestedLanguage)
        assertEquals("en", kitsu.fallbackLanguage)
        assertFalse(kitsu.providerFallbackUsed)

        listOf(tvdb, tmdb, kitsu).forEach { localization ->
            assertEquals(2, localization.policyVersion)
            assertTrue(localization.payloads.isNotEmpty())
            assertTrue(localization.payloads.all { it.source == "PRODUCTION_ADAPTER" })
            assertTrue(localization.payloads.all { it.cacheKey.contains("policy:2") })
            assertTrue(localization.payloads.any { it.fallbackRole == "LOCALIZED" })
            assertTrue(localization.payloads.any { it.language == localization.fallbackLanguage && !it.executedNetwork })
        }
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
    fun `fresh cache hit suppresses provider network for primary metadata`() = runTest {
        val bundle = MetadataAuditRunner.default().runDefaultScenarioBundle()
        val warmReports = bundle.reports.filter { it.scenario.cacheMode == AuditCacheMode.WARM_FRESH }
        val warmDecisions = warmReports.flatMap { it.items }.flatMap { it.cacheDecisions }
        val warmCalls = warmReports.flatMap { it.items }.flatMap { it.runtimeCalls }

        assertTrue(warmDecisions.any { it.apiShapeId == "tmdb.movie.core" && it.decision == CacheDecision.HIT })
        assertTrue(warmDecisions.any { it.apiShapeId == "tvdb.series.extended" && it.decision == CacheDecision.HIT })
        assertTrue(warmDecisions.any { it.apiShapeId == "kitsu.anime.core" && it.decision == CacheDecision.HIT })
        assertTrue(warmCalls.all { !it.executedNetwork })
    }

    @Test
    fun `stale cache is served on forced 429 without network success`() = runTest {
        val report = MetadataAuditRunner.default().runCatalogFixture(
            fixtureName = "netflix_movie_nfx.json",
            fixtureJson = fixture("metadata/addons/netflix_movie_nfx.json"),
            scenario = MetadataAuditScenario(
                name = "stale-on-429",
                depth = MetadataDepth.DETAIL_CORE,
                visibleItemIds = setOf("tt16431404"),
                cacheMode = AuditCacheMode.FORCE_429
            )
        )

        val item = report.items.single()
        assertTrue(item.cacheDecisions.any { it.decision == CacheDecision.STALE_HIT })
        assertTrue(item.runtimeCalls.all { !it.executedNetwork })
    }

    @Test
    fun `premium artwork providers win poster without refetching primary metadata`() = runTest {
        val bundle = MetadataAuditRunner.default().runDefaultScenarioBundle()
        val topposters = bundle.reports.single { it.scenario.name == "premium-artwork-topposters" }.items.single()
        val rpdb = bundle.reports.single { it.scenario.name == "premium-artwork-rpdb" }.items.single()

        assertEquals("TOP_POSTERS", topposters.selectedFields.single { it.field == "poster" }.selectedProvider)
        assertEquals("RPDB", rpdb.selectedFields.single { it.field == "poster" }.selectedProvider)
        assertTrue(topposters.runtimeCalls.any { it.apiShapeId == "topposters.poster_template" })
        assertTrue(rpdb.runtimeCalls.any { it.apiShapeId == "rpdb.poster_template" })
        assertTrue((topposters.cacheDecisions + rpdb.cacheDecisions).any { it.apiShapeId == "tmdb.movie.core" && it.decision == CacheDecision.HIT })
        assertTrue((topposters.runtimeCalls + rpdb.runtimeCalls).none {
            it.apiShapeId == "tmdb.movie.core" && it.executedNetwork
        })
    }

    @Test
    fun `provider native conflict records identity resolution before execution`() = runTest {
        val report = MetadataAuditRunner.default().runCatalogFixture(
            fixtureName = "provider_native_conflict.json",
            fixtureJson = fixture("metadata/addons/provider_native_conflict.json"),
            scenario = MetadataAuditScenario(
                name = "provider-native-conflict",
                depth = MetadataDepth.DETAIL_CORE,
                visibleItemIds = setOf("tmdb:1399")
            )
        )
        val item = report.items.single()

        assertEquals(MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT, item.routing?.reason)
        assertTrue(item.routing?.preResolutionTargetIdRequiresIdentityResolution == true)
        assertFalse(item.routing?.targetIdRequiresIdentityResolution == true)
        assertEquals("tmdb:1399", item.identityResolution?.sourceId)
        assertEquals(MetadataPrimaryProvider.TVDB, item.identityResolution?.targetProvider)
        assertEquals("tvdb.remoteid.lookup", item.identityResolution?.apiShapeId)
        assertTrue(item.identityResolution?.success == true)
        assertTrue(item.runtimeCalls.none { it.apiShapeId == "tvdb.series.extended" && it.operationKey.contains("tmdb:") })
    }

    @Test
    fun `production caller ownership is represented and legacy free`() = runTest {
        val report = MetadataAuditRunner.default().runCatalogFixture(
            fixtureName = "netflix_movie_nfx.json",
            fixtureJson = fixture("metadata/addons/netflix_movie_nfx.json"),
            scenario = MetadataAuditScenario(
                name = "production-caller-ownership",
                depth = MetadataDepth.DETAIL_CORE,
                visibleItemIds = setOf("tt16431404"),
                productionCallerOwnership = true
            )
        )
        val events = report.items.single().productionCallerOwnership
        val pathNames = events.map { it.pathName }.toSet()

        assertTrue(pathNames.contains("home_catalog"))
        assertTrue(pathNames.contains("detail_screen"))
        assertTrue(pathNames.contains("player_start"))
        assertTrue(pathNames.contains("continue_watching_write"))
        assertTrue(pathNames.contains("continue_watching_render"))
        assertTrue(events.all { it.facadeOrRepositoryCalled && it.providerPlanRunnerExpected && it.fieldResolverExpected })
        assertTrue(events.none { it.legacyRouterUsedAfterFacade })
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
        assertTrue(json.contains("\"schemaVersion\""))
        assertTrue(json.contains("\"gitSha\""))
        assertTrue(json.contains("\"gitWorktree\""))
        assertTrue(json.contains("\"artifactRole\": \"SIGN_OFF_AGGREGATE\""))
        assertTrue(json.contains("preResolutionTargetIdRequiresIdentityResolution"))
        assertTrue(json.contains("executionIdentityResolved"))
        assertTrue(json.contains("crunchyroll-imdb-anime-detail-core"))
        assertTrue(json.contains("field-ownership-conflict"))
        assertTrue(json.contains("identityResolution"))
        assertTrue(json.contains("\"localization\""))
        assertTrue(json.contains("\"providerFallbackUsed\""))
        assertTrue(json.contains("productionCallerOwnership"))
        assertTrue(markdown.contains("Metadata Execution Audit Bundle"))
        assertTrue(markdown.contains("Git SHA"))
        assertTrue(markdown.contains("Artifact role"))
        assertTrue(markdown.contains("Pre-resolution identity required"))
        assertTrue(markdown.contains("Execution identity resolved"))
        assertTrue(markdown.contains("provider-native-conflict"))
        assertTrue(markdown.contains("Identity resolution"))
        assertTrue(markdown.contains("Localization"))
        assertTrue(markdown.contains("Production caller ownership"))
    }

    @Test
    fun `single report writer marks artifacts as smoke only`() = runTest {
        val report = MetadataAuditRunner.default().runCatalogFixture(
            fixtureName = "netflix_movie_nfx.json",
            fixtureJson = fixture("metadata/addons/netflix_movie_nfx.json"),
            scenario = MetadataAuditScenario(
                name = "netflix-movie-detail-core",
                depth = MetadataDepth.DETAIL_CORE,
                visibleItemIds = setOf("tt16431404")
            )
        )
        val outputDir = File("build/reports/metadata-audit")

        MetadataAuditReportWriter().writeJson(report, File(outputDir, "metadata-execution-single-report.json"))
        MetadataAuditReportWriter().writeMarkdown(report, File(outputDir, "metadata-execution-single-report.md"))

        assertTrue(File(outputDir, "metadata-execution-single-report.json").readText().contains("SMOKE_DEBUG_ONLY"))
        assertTrue(File(outputDir, "metadata-execution-single-report.md").readText().contains("Smoke/debug artifact only"))
    }

    private fun fixture(path: String): String {
        val resource = javaClass.classLoader?.getResource(path)
            ?: error("Missing fixture $path")
        return resource.readText()
    }

    private fun MetadataExecutionReportBundle.localizedScenario(name: String): LocalizationEvent =
        reports.single { it.scenario.name == name }.items.single().localization
            ?: error("Missing localization event for $name")

    private val Int.daysMs: Long get() = this * 24L * 60L * 60L * 1_000L
}
