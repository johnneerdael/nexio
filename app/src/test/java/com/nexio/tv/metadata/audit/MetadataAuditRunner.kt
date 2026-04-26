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
    private val parser: CatalogFixtureParser = CatalogFixtureParser()
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
                violations = emptyList(),
                events = trace.events
            )
        }

        adapters.forEach { it.bind(itemId = item.id, trace = trace) }
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
        val providerPlanEvent = result.plan?.toAuditEvent(item)
        if (providerPlanEvent != null) trace.onProviderPlan(providerPlanEvent)
        val resolverEvent = ResolverScheduleEvent(
            itemId = item.id,
            depth = result.resolverSchedule.depth,
            resolversScheduled = result.resolverSchedule.localResolvers + result.resolverSchedule.networkResolvers,
            resolversSkipped = emptyMap()
        )
        trace.onResolverSchedule(resolverEvent)

        val selectedFields = result.resolvedDocument.fieldOwners.map { (field, owner) ->
            FieldSelectedEvent(
                itemId = item.id,
                field = field.name.lowercase(),
                selectedProvider = result.route?.provider ?: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TMDB,
                sourceRole = owner.name,
                valuePreview = result.resolvedDocument.valueFor(field)?.toString(),
                rejectedCandidates = emptyList()
            )
        }
        selectedFields.forEach(trace::onFieldSelected)
        val forbiddenOverwrites = result.resolvedDocument.ignoredOverwrites.map { ignored ->
            ForbiddenOverwriteEvent(
                itemId = item.id,
                field = ignored.field.name.lowercase(),
                primaryProvider = result.route?.provider ?: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TMDB,
                rejectedProvider = result.route?.provider ?: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TMDB,
                reason = "Field already owned by ${ignored.existingOwner}; rejected ${ignored.attemptedOwner}"
            )
        }
        forbiddenOverwrites.forEach(trace::onForbiddenOverwrite)

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
            violations = trace.events.mapNotNull { (it as? AuditEvent.PolicyViolation)?.event },
            events = trace.events
        )
    }

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
            reason = reason,
            targetIds = targetIds,
            targetIdRequiresIdentityResolution = targetIdRequiresIdentityResolution,
            usedInputs = inferUsedInputs(),
            ignoredInputs = setOf("catalog.type", "catalog.id", "addon.name", "source.name", "genre", "animeType", "links", "trend", "popularity")
        )

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

    companion object {
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
    }
}

private class AuditMetadataProviderAdapter(
    override val provider: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
) : MetadataProviderAdapter {
    private var itemId: String = "unknown"
    private var trace: MetadataAuditTraceCollector? = null

    fun bind(itemId: String, trace: MetadataAuditTraceCollector) {
        this.itemId = itemId
        this.trace = trace
    }

    fun reset() {
        itemId = "unknown"
        trace = null
    }

    override fun supports(step: ProviderPlanStep): Boolean = true

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val operationKey = "${step.apiShapeId}:${route.targetIds[route.provider] ?: route.parentId}:${route.language.orEmpty()}"
        val cacheKey = "metadata:${route.provider}:${operationKey}"
        trace?.onRuntimeCall(
            RuntimeCallEvent(
                itemId = itemId,
                provider = step.provider,
                apiShapeId = step.apiShapeId,
                operationKey = operationKey,
                cacheKey = cacheKey,
                workClass = "USER_VISIBLE",
                executedNetwork = true
            )
        )
        trace?.onCacheDecision(
            CacheDecisionEvent(
                itemId = itemId,
                provider = step.provider,
                apiShapeId = step.apiShapeId,
                cacheKey = cacheKey,
                decision = CacheDecision.MISS_THEN_NETWORK,
                ttlMs = 3_600_000,
                staleWindowMs = 86_400_000,
                reason = "cold audit fixture"
            )
        )
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
            )
        )
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
