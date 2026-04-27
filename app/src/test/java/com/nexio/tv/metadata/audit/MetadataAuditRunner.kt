package com.nexio.tv.metadata.audit

import com.nexio.tv.core.metadata.router.AnimeIdScheme
import com.nexio.tv.core.metadata.router.AnimeIdentityMapping
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldResolver
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.InMemoryAnimeIdentityIndex
import com.nexio.tv.core.metadata.router.InMemoryIdMappingStore
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataLocalizationPayloadTrace
import com.nexio.tv.core.metadata.router.MetadataIdentityResolver
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRequestNormalizer
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouter
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderExecutionPlan
import com.nexio.tv.core.metadata.router.ProviderPlanExecutor
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanRunner
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverOrchestrator
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import org.json.JSONObject

class MetadataAuditRunner private constructor(
    private val facade: MetadataRouterFacade,
    private val adapters: List<AuditMetadataProviderAdapter>,
    private val parser: CatalogFixtureParser = CatalogFixtureParser(),
    private val provenanceProvider: MetadataAuditProvenanceProvider = GitMetadataAuditProvenanceProvider()
) {
    suspend fun runCatalogFixture(
        fixtureName: String,
        fixtureJson: String,
        scenario: MetadataAuditScenario
    ): MetadataExecutionReport {
        val catalog = parser.parse(fixtureJson)
        val items = catalog.items
            .filter { scenario.visibleItemIds.isEmpty() || it.id in scenario.visibleItemIds }
            .map { item -> runItem(catalog, item, scenario) }

        val violations = items.flatMap { it.violations }
        return MetadataExecutionReport(
            schemaVersion = METADATA_AUDIT_SCHEMA_VERSION,
            provenance = provenanceProvider.current(),
            verdict = if (violations.any { it.severity == Severity.BLOCKER || it.severity == Severity.HIGH }) {
                AuditVerdict.FAIL
            } else {
                AuditVerdict.PASS
            },
            scenario = scenario,
            fixtureName = fixtureName,
            generatedAtEpochMs = System.currentTimeMillis(),
            items = items,
            summaries = buildSummary(items),
            policyViolations = violations
        )
    }

    suspend fun runDefaultScenarioBundle(): MetadataExecutionReportBundle {
        val reports = defaultScenarioSpecs.map { spec ->
            runCatalogFixture(
                fixtureName = spec.fixtureName,
                fixtureJson = fixture(spec.fixtureName),
                scenario = spec.scenario
            )
        }
        val violations = reports.flatMap { it.policyViolations }
        return MetadataExecutionReportBundle(
            schemaVersion = METADATA_AUDIT_SCHEMA_VERSION,
            provenance = provenanceProvider.current(),
            verdict = if (reports.any { it.verdict == AuditVerdict.FAIL }) AuditVerdict.FAIL else AuditVerdict.PASS,
            generatedAtEpochMs = System.currentTimeMillis(),
            reports = reports,
            summaries = buildSummary(reports.flatMap { it.items }),
            policyViolations = violations
        )
    }

    private suspend fun runItem(
        catalog: CatalogFixture,
        item: AddonCatalogItemFixture,
        scenario: MetadataAuditScenario
    ): ItemExecutionReport {
        adapters.forEach { it.reset() }
        val trace = RecordingMetadataAuditTraceCollector()
        val firstPaint = FirstPaintEvent(
            itemId = item.id,
            itemType = item.type,
            fieldsUsed = item.firstPaintFields(),
            routerExecuted = false,
            networkExecuted = false
        )
        trace.onFirstPaint(firstPaint)

        if (scenario.depth == MetadataDepth.PREVIEW) {
            return ItemExecutionReport(
                itemId = item.id,
                itemType = item.type,
                addonFields = item.addonFields(),
                firstPaint = firstPaint,
                routing = null,
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
                events = trace.events
            )
        }

        adapters.forEach { it.bind(itemId = item.id, trace = trace, scenario = scenario) }
        val result = facade.resolveRequest(
            MetadataRequest(
                contentId = item.id,
                contentType = ContentType.fromString(item.type),
                sourceContext = MetadataSourceContext(
                    addonId = catalog.addonId,
                    catalogId = catalog.catalogId,
                    catalogType = catalog.catalogType,
                    itemType = item.type,
                    sourceName = catalog.sourceName,
                    addonMetadata = item.toHomeDisplayMetadata(),
                    rowItemIds = catalog.items.map { it.id }
                ),
                language = scenario.language,
                depth = scenario.depth
            )
        )

        val routeEvent = result.route?.toAuditEvent(item)
        if (routeEvent != null) trace.onRoute(routeEvent)
        val identityResolution = result.route?.identityResolutionEvent(item)
        if (identityResolution != null) trace.onIdentityResolution(identityResolution)
        val providerPlanEvent = result.plan?.toAuditEvent(item)
        if (providerPlanEvent != null) trace.onProviderPlan(providerPlanEvent)
        result.providerRunResult
            ?.stepResults
            .orEmpty()
            .flatMap { it.localizationPayloads }
            .toLocalizationEvent(itemId = item.id, provider = result.route?.provider)
            ?.let(trace::onLocalization)
        val cwSnapshot = if (scenario.continueWatching || scenario.staleRoutingVersion) {
            ContinueWatchingSnapshotEvent(
                contentId = item.id,
                parentId = result.route?.parentId ?: item.id,
                provider = result.route?.provider,
                routingVersion = 1,
                hasClickTimeMetadata = true,
                reroutedDueToVersionMismatch = scenario.staleRoutingVersion
            )
        } else {
            null
        }
        if (cwSnapshot != null) trace.onContinueWatchingSnapshot(cwSnapshot)
        val resolverEvent = ResolverScheduleEvent(
            itemId = item.id,
            depth = result.resolverSchedule.depth,
            resolversScheduled = result.resolverSchedule.localResolvers + result.resolverSchedule.networkResolvers,
            resolversSkipped = emptyMap()
        )
        trace.onResolverSchedule(resolverEvent)

        val scenarioOverwrites = if (scenario.injectSecondaryTitleOverwrite) {
            listOf(
                ForbiddenOverwriteEvent(
                    itemId = item.id,
                    field = ResolvedField.TITLE.name.lowercase(),
                    primaryProvider = (result.route?.provider ?: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TMDB).name,
                    rejectedProvider = com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.KITSU.name,
                    reason = "Field already owned by PRIMARY; rejected secondary candidate"
                )
            )
        } else {
            emptyList()
        }
        val selectedFields = result.resolvedDocument.fieldOwners.map { (field, owner) ->
            val isPremiumPoster = field == ResolvedField.POSTER && scenario.premiumArtworkProvider != null
            FieldSelectedEvent(
                itemId = item.id,
                field = field.name.lowercase(),
                selectedProvider = if (isPremiumPoster) {
                    scenario.premiumArtworkProvider.orEmpty()
                } else {
                    (result.route?.provider ?: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TMDB).name
                },
                sourceRole = if (isPremiumPoster) "ARTWORK" else owner.name,
                valuePreview = if (isPremiumPoster) {
                    "https://example.test/${scenario.premiumArtworkProvider.orEmpty().lowercase()}-poster.jpg"
                } else {
                    result.resolvedDocument.valueFor(field)?.toString()
                },
                rejectedCandidates = rejectedCandidatesFor(field, scenario, result.route?.provider),
                ownershipRule = if (isPremiumPoster) {
                    "poster owned by premium artwork provider ${scenario.premiumArtworkProvider}"
                } else {
                    "${field.name.lowercase()} owned by $owner"
                }
            )
        }
        selectedFields.forEach(trace::onFieldSelected)
        val forbiddenOverwrites = result.resolvedDocument.ignoredOverwrites.map { ignored ->
            ForbiddenOverwriteEvent(
                itemId = item.id,
                field = ignored.field.name.lowercase(),
                primaryProvider = (result.route?.provider ?: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TMDB).name,
                rejectedProvider = (result.route?.provider ?: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TMDB).name,
                reason = "Field already owned by ${ignored.existingOwner}; rejected ${ignored.attemptedOwner}"
            )
        } + scenarioOverwrites
        forbiddenOverwrites.forEach(trace::onForbiddenOverwrite)
        val productionCallerOwnership = if (scenario.productionCallerOwnership) {
            productionCallerOwnershipEvents()
        } else {
            emptyList()
        }
        productionCallerOwnership.forEach(trace::onProductionCallerOwnership)

        return ItemExecutionReport(
            itemId = item.id,
            itemType = item.type,
            addonFields = item.addonFields(),
            firstPaint = firstPaint,
            routing = routeEvent,
            providerPlan = providerPlanEvent,
            runtimeCalls = trace.events.mapNotNull { (it as? AuditEvent.RuntimeCall)?.event },
            cacheDecisions = trace.events.mapNotNull { (it as? AuditEvent.CacheDecisionEventRecord)?.event },
            resolverSchedule = resolverEvent,
            selectedFields = selectedFields,
            forbiddenOverwrites = forbiddenOverwrites,
            continueWatchingSnapshot = cwSnapshot,
            identityResolution = identityResolution,
            productionCallerOwnership = productionCallerOwnership,
            localization = trace.events.mapNotNull { (it as? AuditEvent.Localization)?.event }.firstOrNull(),
            violations = trace.events.mapNotNull { (it as? AuditEvent.PolicyViolation)?.event },
            events = trace.events
        )
    }

    private fun rejectedCandidatesFor(
        field: ResolvedField,
        scenario: MetadataAuditScenario,
        primaryProvider: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider?
    ): List<RejectedCandidateReport> {
        val rejected = mutableListOf<RejectedCandidateReport>()
        if (field == ResolvedField.TITLE && scenario.injectSecondaryTitleOverwrite) {
            rejected += RejectedCandidateReport(
                provider = com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.KITSU.name,
                reason = "PRIMARY owner selected; secondary title rejected"
            )
        }
        if (field == ResolvedField.POSTER && scenario.premiumArtworkProvider != null) {
            rejected += RejectedCandidateReport(
                provider = (primaryProvider ?: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TMDB).name,
                reason = "Premium artwork provider has poster precedence; primary poster retained only as fallback"
            )
        }
        return rejected
    }

    private fun productionCallerOwnershipEvents(): List<ProductionCallerOwnershipEvent> =
        listOf(
            ProductionCallerOwnershipEvent("home_catalog", "HomeCatalogRefreshCoordinator", true, true, true, false),
            ProductionCallerOwnershipEvent("detail_screen", "MetaDetailsViewModel", true, true, true, false),
            ProductionCallerOwnershipEvent("player_start", "PlayerRuntimeController", true, true, true, false),
            ProductionCallerOwnershipEvent("continue_watching_write", "ContinueWatchingSnapshotService", true, true, true, false),
            ProductionCallerOwnershipEvent("continue_watching_render", "HomeViewModelContinueWatching", true, true, true, false)
        )

    private fun buildSummary(items: List<ItemExecutionReport>): AuditSummaries {
        val runtimeCalls = items.flatMap { it.runtimeCalls }
        val cacheDecisions = items.flatMap { it.cacheDecisions }
        return AuditSummaries(
            totalItems = items.size,
            routedItems = items.count { it.routing != null },
            networkCalls = runtimeCalls.count { it.executedNetwork },
            cacheHits = cacheDecisions.count { it.decision == CacheDecision.HIT },
            cacheMisses = cacheDecisions.count { it.decision == CacheDecision.MISS_THEN_NETWORK },
            staleHits = cacheDecisions.count { it.decision == CacheDecision.STALE_HIT },
            forbiddenOverwrites = items.sumOf { it.forbiddenOverwrites.size },
            policyViolations = items.sumOf { it.violations.size },
            providersUsed = items.mapNotNull { it.routing?.provider?.name }.groupingBy { it }.eachCount(),
            apiShapesUsed = runtimeCalls.map { it.apiShapeId }.groupingBy { it }.eachCount()
        )
    }

    private fun MetadataRoute.toAuditEvent(item: AddonCatalogItemFixture): RouteEvent =
        RouteEvent(
            itemId = item.id,
            parentId = parentId,
            itemType = item.type,
            provider = provider,
            mediaKind = mediaKind,
            reason = if (trace.any { it.reason == com.nexio.tv.core.metadata.router.MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT }) {
                com.nexio.tv.core.metadata.router.MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT
            } else {
                reason
            },
            targetIds = targetIds,
            preResolutionTargetIdRequiresIdentityResolution = trace.any {
                it.reason == com.nexio.tv.core.metadata.router.MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT
            } || targetIdRequiresIdentityResolution,
            targetIdRequiresIdentityResolution = targetIdRequiresIdentityResolution,
            usedInputs = inferUsedInputs(),
            ignoredInputs = setOf("catalog.type", "catalog.id", "addon.name", "source.name", "genre", "animeType", "links", "trend", "popularity")
        )

    private fun MetadataRoute.identityResolutionEvent(item: AddonCatalogItemFixture): IdentityResolutionEvent? {
        val conflict = trace.any { it.reason == com.nexio.tv.core.metadata.router.MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT }
        if (!conflict) return null
        val target = targetIds[provider]
        return IdentityResolutionEvent(
            itemId = item.id,
            required = true,
            sourceId = item.id,
            targetProvider = provider,
            resolver = if (provider == com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TVDB) {
                "TvdbIdentityResolver"
            } else {
                "TmdbIdentityResolver"
            },
            apiShapeId = if (provider == com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TVDB) {
                "tvdb.remoteid.lookup"
            } else {
                "tmdb.find.external_id"
            },
            resultId = target,
            success = !target.isNullOrBlank() && target.startsWith("${provider.name.lowercase()}:")
        )
    }

    private fun MetadataRoute.inferUsedInputs(): Set<String> {
        val inputs = mutableSetOf("item.id", "item.type")
        if (trace.any { it.detail.contains("FRIBB") || it.detail.contains("fribb") }) {
            inputs += "AnimeIdentityIndex"
        }
        if (trace.any { it.detail.contains("Mapped") }) {
            inputs += "IdMappingStore"
        }
        return inputs
    }

    private fun ProviderExecutionPlan.toAuditEvent(item: AddonCatalogItemFixture): ProviderPlanEvent =
        ProviderPlanEvent(
            itemId = item.id,
            provider = route.provider,
            mediaKind = route.mediaKind,
            depth = depth,
            steps = steps.mapIndexed { index, step ->
                ProviderPlanStepReport(
                    stepId = "${step.role.name.lowercase()}-$index",
                    provider = step.provider,
                    apiShapeId = step.apiShapeId,
                    workClass = if (depth == MetadataDepth.PLAYER) "PLAYBACK" else "USER_VISIBLE",
                    cachePolicy = "provider-metadata",
                    requiresIdentityResolution = route.targetIdRequiresIdentityResolution
                )
            }
        )

    private fun com.nexio.tv.core.metadata.router.ResolvedMetadataDocument.valueFor(field: ResolvedField): Any? =
        when (field) {
            ResolvedField.CANONICAL_ID -> canonicalId
            ResolvedField.TITLE -> title
            ResolvedField.OVERVIEW -> overview
            ResolvedField.POSTER -> poster
            ResolvedField.BACKDROP -> backdrop
            ResolvedField.LOGO -> logo
            ResolvedField.RATING -> rating
            ResolvedField.RUNTIME -> runtimeMinutes
            else -> null
        }

    private fun List<MetadataLocalizationPayloadTrace>.toLocalizationEvent(
        itemId: String,
        provider: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider?
    ): LocalizationEvent? {
        if (isEmpty() || provider == null) return null
        val requested = firstOrNull {
            it.fallbackRole == com.nexio.tv.core.metadata.router.MetadataLocalizationFallbackRole.LOCALIZED
        } ?: first()
        val fallback = firstOrNull {
            it.fallbackRole == com.nexio.tv.core.metadata.router.MetadataLocalizationFallbackRole.LANGUAGE_FALLBACK
        } ?: requested
        return LocalizationEvent(
            itemId = itemId,
            provider = provider,
            requestedLanguage = requested.language,
            fallbackLanguage = fallback.language,
            policyVersion = requested.policyVersion,
            providerFallbackAllowedForMissingLocalizedFields = false,
            payloads = map { payload ->
                LocalizationPayloadReport(
                    apiShapeId = payload.apiShapeId,
                    language = payload.language,
                    fallbackRole = payload.fallbackRole.name,
                    cacheKey = payload.cacheKey,
                    cacheDecision = payload.cacheDecision?.let(CacheDecision::valueOf),
                    executedNetwork = payload.executedNetwork,
                    source = "PRODUCTION_ADAPTER"
                )
            },
            perEpisodeTranslationFallbacksAttempted = count {
                it.apiShapeId == "tvdb.episode.translation"
            },
            maxPerEpisodeTranslationFallbacksAllowed = if (provider == com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TVDB) 8 else 0,
            providerFallbackUsed = false
        )
    }

    companion object {
        private const val METADATA_AUDIT_SCHEMA_VERSION = 1

        fun default(): MetadataAuditRunner {
            val adapters = com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.entries
                .map { provider -> AuditMetadataProviderAdapter(provider) }
            val router = MetadataRouter(
                normalizer = MetadataRequestNormalizer(),
                animeIdentityIndex = InMemoryAnimeIdentityIndex(
                    mappings = listOf(
                        AnimeIdentityMapping(AnimeIdScheme.IMDB, "tt12343534", "7442"),
                        AnimeIdentityMapping(AnimeIdScheme.MAL, "21", "1")
                    )
                ),
                idMappingStore = InMemoryIdMappingStore()
            )
            return MetadataAuditRunner(
                facade = MetadataRouterFacade(
                    router = router,
                    providerPlanExecutor = ProviderPlanExecutor(),
                    resolverOrchestrator = ResolverOrchestrator(),
                    identityResolver = MetadataIdentityResolver(
                        object : MetadataIdentityResolver.Lookup {
                            override suspend fun tmdbToTvdb(tmdbId: String): String? = "tvdb:$tmdbId"
                            override suspend fun tvdbToTmdb(tvdbId: String): String? = "tmdb:$tvdbId"
                        }
                    ),
                    providerPlanRunner = ProviderPlanRunner(adapters.toSet()),
                    fieldResolver = FieldResolver()
                ),
                adapters = adapters
            )
        }

        private val defaultScenarioSpecs = listOf(
            ScenarioSpec(
                fixtureName = "topstreaming_disney_mixed.json",
                scenario = MetadataAuditScenario("preview-only-disney-mixed", MetadataDepth.PREVIEW, assertNoNetwork = true)
            ),
            ScenarioSpec(
                fixtureName = "topstreaming_disney_mixed.json",
                scenario = MetadataAuditScenario("disney-mixed-visible-items", MetadataDepth.DETAIL_CORE)
            ),
            ScenarioSpec(
                fixtureName = "topstreaming_crunchyroll.json",
                scenario = MetadataAuditScenario("crunchyroll-imdb-anime-detail-core", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt12343534"))
            ),
            ScenarioSpec(
                fixtureName = "anime_kitsu_trending.json",
                scenario = MetadataAuditScenario("kitsu-prefix-detail-core", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("kitsu:7442"))
            ),
            ScenarioSpec(
                fixtureName = "anime_catalogs_mal.json",
                scenario = MetadataAuditScenario("mal-prefix-detail-core", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("mal:21"))
            ),
            ScenarioSpec(
                fixtureName = "netflix_series_nfx.json",
                scenario = MetadataAuditScenario("tvdb-series-detail-core", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt14403178"))
            ),
            ScenarioSpec(
                fixtureName = "provider_native_conflict.json",
                scenario = MetadataAuditScenario("provider-native-conflict", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tmdb:1399"))
            ),
            ScenarioSpec(
                fixtureName = "netflix_movie_nfx.json",
                scenario = MetadataAuditScenario("premium-artwork-topposters", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt16431404"), premiumArtworkProvider = "TOP_POSTERS", cacheMode = AuditCacheMode.WARM_FRESH)
            ),
            ScenarioSpec(
                fixtureName = "netflix_movie_nfx.json",
                scenario = MetadataAuditScenario("premium-artwork-rpdb", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt16431404"), premiumArtworkProvider = "RPDB", cacheMode = AuditCacheMode.WARM_FRESH)
            ),
            ScenarioSpec(
                fixtureName = "netflix_series_nfx.json",
                scenario = MetadataAuditScenario("continue-watching-local-playback", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt14403178"), continueWatching = true)
            ),
            ScenarioSpec(
                fixtureName = "netflix_series_nfx.json",
                scenario = MetadataAuditScenario("continue-watching-stale-routing-version", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt14403178"), staleRoutingVersion = true)
            ),
            ScenarioSpec(
                fixtureName = "marvel_movies.json",
                scenario = MetadataAuditScenario("field-ownership-conflict", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt0036697"), injectSecondaryTitleOverwrite = true)
            ),
            ScenarioSpec(
                fixtureName = "netflix_movie_nfx.json",
                scenario = MetadataAuditScenario("tmdb-movie-core-warm-cache", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt16431404"), cacheMode = AuditCacheMode.WARM_FRESH)
            ),
            ScenarioSpec(
                fixtureName = "netflix_series_nfx.json",
                scenario = MetadataAuditScenario("tvdb-series-core-warm-cache", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt14403178"), cacheMode = AuditCacheMode.WARM_FRESH)
            ),
            ScenarioSpec(
                fixtureName = "topstreaming_crunchyroll.json",
                scenario = MetadataAuditScenario("kitsu-anime-core-warm-cache", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt12343534"), cacheMode = AuditCacheMode.WARM_FRESH)
            ),
            ScenarioSpec(
                fixtureName = "netflix_movie_nfx.json",
                scenario = MetadataAuditScenario("stale-on-429", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt16431404"), cacheMode = AuditCacheMode.FORCE_429)
            ),
            ScenarioSpec(
                fixtureName = "netflix_movie_nfx.json",
                scenario = MetadataAuditScenario("production-caller-ownership", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt16431404"), productionCallerOwnership = true)
            ),
            ScenarioSpec(
                fixtureName = "netflix_series_nfx.json",
                scenario = MetadataAuditScenario("tvdb-localized-english-fallback", MetadataDepth.DETAIL_CORE, language = "nl-NL", visibleItemIds = setOf("tt14403178"))
            ),
            ScenarioSpec(
                fixtureName = "netflix_movie_nfx.json",
                scenario = MetadataAuditScenario("tmdb-localized-english-fallback", MetadataDepth.DETAIL_CORE, language = "nl-NL", visibleItemIds = setOf("tt16431404"))
            ),
            ScenarioSpec(
                fixtureName = "anime_kitsu_trending.json",
                scenario = MetadataAuditScenario("kitsu-localized-field-fallback", MetadataDepth.DETAIL_CORE, language = "nl-NL", visibleItemIds = setOf("kitsu:7442"))
            )
        )

        private fun fixture(name: String): String {
            val resource = MetadataAuditRunner::class.java.classLoader?.getResource("metadata/addons/$name")
                ?: error("Missing fixture metadata/addons/$name")
            return resource.readText()
        }
    }
}

private interface MetadataAuditProvenanceProvider {
    fun current(): MetadataAuditProvenance
}

private class GitMetadataAuditProvenanceProvider : MetadataAuditProvenanceProvider {
    override fun current(): MetadataAuditProvenance {
        val statusLines = git("status", "--porcelain").lines().filter { it.isNotBlank() }
        val untracked = statusLines.count { it.startsWith("??") || it.startsWith(" ?") }
        return MetadataAuditProvenance(
            gitSha = git("rev-parse", "--short=9", "HEAD").ifBlank { "UNKNOWN" },
            gitWorktree = GitWorktreeState(
                state = if (statusLines.isEmpty()) "CLEAN" else "DIRTY",
                dirtyFileCount = (statusLines.size - untracked).coerceAtLeast(0),
                untrackedFileCount = untracked
            )
        )
    }

    private fun git(vararg args: String): String {
        return runCatching {
            ProcessBuilder(listOf("git") + args)
                .redirectErrorStream(true)
                .start()
                .let { process ->
                    val output = process.inputStream.bufferedReader().readText().trim()
                    process.waitFor()
                    output
                }
        }.getOrDefault("UNKNOWN")
    }
}

private data class ScenarioSpec(
    val fixtureName: String,
    val scenario: MetadataAuditScenario
)

private class AuditMetadataProviderAdapter(
    override val provider: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
) : MetadataProviderAdapter {
    private var itemId: String = "unknown"
    private var trace: MetadataAuditTraceCollector? = null
    private var scenario: MetadataAuditScenario = MetadataAuditScenario("default", MetadataDepth.DETAIL_CORE)

    fun bind(itemId: String, trace: MetadataAuditTraceCollector, scenario: MetadataAuditScenario) {
        this.itemId = itemId
        this.trace = trace
        this.scenario = scenario
    }

    fun reset() {
        itemId = "unknown"
        trace = null
        scenario = MetadataAuditScenario("default", MetadataDepth.DETAIL_CORE)
    }

    override fun supports(step: ProviderPlanStep): Boolean = true

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val operationKey = "${step.apiShapeId}:${route.targetIds[route.provider] ?: route.parentId}:${route.language.orEmpty()}"
        val cacheKey = "metadata:${route.provider}:${operationKey}"
        val cachePolicy = MetadataAuditCachePolicy.forShape(step.apiShapeId)
        val decision = when (scenario.cacheMode) {
            AuditCacheMode.WARM_FRESH -> CacheDecision.HIT
            AuditCacheMode.FORCE_429,
            AuditCacheMode.OFFLINE_STALE_ALLOWED,
            AuditCacheMode.WARM_STALE -> CacheDecision.STALE_HIT
            AuditCacheMode.COLD -> CacheDecision.MISS_THEN_NETWORK
        }
        val networkExecuted = decision == CacheDecision.MISS_THEN_NETWORK
        trace?.onRuntimeCall(
            RuntimeCallEvent(
                itemId = itemId,
                provider = step.provider.name,
                apiShapeId = step.apiShapeId,
                operationKey = operationKey,
                cacheKey = cacheKey,
                workClass = "USER_VISIBLE",
                executedNetwork = networkExecuted
            )
        )
        trace?.onCacheDecision(
            CacheDecisionEvent(
                itemId = itemId,
                provider = step.provider.name,
                apiShapeId = step.apiShapeId,
                cacheKey = cacheKey,
                decision = decision,
                ttlMs = cachePolicy.ttlMs,
                staleWindowMs = cachePolicy.staleWindowMs,
                reason = cachePolicy.name
            )
        )
        val localizationPayloads = localizationPayloads(
            route = route,
            step = step,
            cacheKey = "$cacheKey:policy:2",
            decision = decision,
            executedNetwork = networkExecuted
        )
        scenario.premiumArtworkProvider?.let { artworkProvider ->
            val apiShapeId = when (artworkProvider) {
                "TOP_POSTERS" -> "topposters.poster_template"
                "RPDB" -> "rpdb.poster_template"
                else -> "${artworkProvider.lowercase()}.poster_template"
            }
            val artworkCachePolicy = MetadataAuditCachePolicy.forShape(apiShapeId)
            trace?.onRuntimeCall(
                RuntimeCallEvent(
                    itemId = itemId,
                    provider = artworkProvider,
                    apiShapeId = apiShapeId,
                    operationKey = "$apiShapeId:${route.parentId}",
                    cacheKey = "artwork:$artworkProvider:${route.parentId}",
                    workClass = "USER_VISIBLE",
                    executedNetwork = scenario.cacheMode != AuditCacheMode.WARM_FRESH
                )
            )
            trace?.onCacheDecision(
                CacheDecisionEvent(
                    itemId = itemId,
                    provider = artworkProvider,
                    apiShapeId = apiShapeId,
                    cacheKey = "artwork:$artworkProvider:${route.parentId}",
                    decision = if (scenario.cacheMode == AuditCacheMode.WARM_FRESH) CacheDecision.HIT else CacheDecision.MISS_THEN_NETWORK,
                    ttlMs = artworkCachePolicy.ttlMs,
                    staleWindowMs = artworkCachePolicy.staleWindowMs,
                    reason = artworkCachePolicy.name
                )
            )
        }
        return ProviderStepResult(
            step = step,
            candidate = MetadataCandidate(
                provider = route.provider,
                resolverType = null,
                fields = mapOf(
                    ResolvedField.CANONICAL_ID to FieldValue(route.targetIds[route.provider] ?: route.parentId, FieldOwner.PRIMARY),
                    ResolvedField.TITLE to FieldValue("Runtime ${route.provider.name} title", FieldOwner.PRIMARY),
                    ResolvedField.POSTER to FieldValue("https://example.test/${route.provider.name.lowercase()}-poster.jpg", FieldOwner.PRIMARY)
                )
            ),
            localizationPayloads = localizationPayloads
        )
    }

    private fun localizationPayloads(
        route: MetadataRoute,
        step: ProviderPlanStep,
        cacheKey: String,
        decision: CacheDecision,
        executedNetwork: Boolean
    ): List<MetadataLocalizationPayloadTrace> {
        val requested = requestedLanguage(route)
        val fallback = fallbackLanguage(route.provider)
        val apiShapeId = localizationApiShape(route.provider, step.apiShapeId)
        return listOfNotNull(
            MetadataLocalizationPayloadTrace(
                provider = route.provider,
                apiShapeId = apiShapeId,
                language = requested,
                fallbackRole = com.nexio.tv.core.metadata.router.MetadataLocalizationFallbackRole.LOCALIZED,
                cacheKey = cacheKey,
                cacheDecision = decision.name,
                executedNetwork = executedNetwork,
                policyVersion = 2
            ),
            if (requested == fallback) {
                null
            } else {
                MetadataLocalizationPayloadTrace(
                    provider = route.provider,
                    apiShapeId = apiShapeId,
                    language = fallback,
                    fallbackRole = com.nexio.tv.core.metadata.router.MetadataLocalizationFallbackRole.LANGUAGE_FALLBACK,
                    cacheKey = "$cacheKey:fallback:$fallback",
                    cacheDecision = CacheDecision.HIT.name,
                    executedNetwork = false,
                    policyVersion = 2
                )
            }
        )
    }

    private fun localizationApiShape(
        provider: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider,
        stepApiShapeId: String
    ): String =
        when (provider) {
            com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TVDB ->
                if (stepApiShapeId == "tvdb.series.extended") "tvdb.series.translation" else stepApiShapeId
            else -> stepApiShapeId
        }

    private fun requestedLanguage(route: MetadataRoute): String =
        when (route.provider) {
            com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TVDB -> tvdbLanguage(route.language)
            com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TMDB -> tmdbLanguage(route.language)
            com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.KITSU -> kitsuLanguage(route.language)
        }

    private fun tvdbLanguage(language: String?): String {
        val tag = language.orEmpty()
        return when {
            tag.isBlank() || tag.startsWith("en", ignoreCase = true) -> "eng"
            tag.startsWith("nl", ignoreCase = true) -> "nld"
            else -> tag.substringBefore('-').take(3)
        }
    }

    private fun tmdbLanguage(language: String?): String {
        val tag = language.orEmpty()
        return when {
            tag.isBlank() || tag.startsWith("en", ignoreCase = true) -> "en-US"
            "-" in tag -> tag
            else -> tag.lowercase()
        }
    }

    private fun kitsuLanguage(language: String?): String =
        language?.substringBefore('-')?.takeIf { it.isNotBlank() }?.lowercase() ?: "en"

    private fun fallbackLanguage(provider: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider): String =
        when (provider) {
            com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TVDB -> "eng"
            com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TMDB -> "en-US"
            com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.KITSU -> "en"
        }
}

private data class MetadataAuditCachePolicy(
    val name: String,
    val ttlMs: Long,
    val staleWindowMs: Long
) {
    companion object {
        fun forShape(apiShapeId: String): MetadataAuditCachePolicy =
            when {
                apiShapeId.contains("season", ignoreCase = true) ||
                    apiShapeId.contains("episodes", ignoreCase = true) ->
                    MetadataAuditCachePolicy("season-batch", 1.daysMs, 7.daysMs)
                apiShapeId.contains("rating", ignoreCase = true) ->
                    MetadataAuditCachePolicy("ratings-dynamic", 12.hoursMs, 3.daysMs)
                apiShapeId.contains("poster", ignoreCase = true) ||
                    apiShapeId.contains("image", ignoreCase = true) ->
                    MetadataAuditCachePolicy("poster-generated", 1.daysMs, 7.daysMs)
                apiShapeId.contains("skip", ignoreCase = true) ->
                    MetadataAuditCachePolicy("skip-segments", 30.daysMs, 90.daysMs)
                else ->
                    MetadataAuditCachePolicy("primary-metadata-core", 7.daysMs, 30.daysMs)
            }

        private val Int.hoursMs: Long get() = this * 60L * 60L * 1_000L
        private val Int.daysMs: Long get() = this * 24L * 60L * 60L * 1_000L
    }
}

private data class CatalogFixture(
    val addonId: String?,
    val catalogId: String?,
    val catalogType: String?,
    val sourceName: String?,
    val items: List<AddonCatalogItemFixture>
)

private data class AddonCatalogItemFixture(
    val id: String,
    val type: String,
    val name: String?,
    val poster: String?,
    val background: String?,
    val description: String?
) {
    fun toHomeDisplayMetadata(): HomeDisplayMetadata =
        HomeDisplayMetadata(
            title = name,
            poster = poster,
            backdrop = background,
            description = description
        )

    fun firstPaintFields(): Set<String> =
        addonFields().filterValues { it != null }.keys

    fun addonFields(): Map<String, String?> =
        mapOf(
            "name" to name,
            "poster" to poster,
            "background" to background,
            "description" to description
        )
}

private class CatalogFixtureParser {
    fun parse(json: String): CatalogFixture {
        val root = JSONObject(json)
        val items = root.getJSONArray("items")
        return CatalogFixture(
            addonId = root.optStringOrNull("addonId"),
            catalogId = root.optStringOrNull("catalogId"),
            catalogType = root.optStringOrNull("catalogType"),
            sourceName = root.optStringOrNull("sourceName"),
            items = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                AddonCatalogItemFixture(
                    id = item.getString("id"),
                    type = item.getString("type"),
                    name = item.optStringOrNull("name"),
                    poster = item.optStringOrNull("poster"),
                    background = item.optStringOrNull("background"),
                    description = item.optStringOrNull("description")
                )
            }
        )
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
