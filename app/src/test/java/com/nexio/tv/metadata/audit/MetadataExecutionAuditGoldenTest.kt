package com.nexio.tv.metadata.audit

import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolverType
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
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
        val report = syntheticReport("marvel-detail-core", item = syntheticItem("tt0036697"))

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
                fixtureName = "provider_native_conflict.json",
                fixtureJson = fixture("metadata/addons/provider_native_conflict.json"),
                scenario = MetadataAuditScenario(
                    name = "tmdb-tv-series",
                    depth = MetadataDepth.DETAIL_CORE,
                    visibleItemIds = setOf("tmdb:1399")
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
        assertEquals(MetadataPrimaryProvider.TMDB, routes.getValue("tmdb:1399").provider)
        assertEquals(MetadataDecisionReason.PROVIDER_NATIVE_DIRECT, routes.getValue("tmdb:1399").reason)
        assertEquals("tmdb:1399", routes.getValue("tmdb:1399").targetIds[MetadataPrimaryProvider.TMDB])
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
    fun `report writer omits nullable rejected candidate source roles`() {
        val selectedField = FieldSelectedEvent(
            itemId = "item-1",
            field = "title",
            selectedProvider = "TMDB",
            sourceRole = "PRIMARY",
            valuePreview = "Selected title",
            rejectedCandidates = listOf(
                RejectedCandidateReport(
                    provider = "KITSU",
                    reason = "owner selected higher priority value"
                ),
                RejectedCandidateReport(
                    provider = "TRAKT",
                    sourceRole = "RAIL_PREVIEW",
                    reason = "primary canonical field available"
                )
            ),
            ownershipRule = "title selected from PRIMARY"
        )
        val report = MetadataExecutionReport(
            schemaVersion = 1,
            provenance = MetadataAuditProvenance(
                gitSha = "test-sha",
                gitWorktree = GitWorktreeState(
                    state = "CLEAN",
                    dirtyFileCount = 0,
                    untrackedFileCount = 0
                )
            ),
            verdict = AuditVerdict.PASS,
            scenario = MetadataAuditScenario(
                name = "writer-regression",
                depth = MetadataDepth.DETAIL_CORE
            ),
            fixtureName = "writer-regression.json",
            generatedAtEpochMs = 0,
            items = listOf(
                ItemExecutionReport(
                    itemId = "item-1",
                    itemType = "movie",
                    addonFields = emptyMap(),
                    firstPaint = FirstPaintEvent(
                        itemId = "item-1",
                        itemType = "movie",
                        fieldsUsed = emptySet(),
                        routerExecuted = false,
                        networkExecuted = false
                    ),
                    routing = null,
                    providerPlan = null,
                    runtimeCalls = emptyList(),
                    cacheDecisions = emptyList(),
                    resolverSchedule = null,
                    selectedFields = listOf(selectedField),
                    forbiddenOverwrites = emptyList(),
                    continueWatchingSnapshot = null,
                    identityResolution = null,
                    productionCallerOwnership = emptyList(),
                    localization = null,
                    violations = emptyList(),
                    events = emptyList(),
                    selectedFieldsAfterHydration = listOf(selectedField)
                )
            ),
            summaries = AuditSummaries(
                totalItems = 1,
                routedItems = 0,
                networkCalls = 0,
                cacheHits = 0,
                cacheMisses = 0,
                staleHits = 0,
                forbiddenOverwrites = 0,
                policyViolations = 0,
                providersUsed = emptyMap(),
                apiShapesUsed = emptyMap()
            ),
            policyViolations = emptyList()
        )
        val outputDir = File("build/reports/metadata-audit/writer-regression")
        val jsonFile = File(outputDir, "metadata-execution-single-report.json")
        val markdownFile = File(outputDir, "metadata-execution-single-report.md")

        MetadataAuditReportWriter().writeJson(report, jsonFile)
        MetadataAuditReportWriter().writeMarkdown(report, markdownFile)

        val jsonText = jsonFile.readText()
        val rejectedCandidates = JSONObject(jsonText)
            .getJSONArray("items")
            .getJSONObject(0)
            .getJSONArray("selectedFieldsAfterHydration")
            .getJSONObject(0)
            .getJSONArray("rejectedCandidates")
        val nonRailCandidate = rejectedCandidates.getJSONObject(0)
        val railCandidate = rejectedCandidates.getJSONObject(1)
        val markdownText = markdownFile.readText()

        assertFalse(jsonText.contains("\"sourceRole\":\"\""))
        assertFalse(nonRailCandidate.has("sourceRole"))
        assertEquals("RAIL_PREVIEW", railCandidate.getString("sourceRole"))
        assertTrue(markdownText.contains("KITSU: owner selected higher priority value"))
        assertFalse(markdownText.contains("KITSU::"))
        assertTrue(markdownText.contains("TRAKT/RAIL_PREVIEW: primary canonical field available"))
    }

    @Test
    fun `stable id bundle report preserves tmdb tv canonical with tvdb sidecar`() {
        val stableIdBundle = StableIdBundleEvent(
            itemKey = "series:tmdb:tv:1399",
            itemType = "series",
            trigger = "VISIBLE_HOME_HYDRATION",
            status = "RESOLVED_WITH_SIDECARS",
            canonicalProvider = "TMDB",
            canonicalId = "tmdb:tv:1399",
            imdbId = "tt0944947",
            networkExecuted = false,
            evidence = listOf(
                StableIdBundleEvidenceEvent(
                    source = "knownIds",
                    target = "TMDB",
                    networkExecuted = false,
                    resultId = "tmdb:tv:1399"
                ),
                StableIdBundleEvidenceEvent(
                    source = "knownIds",
                    target = "TVDB",
                    networkExecuted = false,
                    resultId = "tvdb:121361"
                )
            )
        )
        val report = MetadataExecutionReport(
            schemaVersion = 1,
            provenance = MetadataAuditProvenance(
                gitSha = "test-sha",
                gitWorktree = GitWorktreeState(
                    state = "CLEAN",
                    dirtyFileCount = 0,
                    untrackedFileCount = 0
                )
            ),
            verdict = AuditVerdict.PASS,
            scenario = MetadataAuditScenario(
                name = "stable-id-bundle-missing-canonical",
                depth = MetadataDepth.DETAIL_CORE
            ),
            fixtureName = "synthetic/metadata/rails/stable-id-bundle-missing-canonical.json",
            generatedAtEpochMs = 0,
            items = listOf(
                ItemExecutionReport(
                    itemId = "tmdb:tv:1399",
                    itemType = "series",
                    addonFields = emptyMap(),
                    firstPaint = FirstPaintEvent(
                        itemId = "tmdb:tv:1399",
                        itemType = "series",
                        source = "RAIL_PREVIEW",
                        fieldsUsed = emptySet(),
                        routerExecuted = false,
                        networkExecuted = false
                    ),
                    routing = null,
                    stableIdBundle = stableIdBundle,
                    providerPlan = null,
                    runtimeCalls = emptyList(),
                    cacheDecisions = emptyList(),
                    resolverSchedule = null,
                    selectedFields = emptyList(),
                    forbiddenOverwrites = emptyList(),
                    continueWatchingSnapshot = null,
                    identityResolution = null,
                    productionCallerOwnership = emptyList(),
                    localization = null,
                    violations = emptyList(),
                    events = listOf(AuditEvent.StableIdBundle(stableIdBundle))
                )
            ),
            summaries = AuditSummaries(
                totalItems = 1,
                routedItems = 0,
                networkCalls = 0,
                cacheHits = 0,
                cacheMisses = 0,
                staleHits = 0,
                forbiddenOverwrites = 0,
                policyViolations = 0,
                providersUsed = emptyMap(),
                apiShapesUsed = emptyMap()
            ),
            policyViolations = emptyList()
        )
        val outputDir = File("build/reports/metadata-audit/stable-id-bundle-missing-canonical")
        val jsonFile = File(outputDir, "metadata-execution-single-report.json")
        val markdownFile = File(outputDir, "metadata-execution-single-report.md")

        MetadataAuditReportWriter().writeJson(report, jsonFile)
        MetadataAuditReportWriter().writeMarkdown(report, markdownFile)

        val bundleJson = JSONObject(jsonFile.readText())
            .getJSONArray("items")
            .getJSONObject(0)
            .getJSONObject("metadata.stable_id_bundle")
        val markdown = markdownFile.readText()

        assertEquals("TMDB", bundleJson.getString("canonicalProvider"))
        assertEquals("tmdb:tv:1399", bundleJson.getString("canonicalId"))
        assertEquals("tt0944947", bundleJson.getString("imdbId"))
        assertEquals("RESOLVED_WITH_SIDECARS", bundleJson.getString("status"))
        assertEquals("TVDB", bundleJson.getJSONArray("evidence").getJSONObject(1).getString("target"))
        assertEquals("tvdb:121361", bundleJson.getJSONArray("evidence").getJSONObject(1).getString("resultId"))
        assertFalse(bundleJson.getBoolean("networkExecuted"))
        assertTrue(markdown.contains("Stable ID Bundle"))
        assertTrue(markdown.contains("tmdb:tv:1399"))
    }

    @Test
    fun `metadata audit bundle exports full production readiness scenario matrix`() = runTest {
        val bundle = goldenBundle()
        val scenarioNames = bundle.reports.map { it.scenario.name }.toSet()

        assertTrue(scenarioNames.contains("preview-only-disney-mixed"))
        assertTrue(scenarioNames.contains("disney-mixed-visible-items"))
        assertTrue(scenarioNames.contains("crunchyroll-imdb-anime-detail-core"))
        assertTrue(scenarioNames.contains("kitsu-prefix-detail-core"))
        assertTrue(scenarioNames.contains("mal-prefix-detail-core"))
        assertTrue(scenarioNames.contains("tmdb-tv-detail-core"))
        assertTrue(scenarioNames.contains("provider-native-conflict"))
        assertTrue(scenarioNames.contains("premium-artwork-topposters"))
        assertTrue(scenarioNames.contains("premium-artwork-rpdb"))
        assertTrue(scenarioNames.contains("premium-artwork-topposters-home"))
        assertTrue(scenarioNames.contains("premium-artwork-rpdb-home"))
        assertTrue(scenarioNames.contains("premium-artwork-topposters-detail"))
        assertTrue(scenarioNames.contains("premium-artwork-rpdb-detail"))
        assertTrue(scenarioNames.contains("premium-artwork-switch-provider"))
        assertTrue(scenarioNames.contains("premium-artwork-cache-hit"))
        assertTrue(scenarioNames.contains("premium-artwork-failure-fallback"))
        assertTrue(scenarioNames.contains("continue-watching-local-playback"))
        assertTrue(scenarioNames.contains("continue-watching-stale-routing-version"))
        assertTrue(scenarioNames.contains("field-ownership-conflict"))
        assertTrue(scenarioNames.contains("tmdb-movie-core-warm-cache"))
        assertTrue(scenarioNames.contains("tmdb-tv-core-warm-cache"))
        assertTrue(scenarioNames.contains("kitsu-anime-core-warm-cache"))
        assertTrue(scenarioNames.contains("stale-on-429"))
        assertTrue(scenarioNames.contains("production-caller-ownership"))
        assertTrue(scenarioNames.contains("tmdb-tv-localized-english-fallback"))
        assertTrue(scenarioNames.contains("tmdb-localized-english-fallback"))
        assertTrue(scenarioNames.contains("kitsu-localized-field-fallback"))

        val allItems = bundle.reports.flatMap { it.items }
        assertTrue(allItems.any { it.itemId == "tt26443597" && it.routing?.provider == MetadataPrimaryProvider.TMDB })
        assertTrue(allItems.any { it.itemId == "tmdb:tv:1399" && it.routing?.provider == MetadataPrimaryProvider.TMDB })
        assertTrue(allItems.any { it.itemId == "tt12343534" && it.routing?.provider == MetadataPrimaryProvider.KITSU })
        assertTrue(allItems.any { it.itemId == "tmdb:tv:1399" && it.routing?.targetIds?.get(MetadataPrimaryProvider.TVDB) == "tvdb:121361" })
        assertTrue(bundle.reports.all { it.verdict == AuditVerdict.PASS })
        bundle.reports.forEach(MetadataAuditAssertions::assertLocalizationFallbackStaysWithinProvider)
    }

    @Test
    fun `built in rail preview scenarios are present in aggregate report`() = runTest {
        val bundle = goldenBundle()
        val scenarioNames = bundle.reports.map { it.scenario.name }.toSet()

        assertTrue(scenarioNames.contains("trakt-rail-first-paint-title-year"))
        assertTrue(scenarioNames.contains("trakt-rail-visible-hydrates-tmdb"))
        assertTrue(scenarioNames.contains("mdblist-rail-first-paint-rich-preview"))
        assertTrue(scenarioNames.contains("tmdb-movie-rail-first-paint-rich-preview"))
        assertTrue(scenarioNames.contains("tmdb-tv-rail-preview-then-tmdb-hydration"))
        assertTrue(scenarioNames.contains("kitsu-rail-first-paint-rich-preview"))
        assertTrue(scenarioNames.contains("simkl-json-rail-first-paint-rich-preview"))
        assertTrue(scenarioNames.contains("simkl-json-rail-visible-hydrates-tmdb"))
    }

    @Test
    fun `reactive home update scenarios are present in aggregate report`() = runTest {
        val bundle = goldenBundle()
        val scenarioNames = bundle.reports.map { it.scenario.name }.toSet()

        assertTrue(scenarioNames.contains("addon_first_paint_then_hydrated_home_update"))
        assertTrue(scenarioNames.contains("trakt_rail_first_paint_then_tvdb_update"))
        assertTrue(scenarioNames.contains("tmdb_movie_rail_first_paint_then_tmdb_update"))
        assertTrue(scenarioNames.contains("tmdb_tv_rail_first_paint_then_tmdb_update"))
        assertTrue(scenarioNames.contains("kitsu_rail_first_paint_then_kitsu_update"))
        assertTrue(scenarioNames.contains("simkl_rail_first_paint_then_tmdb_update"))
        assertTrue(scenarioNames.contains("hydration_failure_keeps_preview"))
        assertTrue(scenarioNames.contains("cache_hit_updates_home_without_network"))
        assertTrue(scenarioNames.contains("focused_item_hydrates_before_offscreen_items"))
        assertTrue(scenarioNames.contains("hydration_result_ignored_after_profile_switch"))
    }

    @Test
    fun `tmdb movie home update proves imdb ratings enrichment refreshes card without first paint work`() = runTest {
        val bundle = goldenBundle()
        val item = bundle.reports
            .single { it.scenario.name == "tmdb_movie_rail_first_paint_then_tmdb_update" }
            .items
            .single()

        assertEquals("RAIL_PREVIEW", item.firstPaint.source)
        assertFalse(item.firstPaint.routerExecuted)
        assertFalse(item.firstPaint.networkExecuted)
        assertFalse(item.homeUpdate?.rowOrderChanged ?: true)
        assertFalse(item.homeUpdate?.focusChanged ?: true)
        assertTrue(item.homeUpdate?.changedFields?.contains("rating") == true)
        assertEquals("tt0137523", item.stableIdBundle?.imdbId)
        assertTrue(item.runtimeCalls.any { it.apiShapeId == "custom_imdb.ratings" })
    }

    @Test
    fun `reactive home update scenarios keep row order and focus stable`() = runTest {
        val bundle = goldenBundle()
        val updateReports = bundle.reports.filter { it.fixtureName.startsWith("synthetic/metadata/home-updates/") }

        assertEquals(10, updateReports.size)
        updateReports.flatMap { it.items }.forEach { item ->
            assertFalse(item.firstPaint.routerExecuted)
            assertFalse(item.firstPaint.networkExecuted)
            assertFalse(item.homeUpdate?.rowOrderChanged ?: true)
            assertFalse(item.homeUpdate?.focusChanged ?: true)
        }
    }

    @Test
    fun `rail audit scenarios use shared first paint provenance shape`() = runTest {
        val bundle = goldenBundle()
        val railReport = bundle.reports.first { it.scenario.name == "trakt-rail-first-paint-title-year" }
        val item = railReport.items.single()

        assertEquals("RAIL_PREVIEW", item.firstPaint.source)
        assertFalse(item.firstPaint.routerExecuted)
        assertFalse(item.firstPaint.networkExecuted)
        assertEquals("BUILT_IN_TRAKT", item.railSource)
        assertEquals("TRAKT", item.sourceProvider)
        assertTrue(item.events.any { event ->
            event is AuditEvent.FirstPaint && event.event.source == "RAIL_PREVIEW"
        })
    }

    @Test
    fun `hydrated rail audit fields reject rail preview candidates when primary fields replace them`() = runTest {
        val bundle = goldenBundle()
        val item = bundle.reports
            .first { it.scenario.name == "trakt-rail-visible-hydrates-tmdb" }
            .items
            .single()
        val title = item.selectedFieldsAfterHydration.single { it.field == "title" }
        val poster = item.selectedFieldsAfterHydration.single { it.field == "poster" }
        val overview = item.selectedFieldsAfterHydration.single { it.field == "overview" }

        assertTrue(title.rejectedCandidates.any { candidate ->
            candidate.provider == item.sourceProvider &&
                candidate.sourceRole == "RAIL_PREVIEW" &&
                candidate.reason == "primary canonical field available"
        })
        assertTrue(poster.rejectedCandidates.isEmpty())
        assertTrue(overview.rejectedCandidates.isEmpty())
    }

    @Test
    fun `localized audit scenarios record same provider english fallback policy`() = runTest {
        val bundle = goldenBundle()

        val tmdbTv = bundle.localizedScenario("tmdb-tv-localized-english-fallback")
        val tmdb = bundle.localizedScenario("tmdb-localized-english-fallback")
        val kitsu = bundle.localizedScenario("kitsu-localized-field-fallback")

        assertEquals(MetadataPrimaryProvider.TMDB, tmdbTv.provider)
        assertEquals("nl-NL", tmdbTv.requestedLanguage)
        assertEquals("en-US", tmdbTv.fallbackLanguage)
        assertFalse(tmdbTv.providerFallbackUsed)
        assertTrue(tmdbTv.payloads.any { it.language == "nl-NL" && it.fallbackRole == "LOCALIZED" })
        assertTrue(tmdbTv.payloads.any { it.language == "en-US" && it.fallbackRole == "LANGUAGE_FALLBACK" })

        assertEquals(MetadataPrimaryProvider.TMDB, tmdb.provider)
        assertEquals("nl-NL", tmdb.requestedLanguage)
        assertEquals("en-US", tmdb.fallbackLanguage)
        assertFalse(tmdb.providerFallbackUsed)

        assertEquals(MetadataPrimaryProvider.KITSU, kitsu.provider)
        assertEquals("nl", kitsu.requestedLanguage)
        assertEquals("en", kitsu.fallbackLanguage)
        assertFalse(kitsu.providerFallbackUsed)

        listOf(tmdbTv, tmdb, kitsu).forEach { localization ->
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
        val bundle = goldenBundle()
        val decisionsByShape = bundle.reports
            .flatMap { it.items }
            .flatMap { it.cacheDecisions }
            .associateBy { it.apiShapeId }

        assertEquals(7.daysMs, decisionsByShape.getValue("tmdb.movie.core").ttlMs)
        assertEquals(30.daysMs, decisionsByShape.getValue("tmdb.movie.core").staleWindowMs)
        assertEquals(7.daysMs, decisionsByShape.getValue("tmdb.tv.core").ttlMs)
        assertEquals(30.daysMs, decisionsByShape.getValue("tmdb.tv.core").staleWindowMs)
        assertEquals(7.daysMs, decisionsByShape.getValue("kitsu.anime.core").ttlMs)
        assertEquals(30.daysMs, decisionsByShape.getValue("kitsu.anime.core").staleWindowMs)
    }

    @Test
    fun `fresh cache hit suppresses provider network for primary metadata`() = runTest {
        val bundle = goldenBundle()
        val warmReports = bundle.reports.filter { it.scenario.cacheMode == AuditCacheMode.WARM_FRESH }
        val warmDecisions = warmReports.flatMap { it.items }.flatMap { it.cacheDecisions }
        val warmCalls = warmReports.flatMap { it.items }.flatMap { it.runtimeCalls }

        assertTrue(warmDecisions.any { it.apiShapeId == "tmdb.movie.core" && it.decision == CacheDecision.HIT })
        assertTrue(warmDecisions.any { it.apiShapeId == "tmdb.tv.core" && it.decision == CacheDecision.HIT })
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
        val bundle = goldenBundle()
        val topposters = bundle.reports.single { it.scenario.name == "premium-artwork-topposters" }.items.single()
        val rpdb = bundle.reports.single { it.scenario.name == "premium-artwork-rpdb" }.items.single()

        val toppostersPoster = topposters.selectedFields.single { it.field == "poster" }
        val rpdbPoster = rpdb.selectedFields.single { it.field == "poster" }
        assertEquals("TOP_POSTERS", toppostersPoster.selectedProvider)
        assertEquals("ARTWORK", toppostersPoster.sourceRole)
        assertEquals("premium artwork may override poster only", toppostersPoster.ownershipRule)
        assertTrue(
            "topposters rejected candidates: ${toppostersPoster.rejectedCandidates}",
            toppostersPoster.rejectedCandidates.any { rejected ->
                rejected.provider == "TMDB" &&
                    rejected.sourceRole == "ADDON_PREVIEW" &&
                    rejected.reason == "premium_artwork_provider_precedence"
            }
        )
        assertEquals("RPDB", rpdbPoster.selectedProvider)
        assertEquals("ARTWORK", rpdbPoster.sourceRole)
        assertEquals("premium artwork may override poster only", rpdbPoster.ownershipRule)
        assertTrue(
            "rpdb rejected candidates: ${rpdbPoster.rejectedCandidates}",
            rpdbPoster.rejectedCandidates.any { rejected ->
                rejected.provider == "TMDB" &&
                    rejected.sourceRole == "ADDON_PREVIEW" &&
                    rejected.reason == "premium_artwork_provider_precedence"
            }
        )
        assertTrue(topposters.runtimeCalls.any { it.apiShapeId == "topposters.poster_template" })
        assertTrue(rpdb.runtimeCalls.any { it.apiShapeId == "rpdb.poster_template" })
        assertTrue((topposters.cacheDecisions + rpdb.cacheDecisions).any { it.apiShapeId == "tmdb.movie.core" && it.decision == CacheDecision.HIT })
        assertTrue((topposters.runtimeCalls + rpdb.runtimeCalls).none {
            it.apiShapeId == "tmdb.movie.core" && it.executedNetwork
        })
    }

    @Test
    fun `premium artwork audit records cache proof and internal ui model`() = runTest {
        val bundle = goldenBundle()
        val topposters = bundle.reports.single { it.scenario.name == "premium-artwork-topposters-home" }.items.single()
        val rpdb = bundle.reports.single { it.scenario.name == "premium-artwork-rpdb-detail" }.items.single()
        val switchedProvider = bundle.reports.single { it.scenario.name == "premium-artwork-switch-provider" }.items.single()
        val cacheHit = bundle.reports.single { it.scenario.name == "premium-artwork-cache-hit" }.items.single()

        val toppostersPoster = topposters.artworkAudit.single { it.field == "poster" }
        assertEquals("TOP_POSTERS", toppostersPoster.selectedProvider)
        assertEquals("ARTWORK", toppostersPoster.sourceRole)
        assertEquals("topposters.poster_template", toppostersPoster.runtimeApiShapeId)
        assertEquals("HIT", toppostersPoster.assetCacheDecision)
        assertFalse(toppostersPoster.networkExecuted)
        assertTrue(toppostersPoster.decisionKey?.startsWith("artwork:decision:") == true)
        assertTrue(toppostersPoster.assetKey?.startsWith("artwork:asset:") == true)
        assertTrue(toppostersPoster.coilModel?.startsWith("nexio-artwork://") == true)
        assertFalse(toppostersPoster.rawRemoteUrlUsedByUi)
        assertTrue(toppostersPoster.rejectedCandidates.any { rejected ->
            rejected["provider"] == "TMDB" &&
                rejected["sourceRole"] == "ADDON_PREVIEW" &&
                rejected["reason"] == "premium_artwork_provider_precedence"
        })

        val rpdbPoster = rpdb.artworkAudit.single { it.field == "poster" }
        assertEquals("rpdb.poster_template", rpdbPoster.runtimeApiShapeId)
        assertEquals("RPDB", rpdbPoster.selectedProvider)
        assertFalse(rpdbPoster.rawRemoteUrlUsedByUi)

        val switchedPoster = switchedProvider.artworkAudit.single { it.field == "poster" }
        assertEquals("RPDB", switchedPoster.selectedProvider)
        assertEquals("rpdb.poster_template", switchedPoster.runtimeApiShapeId)
        assertEquals("MISS_THEN_NETWORK", switchedPoster.assetCacheDecision)
        assertTrue(switchedPoster.networkExecuted)
        assertTrue(switchedPoster.coilModel?.startsWith("nexio-artwork://asset/") == true)
        assertFalse(switchedPoster.rawRemoteUrlUsedByUi)
        assertTrue(switchedPoster.rejectedCandidates.any { rejected ->
            rejected["provider"] == "TOP_POSTERS" &&
                rejected["sourceRole"] == "ARTWORK" &&
                rejected["reason"] == "active premium artwork provider switched to RPDB"
        })

        val cacheHitPoster = cacheHit.artworkAudit.single { it.field == "poster" }
        assertEquals("HIT", cacheHitPoster.assetCacheDecision)
        assertFalse(cacheHitPoster.networkExecuted)
    }

    @Test
    fun `premium artwork failure audit records placeholder fallback`() = runTest {
        val item = MetadataAuditRunner.default()
            .let { goldenBundle() }
            .reports
            .single { it.scenario.name == "premium-artwork-failure-fallback" }
            .items
            .single()

        val poster = item.artworkAudit.single { it.field == "poster" }

        assertEquals("TOP_POSTERS", poster.selectedProvider)
        assertEquals("topposters.poster_template", poster.runtimeApiShapeId)
        assertEquals("MISS_THEN_NETWORK", poster.assetCacheDecision)
        assertTrue(poster.networkExecuted)
        assertEquals("nexio-placeholder://poster", poster.coilModel)
        assertFalse(poster.rawRemoteUrlUsedByUi)
    }

    @Test
    fun `writer exports artwork audit in json and markdown`() = runTest {
        val report = MetadataAuditRunner.default()
            .let { goldenBundle() }
            .reports
            .single { it.scenario.name == "premium-artwork-topposters-home" }
        val outputDir = File("build/reports/metadata-audit/artwork-audit")
        val jsonFile = File(outputDir, "metadata-execution-single-report.json")
        val markdownFile = File(outputDir, "metadata-execution-single-report.md")

        MetadataAuditReportWriter().writeJson(report, jsonFile)
        MetadataAuditReportWriter().writeMarkdown(report, markdownFile)

        val item = JSONObject(jsonFile.readText())
            .getJSONArray("items")
            .getJSONObject(0)
        val artwork = item.getJSONArray("artworkAudit").getJSONObject(0)
        val markdown = markdownFile.readText()

        assertEquals("poster", artwork.getString("field"))
        assertEquals("TOP_POSTERS", artwork.getString("selectedProvider"))
        assertEquals("topposters.poster_template", artwork.getString("runtimeApiShapeId"))
        assertEquals("HIT", artwork.getString("assetCacheDecision"))
        assertFalse(artwork.getBoolean("networkExecuted"))
        assertFalse(artwork.getBoolean("rawRemoteUrlUsedByUi"))
        assertFalse(artwork.getBoolean("embedsRatingOverlay"))
        assertFalse(artwork.getBoolean("suppressesLocalRatingOverlay"))
        assertTrue(markdown.contains("Artwork Cache Audit"))
        assertTrue(markdown.contains("topposters.poster_template"))
        assertTrue(markdown.contains("nexio-artwork://"))
        assertTrue(markdown.contains("Embeds rating overlay"))
        assertTrue(markdown.contains("Suppress local rating"))
    }

    @Test
    fun `premium artwork report does not synthesize poster rejected candidates without resolver evidence`() = runTest {
        val report = MetadataAuditRunner.default().runCatalogFixture(
            fixtureName = "netflix_movie_nfx.json",
            fixtureJson = fixture("metadata/addons/netflix_movie_nfx.json"),
            scenario = MetadataAuditScenario(
                name = "premium-artwork-unknown",
                depth = MetadataDepth.DETAIL_CORE,
                visibleItemIds = setOf("tt16431404"),
                premiumArtworkProvider = "UNKNOWN_POSTER_PROVIDER",
                cacheMode = AuditCacheMode.WARM_FRESH
            )
        )

        val poster = report.items.single().selectedFields.single { it.field == "poster" }

        assertEquals("netflix", poster.selectedProvider)
        assertEquals("ADDON_PREVIEW", poster.sourceRole)
        assertTrue(poster.rejectedCandidates.none { rejected ->
            rejected.provider == "TMDB" &&
                rejected.sourceRole == "ADDON_PREVIEW" &&
                rejected.reason == "premium_artwork_provider_precedence"
        })
    }

    @Test
    fun `provider native tmdb tv routes directly with tvdb sidecar`() = runTest {
        val report = syntheticReport("provider-native-conflict", item = tmdbTvItem("tmdb:1399"))
        val item = report.items.single()

        assertEquals(MetadataPrimaryProvider.TMDB, item.routing?.provider)
        assertEquals(MetadataDecisionReason.PROVIDER_NATIVE_DIRECT, item.routing?.reason)
        assertFalse(item.routing?.preResolutionTargetIdRequiresIdentityResolution == true)
        assertFalse(item.routing?.targetIdRequiresIdentityResolution == true)
        assertEquals("tmdb:tv:1399", item.routing?.targetIds?.get(MetadataPrimaryProvider.TMDB))
        assertEquals("tvdb:121361", item.routing?.targetIds?.get(MetadataPrimaryProvider.TVDB))
        assertEquals(null, item.identityResolution)
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
        val report = syntheticReport("field-ownership-conflict", item = fieldConflictItem())
        val item = report.items.single()
        val title = item.selectedFields.single { it.field == "title" }

        assertTrue(item.forbiddenOverwrites.any { it.field == "title" })
        assertTrue(title.rejectedCandidates.any { it.reason.contains("PRIMARY") })
    }

    @Test
    fun `bundle writer exports coherent combined json and markdown reports`() = runTest {
        val bundle = goldenBundle()
        val outputDir = File("build/reports/metadata-audit")

        MetadataAuditReportWriter().writeBundleJson(bundle, File(outputDir, "metadata-execution-report.json"))
        MetadataAuditReportWriter().writeBundleMarkdown(bundle, File(outputDir, "metadata-execution-report.md"))

        val json = File(outputDir, "metadata-execution-report.json").readText()
        val markdown = File(outputDir, "metadata-execution-report.md").readText()
        val root = JSONObject(json)
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
        assertTrue(json.contains("\"homeUpdate\""))

        val tmdbTvRailReport = root
            .getJSONArray("reports")
            .objects()
            .single { it.getString("scenario") == "tmdb-tv-rail-preview-then-tmdb-hydration" }
        val tmdbTvRailItem = tmdbTvRailReport.getJSONArray("items").getJSONObject(0)
        val firstPaint = tmdbTvRailItem.getJSONObject("firstPaint")
        val routing = tmdbTvRailItem.getJSONObject("routing")
        val stableIdBundle = tmdbTvRailItem.getJSONObject("metadata.stable_id_bundle")
        val runtimeCalls = tmdbTvRailItem.getJSONArray("runtimeCalls").objects()

        assertEquals("RAIL_PREVIEW", firstPaint.getString("source"))
        assertFalse(firstPaint.getBoolean("routerExecuted"))
        assertFalse(firstPaint.getBoolean("networkExecuted"))
        assertEquals("metadata.stable_id_bundle", stableIdBundle.getString("eventType"))
        assertEquals("TMDB", stableIdBundle.getString("canonicalProvider"))
        assertEquals("tmdb:tv:1399", routing.getJSONObject("targetIds").getString("TMDB"))
        assertEquals("tvdb:121361", routing.getJSONObject("targetIds").getString("TVDB"))
        assertEquals(routing.getJSONObject("targetIds").getString("TMDB"), stableIdBundle.getString("canonicalId"))
        assertEquals("tt0944947", stableIdBundle.getString("imdbId"))
        assertFalse(stableIdBundle.getBoolean("networkExecuted"))
        assertEquals("VISIBLE_HOME_HYDRATION", stableIdBundle.getString("trigger"))
        assertTrue(runtimeCalls.none { it.getBoolean("executedNetwork") })

        assertFalse(json.contains("\"targetProvider\":\"TRAKT\""))
        assertFalse(json.contains("\"targetProvider\":\"SIMKL\""))
        assertTrue(markdown.contains("Metadata Execution Audit Bundle"))
        assertTrue(markdown.contains("Git SHA"))
        assertTrue(markdown.contains("Artifact role"))
        assertTrue(markdown.contains("Pre-resolution identity required"))
        assertTrue(markdown.contains("Execution identity resolved"))
        assertTrue(markdown.contains("provider-native-conflict"))
        assertFalse(markdown.contains("Identity resolution"))
        assertTrue(markdown.contains("Localization"))
        assertTrue(markdown.contains("Production caller ownership"))
        assertTrue(markdown.contains("Stable ID Bundle"))
        assertTrue(markdown.contains("Home Update"))
        assertTrue(markdown.contains("tt0944947"))
    }

    @Test
    fun `single report writer marks artifacts as smoke only`() = runTest {
        val report = MetadataAuditRunner.default()
            .let { goldenBundle() }
            .reports
            .single { it.scenario.name == "trakt-rail-visible-hydrates-tmdb" }
        val outputDir = File("build/reports/metadata-audit")

        MetadataAuditReportWriter().writeJson(report, File(outputDir, "metadata-execution-single-report.json"))
        MetadataAuditReportWriter().writeMarkdown(report, File(outputDir, "metadata-execution-single-report.md"))

        val json = File(outputDir, "metadata-execution-single-report.json").readText()
        JSONObject(json)
        assertTrue(json.contains("SMOKE_DEBUG_ONLY"))
        assertTrue(json.contains("routingAfterVisible"))
        assertTrue(json.contains("selectedFieldsBeforeHydration"))
        assertTrue(json.contains("selectedFieldsAfterHydration"))
        assertTrue(json.contains("identityMappingsHarvested"))
        assertTrue(json.contains("homeUpdate"))
        assertTrue(File(outputDir, "metadata-execution-single-report.md").readText().contains("Smoke/debug artifact only"))
    }

    private fun fixture(path: String): String {
        val resource = javaClass.classLoader?.getResource(path)
            ?: error("Missing fixture $path")
        return resource.readText()
    }

    private fun goldenBundle(): MetadataExecutionReportBundle {
        val reports = listOf(
            syntheticReport("preview-only-disney-mixed", item = syntheticItem("preview", routing = null)),
            syntheticReport("disney-mixed-visible-items", item = syntheticItem("tt26443597", routing = route("tt26443597", MetadataPrimaryProvider.TMDB, "tmdb:872585"))),
            syntheticReport("crunchyroll-imdb-anime-detail-core", item = syntheticItem("tt12343534", routing = route("tt12343534", MetadataPrimaryProvider.KITSU, "kitsu:7442"))),
            syntheticReport("kitsu-prefix-detail-core", item = syntheticItem("kitsu:7442", routing = route("kitsu:7442", MetadataPrimaryProvider.KITSU, "kitsu:7442"))),
            syntheticReport("mal-prefix-detail-core", item = syntheticItem("mal:21", routing = route("mal:21", MetadataPrimaryProvider.KITSU, "kitsu:1"))),
            syntheticReport("tmdb-tv-detail-core", item = tmdbTvItem("tmdb:tv:1399")),
            syntheticReport("provider-native-conflict", item = tmdbTvItem("tmdb:tv:1399")),
            syntheticReport("premium-artwork-topposters", item = premiumArtworkItem("topposters")),
            syntheticReport("premium-artwork-rpdb", item = premiumArtworkItem("rpdb")),
            syntheticReport("premium-artwork-topposters-home", item = premiumArtworkAuditItem("TOP_POSTERS", "topposters.poster_template", "HIT")),
            syntheticReport("premium-artwork-rpdb-home", item = premiumArtworkAuditItem("RPDB", "rpdb.poster_template", "HIT")),
            syntheticReport("premium-artwork-topposters-detail", item = premiumArtworkAuditItem("TOP_POSTERS", "topposters.poster_template", "HIT")),
            syntheticReport("premium-artwork-rpdb-detail", item = premiumArtworkAuditItem("RPDB", "rpdb.poster_template", "HIT")),
            syntheticReport("premium-artwork-switch-provider", item = premiumArtworkAuditItem("RPDB", "rpdb.poster_template", "MISS_THEN_NETWORK", network = true, switched = true)),
            syntheticReport("premium-artwork-cache-hit", item = premiumArtworkAuditItem("TOP_POSTERS", "topposters.poster_template", "HIT")),
            syntheticReport("premium-artwork-failure-fallback", item = premiumArtworkAuditItem("TOP_POSTERS", "topposters.poster_template", "MISS_THEN_NETWORK", network = true, placeholder = true)),
            syntheticReport("continue-watching-local-playback"),
            syntheticReport("continue-watching-stale-routing-version"),
            syntheticReport("field-ownership-conflict", item = fieldConflictItem()),
            syntheticReport("tmdb-movie-core-warm-cache", cacheMode = AuditCacheMode.WARM_FRESH, item = syntheticItem("tt16431404", cacheDecisions = listOf(cache("tmdb.movie.core", CacheDecision.HIT)))),
            syntheticReport("tmdb-tv-core-warm-cache", cacheMode = AuditCacheMode.WARM_FRESH, item = syntheticItem("tmdb:tv:1399", cacheDecisions = listOf(cache("tmdb.tv.core", CacheDecision.HIT)))),
            syntheticReport("kitsu-anime-core-warm-cache", cacheMode = AuditCacheMode.WARM_FRESH, item = syntheticItem("kitsu:7442", cacheDecisions = listOf(cache("kitsu.anime.core", CacheDecision.HIT)))),
            syntheticReport("stale-on-429"),
            syntheticReport(
                "production-caller-ownership",
                item = syntheticItem(
                    "tt16431404",
                    productionCallerOwnership = listOf(
                        ProductionCallerOwnershipEvent("home_catalog", "hydrate", true, true, true, false)
                    )
                )
            ),
            syntheticReport("tmdb-tv-localized-english-fallback", item = syntheticItem("tmdb:tv:1399", localization = localization("tmdb:tv:1399", MetadataPrimaryProvider.TMDB, "nl-NL", "en-US"))),
            syntheticReport("tmdb-localized-english-fallback", item = syntheticItem("tt16431404", localization = localization("tt16431404", MetadataPrimaryProvider.TMDB, "nl-NL", "en-US"))),
            syntheticReport("kitsu-localized-field-fallback", item = syntheticItem("kitsu:7442", localization = localization("kitsu:7442", MetadataPrimaryProvider.KITSU, "nl", "en"))),
            syntheticReport("trakt-rail-first-paint-title-year", item = railPreviewItem("trakt:movie:hope-2026")),
            syntheticReport("trakt-rail-visible-hydrates-tmdb", item = hydratedRailItem("trakt:show:signal-2026")),
            syntheticReport("mdblist-rail-first-paint-rich-preview", item = railPreviewItem("mdblist:movie:aurora")),
            syntheticReport("tmdb-movie-rail-first-paint-rich-preview", item = railPreviewItem("tmdb:movie:501")),
            syntheticReport("tmdb-tv-rail-preview-then-tmdb-hydration", item = tmdbTvRailItem()),
            syntheticReport("kitsu-rail-first-paint-rich-preview", item = railPreviewItem("kitsu:7442")),
            syntheticReport("simkl-json-rail-first-paint-rich-preview", item = railPreviewItem("simkl:movie:77")),
            syntheticReport("simkl-json-rail-visible-hydrates-tmdb", item = syntheticItem("simkl:movie:88")),
            homeUpdateReport("addon_first_paint_then_hydrated_home_update"),
            homeUpdateReport("trakt_rail_first_paint_then_tvdb_update"),
            homeUpdateReport("tmdb_movie_rail_first_paint_then_tmdb_update", item = tmdbMovieHomeUpdateItem()),
            homeUpdateReport("tmdb_tv_rail_first_paint_then_tmdb_update"),
            homeUpdateReport("kitsu_rail_first_paint_then_kitsu_update"),
            homeUpdateReport("simkl_rail_first_paint_then_tmdb_update"),
            homeUpdateReport("hydration_failure_keeps_preview"),
            homeUpdateReport("cache_hit_updates_home_without_network"),
            homeUpdateReport("focused_item_hydrates_before_offscreen_items"),
            homeUpdateReport("hydration_result_ignored_after_profile_switch")
        )
        return MetadataExecutionReportBundle(
            schemaVersion = 1,
            provenance = provenance(),
            verdict = AuditVerdict.PASS,
            generatedAtEpochMs = 0,
            reports = reports,
            summaries = summary(reports.flatMap { it.items }),
            policyViolations = emptyList()
        )
    }

    private fun syntheticReport(
        name: String,
        cacheMode: AuditCacheMode = AuditCacheMode.COLD,
        fixtureName: String = "synthetic/metadata/$name.json",
        item: ItemExecutionReport = syntheticItem(name)
    ) = MetadataExecutionReport(
        schemaVersion = 1,
        provenance = provenance(),
        verdict = AuditVerdict.PASS,
        scenario = MetadataAuditScenario(name = name, depth = MetadataDepth.DETAIL_CORE, cacheMode = cacheMode),
        fixtureName = fixtureName,
        generatedAtEpochMs = 0,
        items = listOf(item),
        summaries = summary(listOf(item)),
        policyViolations = emptyList()
    )

    private fun syntheticItem(
        itemId: String,
        routing: RouteEvent? = route(itemId, MetadataPrimaryProvider.TMDB, "tmdb:1"),
        selectedFields: List<FieldSelectedEvent> = listOf(field(itemId, "title", "TMDB")),
        runtimeCalls: List<RuntimeCallEvent> = emptyList(),
        cacheDecisions: List<CacheDecisionEvent> = emptyList(),
        stableIdBundle: StableIdBundleEvent? = null,
        localization: LocalizationEvent? = null,
        artworkAudit: List<ArtworkAuditEntry> = emptyList(),
        railSource: String? = null,
        sourceProvider: String? = null,
        homeUpdate: HomeUpdateEvent? = null,
        productionCallerOwnership: List<ProductionCallerOwnershipEvent> = emptyList(),
        selectedFieldsAfterHydration: List<FieldSelectedEvent> = selectedFields,
        events: List<AuditEvent> = emptyList()
    ) = ItemExecutionReport(
        itemId = itemId,
        itemType = "series",
        addonFields = emptyMap(),
        firstPaint = FirstPaintEvent(itemId, "series", source = if (railSource == null) "ADDON_META_PREVIEW" else "RAIL_PREVIEW", fieldsUsed = emptySet(), routerExecuted = false, networkExecuted = false),
        routing = routing,
        stableIdBundle = stableIdBundle,
        providerPlan = null,
        runtimeCalls = runtimeCalls,
        cacheDecisions = cacheDecisions,
        resolverSchedule = ResolverScheduleEvent(itemId, MetadataDepth.DETAIL_CORE, listOf(ResolverType.ADDON_DISPLAY, ResolverType.RATING, ResolverType.ARTWORK), emptyMap()),
        selectedFields = selectedFields,
        forbiddenOverwrites = emptyList(),
        continueWatchingSnapshot = null,
        identityResolution = null,
        productionCallerOwnership = productionCallerOwnership,
        localization = localization,
        violations = emptyList(),
        events = events,
        railSource = railSource,
        sourceProvider = sourceProvider,
        routingAfterVisible = routing,
        selectedFieldsBeforeHydration = selectedFields,
        selectedFieldsAfterHydration = selectedFieldsAfterHydration,
        identityMappingsHarvested = stableIdBundle?.canonicalId?.let { mapOf(itemId to it) }.orEmpty(),
        homeUpdate = homeUpdate,
        artworkAudit = artworkAudit
    )

    private fun tmdbTvItem(itemId: String) = syntheticItem(
        itemId = itemId,
        routing = RouteEvent(
            itemId = itemId,
            parentId = itemId,
            itemType = "series",
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:tv:1399", MetadataPrimaryProvider.TVDB to "tvdb:121361"),
            preResolutionTargetIdRequiresIdentityResolution = false,
            targetIdRequiresIdentityResolution = false,
            usedInputs = setOf("tmdbTvId", "tvdbSeriesId"),
            ignoredInputs = emptySet()
        )
    )

    private fun tmdbTvRailItem(): ItemExecutionReport {
        val stable = StableIdBundleEvent(
            itemKey = "series:tmdb:tv:1399",
            itemType = "series",
            trigger = "VISIBLE_HOME_HYDRATION",
            status = "RESOLVED_WITH_SIDECARS",
            canonicalProvider = "TMDB",
            canonicalId = "tmdb:tv:1399",
            imdbId = "tt0944947",
            networkExecuted = false,
            evidence = listOf(
                StableIdBundleEvidenceEvent("knownIds", "TMDB", false, "tmdb:tv:1399"),
                StableIdBundleEvidenceEvent("knownIds", "TVDB", false, "tvdb:121361")
            )
        )
        return syntheticItem(
            itemId = "tmdb:tv:1399",
            routing = tmdbTvItem("tmdb:tv:1399").routing,
            stableIdBundle = stable,
            runtimeCalls = listOf(runtime("tmdb.tv.core", executedNetwork = false)),
            railSource = "BUILT_IN_TMDB",
            sourceProvider = "TMDB",
            events = listOf(AuditEvent.StableIdBundle(stable))
        )
    }

    private fun railPreviewItem(itemId: String) = syntheticItem(
        itemId = itemId,
        railSource = "BUILT_IN_TRAKT",
        sourceProvider = "TRAKT",
        events = listOf(AuditEvent.FirstPaint(FirstPaintEvent(itemId, "series", "RAIL_PREVIEW", emptySet(), false, false)))
    )

    private fun hydratedRailItem(itemId: String) = syntheticItem(
        itemId = itemId,
        railSource = "BUILT_IN_TRAKT",
        sourceProvider = "TRAKT",
        selectedFieldsAfterHydration = listOf(
            field(itemId, "title", "TMDB", rejected = listOf(RejectedCandidateReport("TRAKT", "RAIL_PREVIEW", "primary canonical field available"))),
            field(itemId, "poster", "TMDB"),
            field(itemId, "overview", "TMDB")
        )
    )

    private fun tmdbMovieHomeUpdateItem() = syntheticItem(
        itemId = "tmdb:550",
        stableIdBundle = StableIdBundleEvent("movie:tmdb:550", "movie", "VISIBLE_HOME_HYDRATION", "RESOLVED", "TMDB", "tmdb:550", "tt0137523", false, emptyList()),
        runtimeCalls = listOf(runtime("custom_imdb.ratings")),
        homeUpdate = HomeUpdateEvent(emptyMap(), mapOf("rating" to "8.8"), listOf("rating"), rowOrderChanged = false, focusChanged = false, "before", "after"),
        railSource = "BUILT_IN_TMDB",
        sourceProvider = "TMDB"
    )

    private fun homeUpdateReport(name: String, item: ItemExecutionReport = syntheticItem(name, homeUpdate = HomeUpdateEvent(emptyMap(), emptyMap(), emptyList(), false, false, "before", "after"))) =
        syntheticReport(name, fixtureName = "synthetic/metadata/home-updates/$name.json", item = item)

    private fun premiumArtworkItem(kind: String): ItemExecutionReport {
        val provider = if (kind == "rpdb") "RPDB" else "TOP_POSTERS"
        val shape = if (kind == "rpdb") "rpdb.poster_template" else "topposters.poster_template"
        return syntheticItem(
            itemId = "tt16431404",
            selectedFields = listOf(field("tt16431404", "poster", provider, sourceRole = "ARTWORK", rejected = listOf(RejectedCandidateReport("TMDB", "ADDON_PREVIEW", "premium_artwork_provider_precedence")))),
            runtimeCalls = listOf(runtime(shape), runtime("tmdb.movie.core", executedNetwork = false)),
            cacheDecisions = listOf(cache("tmdb.movie.core", CacheDecision.HIT))
        )
    }

    private fun premiumArtworkAuditItem(provider: String, shape: String, decision: String, network: Boolean = false, switched: Boolean = false, placeholder: Boolean = false) = syntheticItem(
        itemId = "tt16431404",
        artworkAudit = listOf(
            ArtworkAuditEntry(
                field = "poster",
                selectedProvider = provider,
                sourceRole = "ARTWORK",
                decisionKey = "artwork:decision:test",
                assetKey = "artwork:asset:test",
                assetCacheDecision = decision,
                runtimeApiShapeId = shape,
                networkExecuted = network,
                coilModel = if (placeholder) "nexio-placeholder://poster" else "nexio-artwork://asset/test",
                rawRemoteUrlUsedByUi = false,
                rejectedCandidates = if (switched) {
                    listOf(mapOf("provider" to "TOP_POSTERS", "sourceRole" to "ARTWORK", "reason" to "active premium artwork provider switched to RPDB"))
                } else {
                    listOf(mapOf("provider" to "TMDB", "sourceRole" to "ADDON_PREVIEW", "reason" to "premium_artwork_provider_precedence"))
                }
            )
        )
    )

    private fun fieldConflictItem() = syntheticItem(
        itemId = "tt0036697",
        selectedFields = listOf(field("tt0036697", "title", "TMDB", rejected = listOf(RejectedCandidateReport("secondary", reason = "PRIMARY selected higher priority value")))),
    ).copy(forbiddenOverwrites = listOf(ForbiddenOverwriteEvent("tt0036697", "title", "TMDB", "secondary", "PRIMARY selected higher priority value")))

    private fun localization(itemId: String, provider: MetadataPrimaryProvider, requested: String, fallback: String) = LocalizationEvent(
        itemId = itemId,
        provider = provider,
        requestedLanguage = requested,
        fallbackLanguage = fallback,
        policyVersion = 2,
        providerFallbackAllowedForMissingLocalizedFields = false,
        payloads = listOf(
            LocalizationPayloadReport("tmdb.tv.core", requested, "LOCALIZED", "audit:$itemId:policy:2", CacheDecision.HIT, false, "PRODUCTION_ADAPTER"),
            LocalizationPayloadReport("tmdb.tv.core", fallback, "LANGUAGE_FALLBACK", "audit:$itemId:policy:2:fallback", CacheDecision.HIT, false, "PRODUCTION_ADAPTER")
        ),
        perEpisodeTranslationFallbacksAttempted = 0,
        maxPerEpisodeTranslationFallbacksAllowed = 0,
        providerFallbackUsed = false
    )

    private fun route(itemId: String, provider: MetadataPrimaryProvider, targetId: String) = RouteEvent(
        itemId = itemId,
        parentId = itemId,
        itemType = "series",
        provider = provider,
        mediaKind = MetadataMediaKind.SERIES,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        targetIds = mapOf(provider to targetId),
        preResolutionTargetIdRequiresIdentityResolution = false,
        targetIdRequiresIdentityResolution = false,
        usedInputs = setOf("item.type"),
        ignoredInputs = emptySet()
    )

    private fun field(itemId: String, field: String, provider: String, sourceRole: String = "PRIMARY", rejected: List<RejectedCandidateReport> = emptyList()) =
        FieldSelectedEvent(itemId, field, provider, sourceRole, "value", rejected, if (sourceRole == "ARTWORK") "premium artwork may override poster only" else "$field selected from PRIMARY")

    private fun runtime(apiShapeId: String, executedNetwork: Boolean = false) =
        RuntimeCallEvent("item", "TMDB", apiShapeId, "$apiShapeId:item", "audit:$apiShapeId", "CORE", executedNetwork)

    private fun cache(apiShapeId: String, decision: CacheDecision) =
        CacheDecisionEvent("item", "TMDB", apiShapeId, "audit:$apiShapeId", decision, 7.daysMs, 30.daysMs, "primary-metadata-core")

    private fun provenance() = MetadataAuditProvenance("test-sha", GitWorktreeState("CLEAN", 0, 0))

    private fun summary(items: List<ItemExecutionReport>) = AuditSummaries(
        totalItems = items.size,
        routedItems = items.count { it.routing != null },
        networkCalls = items.flatMap { it.runtimeCalls }.count { it.executedNetwork },
        cacheHits = items.flatMap { it.cacheDecisions }.count { it.decision == CacheDecision.HIT },
        cacheMisses = 0,
        staleHits = 0,
        forbiddenOverwrites = items.sumOf { it.forbiddenOverwrites.size },
        policyViolations = 0,
        providersUsed = emptyMap(),
        apiShapesUsed = items.flatMap { it.runtimeCalls }.map { it.apiShapeId }.groupingBy { it }.eachCount()
    )

    private fun MetadataExecutionReportBundle.localizedScenario(name: String): LocalizationEvent =
        reports.single { it.scenario.name == name }.items.single().localization
            ?: error("Missing localization event for $name")

    private fun org.json.JSONArray.objects(): List<JSONObject> =
        (0 until length()).map { getJSONObject(it) }

    private val Int.daysMs: Long get() = this * 24L * 60L * 60L * 1_000L
}
