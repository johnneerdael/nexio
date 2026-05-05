package com.nexio.tv.metadata.audit

import com.nexio.tv.core.metadata.router.AnimeIdScheme
import com.nexio.tv.core.metadata.router.AnimeIdentityMapping
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldResolver
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.InMemoryAnimeIdentityIndex
import com.nexio.tv.core.metadata.router.InMemoryIdMappingStore
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataLocalizationPayloadTrace
import com.nexio.tv.core.metadata.router.MetadataIdentityResolver
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRequestNormalizer
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouter
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderExecutionPlan
import com.nexio.tv.core.metadata.router.ProviderPlanExecutor
import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanRunner
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverOrchestrator
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.integration.posters.RpdbMetadataProviderAdapter
import com.nexio.tv.data.integration.posters.TopPostersMetadataProviderAdapter
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterRatingsProvider
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailDisplaySeed
import com.nexio.tv.domain.model.RailHydrationState
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.SourcePayloadQuality
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import com.nexio.tv.domain.model.toMetaPreview
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject

class MetadataAuditRunner private constructor(
    private val facade: MetadataRouterFacade,
    private val adapters: List<AuditMetadataProviderAdapter>,
    private val posterResolver: PosterRatingsUrlResolver,
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
        } + railScenarioSpecs.map { spec -> runRailScenario(spec) } +
            homeUpdateScenarioSpecs.map { spec -> runHomeUpdateScenario(spec) }
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

    private fun configurePosterResolverForScenario(scenario: MetadataAuditScenario) {
        val premium = scenario.premiumArtworkProvider?.uppercase()
        when (premium) {
            "RPDB" -> {
                val activeProvider = PosterRatingsUrlResolver.ActiveProvider(
                    provider = PosterRatingsProvider.RPDB,
                    apiKey = "test-rpdb-key"
                )
                coEvery { posterResolver.getActiveProvider() } returns activeProvider
                every { posterResolver.resolvePosterUrl(any(), any(), any(), any()) } returns
                    if (scenario.premiumArtworkFetchFails) null else "https://example.test/rpdb-poster.jpg"
            }
            "TOP_POSTERS" -> {
                val activeProvider = PosterRatingsUrlResolver.ActiveProvider(
                    provider = PosterRatingsProvider.TOP_POSTERS,
                    apiKey = "test-top_posters-key"
                )
                coEvery { posterResolver.getActiveProvider() } returns activeProvider
                every { posterResolver.resolvePosterUrl(any(), any(), any(), any()) } returns
                    if (scenario.premiumArtworkFetchFails) null else "https://example.test/top_posters-poster.jpg"
            }
            else -> {
                coEvery { posterResolver.getActiveProvider() } returns null
            }
        }
    }

    private suspend fun runItem(
        catalog: CatalogFixture,
        item: AddonCatalogItemFixture,
        scenario: MetadataAuditScenario
    ): ItemExecutionReport {
        configurePosterResolverForScenario(scenario)
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
            val winnerProvider = result.resolvedDocument.sourceProviders[field]
                ?: (result.route?.provider ?: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TMDB).name
            val sourceRole = result.resolvedDocument.sourceRoles[field]?.name ?: owner.name
            FieldSelectedEvent(
                itemId = item.id,
                field = field.name.lowercase(),
                selectedProvider = winnerProvider,
                sourceRole = sourceRole,
                valuePreview = result.resolvedDocument.valueFor(field)?.toString(),
                rejectedCandidates = rejectedCandidatesFor(
                    field = field,
                    scenario = scenario,
                    resolverRejectedCandidates = result.resolvedDocument.rejectedCandidatesByField[field].orEmpty()
                ),
                ownershipRule = if (
                    field == ResolvedField.POSTER &&
                    sourceRole == com.nexio.tv.core.metadata.router.SourceRole.ARTWORK.name &&
                    scenario.premiumArtworkProvider != null
                ) {
                    "premium artwork may override poster only"
                } else {
                    "${field.name.lowercase()} owned by $owner"
                }
            )
        }
        selectedFields.forEach(trace::onFieldSelected)
        val runtimeCalls = trace.events.mapNotNull { (it as? AuditEvent.RuntimeCall)?.event }
        val cacheDecisions = trace.events.mapNotNull { (it as? AuditEvent.CacheDecisionEventRecord)?.event }
        val artworkAudit = buildArtworkAudit(
            itemId = item.id,
            scenario = scenario,
            selectedFields = selectedFields,
            runtimeCalls = runtimeCalls,
            cacheDecisions = cacheDecisions
        )
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
            runtimeCalls = runtimeCalls,
            cacheDecisions = cacheDecisions,
            resolverSchedule = resolverEvent,
            selectedFields = selectedFields,
            forbiddenOverwrites = forbiddenOverwrites,
            continueWatchingSnapshot = cwSnapshot,
            identityResolution = identityResolution,
            productionCallerOwnership = productionCallerOwnership,
            localization = trace.events.mapNotNull { (it as? AuditEvent.Localization)?.event }.firstOrNull(),
            violations = trace.events.mapNotNull { (it as? AuditEvent.PolicyViolation)?.event },
            events = trace.events,
            artworkAudit = artworkAudit
        )
    }

    private suspend fun runRailScenario(spec: RailScenarioSpec): MetadataExecutionReport {
        val scenario = MetadataAuditScenario(
            name = spec.name,
            depth = if (spec.routeProvider == null) MetadataDepth.PREVIEW else MetadataDepth.DETAIL_CORE,
            visibleItemIds = setOf(spec.itemId),
            cacheMode = AuditCacheMode.WARM_FRESH
        )
        val trace = RecordingMetadataAuditTraceCollector()
        val railPreview = spec.toRailItemPreview(generatedAtMs = System.currentTimeMillis())
        val metaPreview = railPreview.toMetaPreview()
        val firstPaint = metaPreview.toAuditFirstPaintEvent(
            fieldsUsed = spec.previewFields.filterValues { it != null }.keys
        )
        trace.onFirstPaint(firstPaint)

        val beforeHydration = spec.previewFields.mapNotNull { (field, value) ->
            value?.let {
                FieldSelectedEvent(
                    itemId = metaPreview.id,
                    field = field,
                    selectedProvider = metaPreview.firstPaintSourceProvider?.name ?: spec.sourceProvider,
                    sourceRole = com.nexio.tv.core.metadata.router.SourceRole.RAIL_PREVIEW.name,
                    valuePreview = it,
                    rejectedCandidates = emptyList(),
                    ownershipRule = "$field selected from ${com.nexio.tv.core.metadata.router.SourceRole.RAIL_PREVIEW.name}"
                )
            }
        }

        val result = if (scenario.depth == MetadataDepth.PREVIEW) {
            null
        } else {
            adapters.forEach { it.reset() }
            adapters.forEach { adapter ->
                adapter.bind(
                    itemId = metaPreview.id,
                    trace = trace,
                    scenario = scenario,
                    deterministicFields = spec.hydratedFields
                )
            }
            facade.resolveRequest(
                MetadataRequest(
                    contentId = spec.itemId,
                    contentType = ContentType.fromString(metaPreview.apiType),
                    sourceContext = MetadataSourceContext(
                        catalogId = spec.name,
                        catalogType = metaPreview.apiType,
                        itemType = metaPreview.apiType,
                        sourceName = spec.railSource,
                        addonMetadata = metaPreview.toHomeDisplayMetadata(),
                        rowItemIds = listOf(metaPreview.id),
                        previewSourceRole = com.nexio.tv.core.metadata.router.SourceRole.RAIL_PREVIEW,
                        previewSourceProvider = metaPreview.firstPaintSourceProvider?.name ?: spec.sourceProvider,
                        previewStableIds = railPreview.stableIds,
                        previewSourceItemId = railPreview.sourceItemId,
                        previewRailSource = railPreview.railSource.name
                    ),
                    depth = scenario.depth
                )
            )
        }

        val route = result?.route
            ?.toAuditEvent(itemId = metaPreview.id, itemType = metaPreview.apiType)
        if (route != null) trace.onRoute(route)
        val stableIdBundle = route?.let { resolvedRoute ->
            spec.toStableIdBundleEvent(resolvedRoute)
        }
        val providerPlan = result?.plan?.toAuditEvent(itemId = metaPreview.id)
        if (providerPlan != null) trace.onProviderPlan(providerPlan)
        val resolverSchedule = result?.resolverSchedule?.let { schedule ->
            ResolverScheduleEvent(
                itemId = metaPreview.id,
                depth = schedule.depth,
                resolversScheduled = schedule.localResolvers + schedule.networkResolvers,
                resolversSkipped = emptyMap()
            )
        }
        if (resolverSchedule != null) trace.onResolverSchedule(resolverSchedule)
        result
            ?.providerRunResult
            ?.stepResults
            .orEmpty()
            .flatMap { it.localizationPayloads }
            .toLocalizationEvent(itemId = metaPreview.id, provider = result?.route?.provider)
            ?.let(trace::onLocalization)

        val resolverRejectedFields = result
            ?.resolvedDocument
            ?.ignoredOverwrites
            .orEmpty()
            .map { it.field }
            .toSet()
        val afterHydration = result?.let { resolution ->
            resolution.resolvedDocument.fieldOwners.map { (field, owner) ->
            val sourceRole = resolution.resolvedDocument.sourceRoles[field]?.name ?: owner.name
            FieldSelectedEvent(
                itemId = metaPreview.id,
                field = field.name.lowercase(),
                selectedProvider = resolution.resolvedDocument.sourceProviders[field] ?: resolution.route?.provider?.name.orEmpty(),
                sourceRole = sourceRole,
                valuePreview = resolution.resolvedDocument.valueFor(field)?.toString(),
                rejectedCandidates = if (field in resolverRejectedFields && field.name.lowercase() in spec.previewFields.filterValues { it != null }) {
                    listOf(
                        RejectedCandidateReport(
                            provider = metaPreview.firstPaintSourceProvider?.name ?: spec.sourceProvider,
                            sourceRole = com.nexio.tv.core.metadata.router.SourceRole.RAIL_PREVIEW.name,
                            reason = "primary canonical field available"
                        )
                    )
                } else {
                    emptyList()
                },
                ownershipRule = when (sourceRole) {
                    com.nexio.tv.core.metadata.router.SourceRole.RAIL_PREVIEW.name -> "rail preview fills field before canonical hydration"
                    com.nexio.tv.core.metadata.router.SourceRole.PRIMARY.name -> "primary always wins"
                    else -> "${field.name.lowercase()} owned by $sourceRole"
                }
            )
            }
        }.orEmpty()
        afterHydration.forEach(trace::onFieldSelected)
        val identityMappingsHarvested = emptyMap<String, String>()
        val forbiddenOverwrites = result?.let { resolution ->
            resolution.resolvedDocument.ignoredOverwrites.map { ignored ->
            ForbiddenOverwriteEvent(
                itemId = metaPreview.id,
                field = ignored.field.name.lowercase(),
                primaryProvider = resolution.route?.provider?.name.orEmpty(),
                rejectedProvider = metaPreview.firstPaintSourceProvider?.name ?: spec.sourceProvider,
                reason = "Field already owned by ${ignored.existingOwner}; rejected ${ignored.attemptedOwner}"
            )
            }
        }.orEmpty()
        forbiddenOverwrites.forEach(trace::onForbiddenOverwrite)
        val identityResolution = result?.route?.let { resolvedRoute ->
            if (!resolvedRoute.targetIdRequiresIdentityResolution) {
                null
            } else {
                IdentityResolutionEvent(
                    itemId = metaPreview.id,
                    required = true,
                    sourceId = metaPreview.id,
                    targetProvider = resolvedRoute.provider,
                    resolver = "MetadataIdentityResolver",
                    apiShapeId = providerPlan?.steps?.firstOrNull()?.apiShapeId.orEmpty(),
                    resultId = resolvedRoute.targetIds[resolvedRoute.provider],
                    success = !resolvedRoute.targetIds[resolvedRoute.provider].isNullOrBlank()
                )
            }
        }
        val item = ItemExecutionReport(
            itemId = metaPreview.id,
            itemType = metaPreview.apiType,
            addonFields = spec.previewFields,
            firstPaint = firstPaint,
            routing = route,
            stableIdBundle = stableIdBundle,
            providerPlan = providerPlan,
            runtimeCalls = trace.events.mapNotNull { (it as? AuditEvent.RuntimeCall)?.event },
            cacheDecisions = trace.events.mapNotNull { (it as? AuditEvent.CacheDecisionEventRecord)?.event },
            resolverSchedule = resolverSchedule,
            selectedFields = afterHydration.ifEmpty { beforeHydration },
            forbiddenOverwrites = forbiddenOverwrites,
            continueWatchingSnapshot = null,
            identityResolution = identityResolution,
            productionCallerOwnership = emptyList(),
            localization = trace.events.mapNotNull { (it as? AuditEvent.Localization)?.event }.firstOrNull(),
            violations = trace.events.mapNotNull { (it as? AuditEvent.PolicyViolation)?.event },
            events = stableIdBundle?.let { trace.events + AuditEvent.StableIdBundle(it) } ?: trace.events,
            railSource = metaPreview.firstPaintRailSource?.name,
            sourceProvider = metaPreview.firstPaintSourceProvider?.name,
            sourcePayloadFieldsUsed = spec.previewFields.filterValues { it != null }.keys,
            routingAfterVisible = route,
            selectedFieldsBeforeHydration = beforeHydration,
            selectedFieldsAfterHydration = afterHydration,
            identityMappingsHarvested = identityMappingsHarvested
        )
        val report = MetadataExecutionReport(
            schemaVersion = METADATA_AUDIT_SCHEMA_VERSION,
            provenance = provenanceProvider.current(),
            verdict = AuditVerdict.PASS,
            scenario = scenario,
            fixtureName = "synthetic/metadata/rails/${spec.name}.json",
            generatedAtEpochMs = System.currentTimeMillis(),
            items = listOf(item),
            summaries = buildSummary(listOf(item)),
            policyViolations = emptyList()
        )
        return report
    }

    private fun runHomeUpdateScenario(spec: HomeUpdateScenarioSpec): MetadataExecutionReport {
        val scenario = MetadataAuditScenario(
            name = spec.name,
            depth = MetadataDepth.DETAIL_CORE,
            visibleItemIds = setOf(spec.itemId),
            cacheMode = if (spec.metadataNetworkExecuted || spec.identityNetworkExecuted) {
                AuditCacheMode.COLD
            } else {
                AuditCacheMode.WARM_FRESH
            }
        )
        val firstPaint = FirstPaintEvent(
            itemId = spec.itemId,
            itemType = spec.itemType,
            source = spec.firstPaintSource,
            fieldsUsed = spec.previewFields.filterValues { it != null }.keys,
            routerExecuted = false,
            networkExecuted = false
        )
        val route = spec.canonicalProvider?.let { provider ->
            RouteEvent(
                itemId = spec.itemId,
                parentId = spec.itemId,
                itemType = spec.itemType,
                provider = provider,
                mediaKind = spec.mediaKind,
                reason = if (spec.mediaKind == MetadataMediaKind.SERIES) {
                    MetadataDecisionReason.ITEM_TYPE_SERIES
                } else {
                    MetadataDecisionReason.ITEM_TYPE_MOVIE
                },
                targetIds = spec.canonicalId?.let { mapOf(provider to it) }.orEmpty(),
                preResolutionTargetIdRequiresIdentityResolution = false,
                targetIdRequiresIdentityResolution = false,
                usedInputs = spec.usedInputs,
                ignoredInputs = setOf("catalog.type", "catalog.id", "addon.name", "source.name")
            )
        }
        val stableIdBundle = spec.canonicalProvider?.let { provider ->
            StableIdBundleEvent(
                itemKey = "${spec.itemType}:${spec.itemId}",
                itemType = spec.itemType,
                trigger = spec.trigger,
                status = stableIdBundleStatus(
                    canonicalId = spec.canonicalId,
                    imdbId = spec.imdbId
                ),
                canonicalProvider = provider.name,
                canonicalId = spec.canonicalId,
                imdbId = spec.imdbId,
                networkExecuted = spec.identityNetworkExecuted,
                evidence = listOf(
                    StableIdBundleEvidenceEvent(
                        source = if (spec.identityNetworkExecuted) "provider.identity_lookup" else "knownIds",
                        target = provider.name,
                        networkExecuted = spec.identityNetworkExecuted,
                        resultId = spec.canonicalId
                    )
                )
            )
        }
        val selectedFieldsBeforeHydration = spec.previewFields.toHomeFieldSelections(
            itemId = spec.itemId,
            selectedProvider = spec.sourceProvider,
            sourceRole = if (spec.firstPaintSource == "RAIL_PREVIEW") "RAIL_PREVIEW" else "ADDON_PREVIEW",
            ownershipRuleSuffix = "selected from first paint preview"
        )
        val selectedFieldsAfterHydration = spec.homeUpdate.after.toHomeFieldSelections(
            itemId = spec.itemId,
            selectedProvider = spec.canonicalProvider?.name ?: spec.sourceProvider,
            sourceRole = if (spec.homeUpdate.changedFields.isEmpty()) {
                if (spec.firstPaintSource == "RAIL_PREVIEW") "RAIL_PREVIEW" else "ADDON_PREVIEW"
            } else {
                "PRIMARY"
            },
            ownershipRuleSuffix = if (spec.homeUpdate.changedFields.isEmpty()) {
                "remains from first paint preview"
            } else {
                "selected from hydrated home overlay"
            },
            changedFields = spec.homeUpdate.changedFields,
            previewProvider = spec.sourceProvider,
            previewSourceRole = if (spec.firstPaintSource == "RAIL_PREVIEW") "RAIL_PREVIEW" else "ADDON_PREVIEW"
        )
        val runtimeCalls = spec.runtimeApiShapes.map { apiShapeId ->
            RuntimeCallEvent(
                itemId = spec.itemId,
                provider = spec.canonicalProvider?.name ?: spec.sourceProvider,
                apiShapeId = apiShapeId,
                operationKey = "$apiShapeId:${spec.itemId}",
                cacheKey = "audit:${spec.name}:$apiShapeId",
                workClass = if (spec.trigger == "FOCUSED_HOME_ITEM") "USER_VISIBLE" else "BACKGROUND_HYDRATION",
                executedNetwork = spec.metadataNetworkExecuted
            )
        }
        val cacheDecisions = spec.runtimeApiShapes.map { apiShapeId ->
            CacheDecisionEvent(
                itemId = spec.itemId,
                provider = spec.canonicalProvider?.name ?: spec.sourceProvider,
                apiShapeId = apiShapeId,
                cacheKey = "audit:${spec.name}:$apiShapeId",
                decision = if (spec.metadataNetworkExecuted) CacheDecision.MISS_THEN_NETWORK else CacheDecision.HIT,
                ttlMs = 604_800_000L,
                staleWindowMs = 2_592_000_000L,
                reason = if (spec.metadataNetworkExecuted) "hydration fetched canonical overlay" else "hydration applied cached overlay"
            )
        }
        val item = ItemExecutionReport(
            itemId = spec.itemId,
            itemType = spec.itemType,
            addonFields = spec.previewFields,
            firstPaint = firstPaint,
            routing = route,
            stableIdBundle = stableIdBundle,
            providerPlan = null,
            runtimeCalls = runtimeCalls,
            cacheDecisions = cacheDecisions,
            resolverSchedule = null,
            selectedFields = selectedFieldsAfterHydration,
            forbiddenOverwrites = emptyList(),
            continueWatchingSnapshot = null,
            identityResolution = null,
            productionCallerOwnership = emptyList(),
            localization = null,
            violations = emptyList(),
            events = buildList {
                add(AuditEvent.FirstPaint(firstPaint))
                route?.let { add(AuditEvent.Route(it)) }
                stableIdBundle?.let { add(AuditEvent.StableIdBundle(it)) }
                add(AuditEvent.HomeUpdate(spec.homeUpdate))
                runtimeCalls.forEach { add(AuditEvent.RuntimeCall(it)) }
                cacheDecisions.forEach { add(AuditEvent.CacheDecisionEventRecord(it)) }
                selectedFieldsBeforeHydration.forEach { add(AuditEvent.FieldSelected(it)) }
                selectedFieldsAfterHydration.forEach { add(AuditEvent.FieldSelected(it)) }
            },
            railSource = spec.railSource,
            sourceProvider = spec.sourceProvider,
            sourcePayloadFieldsUsed = spec.previewFields.filterValues { it != null }.keys,
            routingAfterVisible = route,
            selectedFieldsBeforeHydration = selectedFieldsBeforeHydration,
            selectedFieldsAfterHydration = selectedFieldsAfterHydration,
            identityMappingsHarvested = spec.identityMappingsHarvested,
            homeUpdate = spec.homeUpdate
        )
        return MetadataExecutionReport(
            schemaVersion = METADATA_AUDIT_SCHEMA_VERSION,
            provenance = provenanceProvider.current(),
            verdict = AuditVerdict.PASS,
            scenario = scenario,
            fixtureName = "synthetic/metadata/home-updates/${spec.name}.json",
            generatedAtEpochMs = System.currentTimeMillis(),
            items = listOf(item),
            summaries = buildSummary(listOf(item)),
            policyViolations = emptyList()
        )
    }

    private fun Map<String, String?>.toHomeFieldSelections(
        itemId: String,
        selectedProvider: String,
        sourceRole: String,
        ownershipRuleSuffix: String,
        changedFields: List<String> = emptyList(),
        previewProvider: String? = null,
        previewSourceRole: String? = null
    ): List<FieldSelectedEvent> {
        return mapNotNull { (field, value) ->
            value?.takeIf { field in displayFieldNames }?.let {
                val changed = field in changedFields
                FieldSelectedEvent(
                    itemId = itemId,
                    field = field,
                    selectedProvider = if (changed) selectedProvider else previewProvider ?: selectedProvider,
                    sourceRole = if (changed) sourceRole else previewSourceRole ?: sourceRole,
                    valuePreview = it,
                    rejectedCandidates = if (changed && previewProvider != null && previewSourceRole != null) {
                        listOf(
                            RejectedCandidateReport(
                                provider = previewProvider,
                                sourceRole = previewSourceRole,
                                reason = "primary canonical field available"
                            )
                        )
                    } else {
                        emptyList()
                    },
                    ownershipRule = if (changed) {
                        "$field $ownershipRuleSuffix"
                    } else {
                        "$field remains from first paint preview"
                    }
                )
            }
        }
    }

    private val displayFieldNames = setOf(
        "title",
        "poster",
        "backdrop",
        "overview",
        "rating",
        "runtime",
        "year"
    )

    private fun RailScenarioSpec.toRailItemPreview(generatedAtMs: Long): RailItemPreview {
        return RailItemPreview(
            railId = name,
            railSource = railSource.toAuditRailSource(),
            sourceProvider = sourceProvider.toAuditProviderId(),
            sourceItemId = itemId,
            itemType = ContentType.fromString(itemType),
            stableIds = stableIds(),
            display = previewDisplaySeed(),
            sourcePayloadQuality = SourcePayloadQuality.RICH_PREVIEW,
            sourcePayloadHash = "${name}:${previewFields.filterValues { it != null }.keys.sorted().joinToString(",")}",
            generatedAtMs = generatedAtMs,
            hydrationState = RailHydrationState.PREVIEW_ONLY
        )
    }

    private fun RailScenarioSpec.previewDisplaySeed(): RailDisplaySeed {
        val year = previewFields["year"]?.toIntOrNull()
        return RailDisplaySeed(
            title = previewFields["title"],
            year = year,
            releaseDate = previewFields["year"]?.takeIf { year == null },
            overview = previewFields["overview"],
            posterUrl = previewFields["poster"],
            backdropUrl = previewFields["backdrop"]
        )
    }

    private fun RailScenarioSpec.stableIds(): ProviderIds {
        val targetValues = targetIds.values
        return ProviderIds(
            imdb = imdbId,
            tmdb = targetValues.idValue("tmdb"),
            tvdb = targetValues.idValue("tvdb"),
            trakt = itemId.takeIf { sourceProvider == "TRAKT" },
            simkl = itemId.takeIf { sourceProvider == "SIMKL" },
            kitsu = itemId.removePrefix("kitsu:").takeIf { sourceProvider == "KITSU" }
        )
    }

    private fun RailScenarioSpec.toStableIdBundleEvent(route: RouteEvent): StableIdBundleEvent {
        val canonicalId = route.targetIds[route.provider]
        val stableIds = stableIds()
        return StableIdBundleEvent(
            itemKey = "$itemType:$itemId",
            itemType = itemType,
            trigger = "VISIBLE_HOME_HYDRATION",
            status = stableIdBundleStatus(
                canonicalId = canonicalId,
                imdbId = stableIds.imdb
            ),
            canonicalProvider = route.provider.name,
            canonicalId = canonicalId,
            imdbId = stableIds.imdb,
            networkExecuted = requiresIdentityNetwork,
            evidence = listOf(
                StableIdBundleEvidenceEvent(
                    source = if (requiresIdentityNetwork) "provider.identity_lookup" else "knownIds",
                    target = route.provider.name,
                    networkExecuted = requiresIdentityNetwork,
                    resultId = canonicalId
                )
            )
        )
    }

    private fun stableIdBundleStatus(canonicalId: String?, imdbId: String?): String {
        val hasCanonical = !canonicalId.isNullOrBlank()
        val hasImdb = !imdbId.isNullOrBlank()
        return when {
            hasCanonical && hasImdb -> "CANONICAL_AND_RATING_READY"
            hasCanonical -> "CANONICAL_READY_RATING_UNRESOLVED"
            hasImdb -> "PREVIEW_IDS_ONLY"
            else -> "UNRESOLVED"
        }
    }

    private fun Iterable<String>.idValue(prefix: String): String? {
        return firstNotNullOfOrNull { value ->
            value.removePrefix("$prefix:").takeIf { it != value }
        }
    }

    private fun String.toAuditRailSource(): RailSource {
        return when (this) {
            "BUILT_IN_TRAKT" -> RailSource.BUILT_IN_TRAKT
            "BUILT_IN_MDBLIST", "MDBLIST" -> RailSource.BUILT_IN_MDBLIST
            "BUILT_IN_TMDB", "TMDB" -> RailSource.BUILT_IN_TMDB
            "BUILT_IN_KITSU", "KITSU" -> RailSource.BUILT_IN_KITSU
            "BUILT_IN_SIMKL_DISCOVERY", "SIMKL_JSON" -> RailSource.BUILT_IN_SIMKL_DISCOVERY
            else -> RailSource.ADDON_CATALOG
        }
    }

    private fun String.toAuditProviderId(): ProviderId? {
        return runCatching { ProviderId.valueOf(this) }.getOrNull()
    }

    private fun MetaPreview.toAuditFirstPaintEvent(fieldsUsed: Set<String>): FirstPaintEvent {
        return FirstPaintEvent(
            itemId = id,
            itemType = apiType,
            source = firstPaintSource.name,
            fieldsUsed = fieldsUsed,
            routerExecuted = false,
            networkExecuted = false
        )
    }

    private fun rejectedCandidatesFor(
        field: ResolvedField,
        scenario: MetadataAuditScenario,
        resolverRejectedCandidates: List<Map<String, Any?>>
    ): List<RejectedCandidateReport> {
        val rejected = mutableListOf<RejectedCandidateReport>()
        if (field == ResolvedField.TITLE && scenario.injectSecondaryTitleOverwrite) {
            rejected += RejectedCandidateReport(
                provider = com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.KITSU.name,
                reason = "PRIMARY owner selected; secondary title rejected"
            )
        }
        rejected += resolverRejectedCandidates.mapNotNull(::rejectedCandidateReport)
        return rejected
    }

    private fun rejectedCandidateReport(candidate: Map<String, Any?>): RejectedCandidateReport? {
        val provider = candidate["provider"]?.toString()
            ?: candidate["sourceProvider"]?.toString()
            ?: return null
        val reason = candidate["reason"]?.toString() ?: return null
        return RejectedCandidateReport(
            provider = provider,
            sourceRole = candidate["sourceRole"]?.toString(),
            reason = reason
        )
    }

    private fun buildArtworkAudit(
        itemId: String,
        scenario: MetadataAuditScenario,
        selectedFields: List<FieldSelectedEvent>,
        runtimeCalls: List<RuntimeCallEvent>,
        cacheDecisions: List<CacheDecisionEvent>
    ): List<ArtworkAuditEntry> {
        val artworkProvider = scenario.premiumArtworkProvider ?: return emptyList()
        val apiShapeId = artworkProviderApiShapeId(artworkProvider)
        val runtimeCall = runtimeCalls.lastOrNull { it.apiShapeId == apiShapeId }
        val cacheDecision = cacheDecisions.lastOrNull { it.apiShapeId == apiShapeId }
        val selectedPoster = selectedFields.firstOrNull { it.field == "poster" }
        val selectedArtworkPoster = selectedPoster?.takeIf {
            it.sourceRole == com.nexio.tv.core.metadata.router.SourceRole.ARTWORK.name
        }
        val selectedProvider = selectedArtworkPoster?.selectedProvider ?: artworkProvider
        val rejectedCandidates = (selectedArtworkPoster ?: selectedPoster)
            ?.rejectedCandidates
            .orEmpty()
            .map { rejected ->
                mapOf(
                    "provider" to rejected.provider,
                    "sourceRole" to rejected.sourceRole,
                    "reason" to rejected.reason
                )
            } + switchedProviderRejectedCandidate(
                previousProvider = scenario.previousPremiumArtworkProvider,
                selectedProvider = selectedProvider
            )
        val assetKey = "artwork:asset:$selectedProvider:$itemId:poster"
        val coilModel = if (selectedArtworkPoster != null) {
            "nexio-artwork://asset/$assetKey"
        } else {
            "nexio-placeholder://poster"
        }
        return listOf(
            ArtworkAuditEntry(
                field = "poster",
                selectedProvider = selectedProvider,
                sourceRole = com.nexio.tv.core.metadata.router.SourceRole.ARTWORK.name,
                decisionKey = "artwork:decision:$selectedProvider:$itemId:poster",
                assetKey = assetKey,
                assetCacheDecision = cacheDecision?.decision?.name,
                runtimeApiShapeId = runtimeCall?.apiShapeId ?: apiShapeId,
                networkExecuted = runtimeCall?.executedNetwork ?: false,
                coilModel = coilModel,
                rawRemoteUrlUsedByUi = false,
                rejectedCandidates = rejectedCandidates
            )
        )
    }

    private fun artworkProviderApiShapeId(artworkProvider: String): String =
        when (artworkProvider) {
            "TOP_POSTERS" -> "topposters.poster_template"
            "RPDB" -> "rpdb.poster_template"
            else -> "${artworkProvider.lowercase()}.poster_template"
        }

    private fun switchedProviderRejectedCandidate(
        previousProvider: String?,
        selectedProvider: String
    ): List<Map<String, String?>> =
        if (previousProvider != null && previousProvider != selectedProvider) {
            listOf(
                mapOf(
                    "provider" to previousProvider,
                    "sourceRole" to com.nexio.tv.core.metadata.router.SourceRole.ARTWORK.name,
                    "reason" to "active premium artwork provider switched to $selectedProvider"
                )
            )
        } else {
            emptyList()
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
        toAuditEvent(itemId = item.id, itemType = item.type)

    private fun MetadataRoute.toAuditEvent(itemId: String, itemType: String): RouteEvent =
        RouteEvent(
            itemId = itemId,
            parentId = parentId,
            itemType = itemType,
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
        toAuditEvent(itemId = item.id)

    private fun ProviderExecutionPlan.toAuditEvent(itemId: String): ProviderPlanEvent =
        ProviderPlanEvent(
            itemId = itemId,
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
            // Stub resolver — behavior is reconfigured per-scenario in configurePosterResolverForScenario().
            // Starts with getActiveProvider() returning null (no-op) until a scenario with
            // premiumArtworkProvider overrides it.
            val posterResolver = mockk<PosterRatingsUrlResolver>(relaxed = true)
            coEvery { posterResolver.getActiveProvider() } returns null
            val rpdbAdapter = RpdbMetadataProviderAdapter(posterResolver)
            val topPostersAdapter = TopPostersMetadataProviderAdapter(posterResolver, mockk(relaxed = true))
            val allAdapters: Set<MetadataProviderAdapter> = adapters.toSet() + rpdbAdapter + topPostersAdapter
            val router = MetadataRouter(
                normalizer = MetadataRequestNormalizer(traceEvents = TraceMetadataEvents(RecordingTraceSink()) { null }),
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
                            // Known scenario-specific identity mappings required by rail audit specs.
                            // The "tv" raw value comes from MetadataIdParser parsing "tmdb:tv:1399" as
                            // scheme=TMDB with value="tv" (second colon segment). Map it to the correct
                            // TVDB ID so rail scenarios that use TMDB TV source item IDs route correctly.
                            private val tmdbToTvdbMappings = mapOf(
                                "tv" to "tvdb:121361",   // tmdb:tv:1399 "Game of Thrones"
                                "1399" to "tvdb:121361"  // tmdb:1399 fallback
                            )
                            override suspend fun tmdbToTvdb(tmdbId: String): String? =
                                tmdbToTvdbMappings[tmdbId] ?: "tvdb:$tmdbId"
                            override suspend fun imdbToTvdb(imdbId: String): String? = "tvdb:$imdbId"
                            override suspend fun tvdbToTmdb(tvdbId: String): String? = "tmdb:$tvdbId"
                        }
                    ),
                    providerPlanRunner = ProviderPlanRunner(allAdapters),
                    fieldResolver = FieldResolver()
                ),
                adapters = adapters,
                posterResolver = posterResolver
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
                fixtureName = "netflix_movie_nfx.json",
                scenario = MetadataAuditScenario("premium-artwork-topposters-home", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt16431404"), premiumArtworkProvider = "TOP_POSTERS", cacheMode = AuditCacheMode.WARM_FRESH)
            ),
            ScenarioSpec(
                fixtureName = "netflix_movie_nfx.json",
                scenario = MetadataAuditScenario("premium-artwork-rpdb-home", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt16431404"), premiumArtworkProvider = "RPDB", cacheMode = AuditCacheMode.WARM_FRESH)
            ),
            ScenarioSpec(
                fixtureName = "netflix_movie_nfx.json",
                scenario = MetadataAuditScenario("premium-artwork-topposters-detail", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt16431404"), premiumArtworkProvider = "TOP_POSTERS", cacheMode = AuditCacheMode.COLD)
            ),
            ScenarioSpec(
                fixtureName = "netflix_movie_nfx.json",
                scenario = MetadataAuditScenario("premium-artwork-rpdb-detail", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt16431404"), premiumArtworkProvider = "RPDB", cacheMode = AuditCacheMode.COLD)
            ),
            ScenarioSpec(
                fixtureName = "netflix_movie_nfx.json",
                scenario = MetadataAuditScenario(
                    name = "premium-artwork-switch-provider",
                    depth = MetadataDepth.DETAIL_CORE,
                    visibleItemIds = setOf("tt16431404"),
                    premiumArtworkProvider = "RPDB",
                    cacheMode = AuditCacheMode.COLD,
                    previousPremiumArtworkProvider = "TOP_POSTERS"
                )
            ),
            ScenarioSpec(
                fixtureName = "netflix_movie_nfx.json",
                scenario = MetadataAuditScenario("premium-artwork-cache-hit", MetadataDepth.DETAIL_CORE, visibleItemIds = setOf("tt16431404"), premiumArtworkProvider = "TOP_POSTERS", cacheMode = AuditCacheMode.WARM_FRESH)
            ),
            ScenarioSpec(
                fixtureName = "netflix_movie_nfx.json",
                scenario = MetadataAuditScenario(
                    name = "premium-artwork-failure-fallback",
                    depth = MetadataDepth.DETAIL_CORE,
                    visibleItemIds = setOf("tt16431404"),
                    premiumArtworkProvider = "TOP_POSTERS",
                    cacheMode = AuditCacheMode.COLD,
                    premiumArtworkFetchFails = true
                )
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

        private val railScenarioSpecs = listOf(
            RailScenarioSpec(
                name = "trakt-rail-first-paint-title-year",
                railSource = "BUILT_IN_TRAKT",
                sourceProvider = "TRAKT",
                itemId = "trakt:movie:hope-2026",
                itemType = "movie",
                mediaKind = MetadataMediaKind.MOVIE,
                previewFields = mapOf("title" to "Hope", "year" to "2026")
            ),
            RailScenarioSpec(
                name = "trakt-rail-visible-hydrates-tvdb",
                railSource = "BUILT_IN_TRAKT",
                sourceProvider = "TRAKT",
                itemId = "trakt:show:signal-2026",
                itemType = "series",
                mediaKind = MetadataMediaKind.SERIES,
                previewFields = mapOf("title" to "Signal", "year" to "2026"),
                routeProvider = com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TVDB,
                apiShapeId = "tvdb.series.extended",
                targetIds = mapOf(com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TVDB to "tvdb:1001"),
                usedInputs = setOf("railSource", "sourceProvider", "trakt.ids.tvdb"),
                hydratedFields = mapOf(
                    "title" to "Signal TVDB Canonical",
                    "poster" to "https://example.test/tvdb-signal.jpg",
                    "overview" to "Hydrated TVDB series overview"
                )
            ),
            RailScenarioSpec(
                name = "mdblist-rail-first-paint-rich-preview",
                railSource = "BUILT_IN_MDBLIST",
                sourceProvider = "MDBLIST",
                itemId = "mdblist:movie:aurora",
                itemType = "movie",
                mediaKind = MetadataMediaKind.MOVIE,
                previewFields = mapOf(
                    "title" to "Aurora",
                    "year" to "2026",
                    "poster" to "https://example.test/mdblist-aurora.jpg",
                    "overview" to "MDBList rich preview"
                )
            ),
            RailScenarioSpec(
                name = "tmdb-movie-rail-first-paint-rich-preview",
                railSource = "BUILT_IN_TMDB",
                sourceProvider = "TMDB",
                itemId = "tmdb:movie:501",
                itemType = "movie",
                mediaKind = MetadataMediaKind.MOVIE,
                previewFields = mapOf(
                    "title" to "TMDB Preview Movie",
                    "year" to "2026",
                    "poster" to "https://example.test/tmdb-movie.jpg",
                    "backdrop" to "https://example.test/tmdb-backdrop.jpg"
                )
            ),
            RailScenarioSpec(
                name = "tmdb-tv-rail-preview-then-tvdb-hydration",
                railSource = "BUILT_IN_TMDB",
                sourceProvider = "TMDB",
                itemId = "tmdb:tv:1399",
                itemType = "series",
                mediaKind = MetadataMediaKind.SERIES,
                previewFields = mapOf(
                    "title" to "TMDB TV Preview",
                    "poster" to "https://example.test/tmdb-tv.jpg"
                ),
                routeProvider = com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TVDB,
                apiShapeId = "tvdb.series.extended",
                targetIds = mapOf(
                    com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TMDB to "tmdb:1399",
                    com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TVDB to "tvdb:121361"
                ),
                imdbId = "tt0944947",
                usedInputs = setOf("railSource", "sourceProvider", "tmdb.ids.tvdb"),
                hydratedFields = mapOf(
                    "title" to "TVDB Hydrated Series",
                    "poster" to "https://example.test/tvdb-tv.jpg",
                    "overview" to "TVDB replaced rail preview fields"
                )
            ),
            RailScenarioSpec(
                name = "kitsu-rail-first-paint-rich-preview",
                railSource = "BUILT_IN_KITSU",
                sourceProvider = "KITSU",
                itemId = "kitsu:7442",
                itemType = "series",
                mediaKind = MetadataMediaKind.SERIES,
                previewFields = mapOf(
                    "title" to "Kitsu Preview Anime",
                    "poster" to "https://example.test/kitsu-anime.jpg",
                    "overview" to "Kitsu rich preview"
                )
            ),
            RailScenarioSpec(
                name = "simkl-json-rail-first-paint-rich-preview",
                railSource = "BUILT_IN_SIMKL_DISCOVERY",
                sourceProvider = "SIMKL",
                itemId = "simkl:movie:77",
                itemType = "movie",
                mediaKind = MetadataMediaKind.MOVIE,
                previewFields = mapOf(
                    "title" to "Simkl JSON Movie",
                    "year" to "2026",
                    "poster" to "https://example.test/simkl-json.jpg",
                    "overview" to "Simkl JSON rich preview"
                )
            ),
            RailScenarioSpec(
                name = "simkl-json-rail-visible-hydrates-tmdb",
                railSource = "BUILT_IN_SIMKL_DISCOVERY",
                sourceProvider = "SIMKL",
                itemId = "simkl:movie:88",
                itemType = "movie",
                mediaKind = MetadataMediaKind.MOVIE,
                previewFields = mapOf("title" to "Simkl JSON Hydration", "year" to "2026"),
                routeProvider = com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TMDB,
                apiShapeId = "tmdb.movie.core",
                targetIds = mapOf(com.nexio.tv.core.metadata.router.MetadataPrimaryProvider.TMDB to "tmdb:5088"),
                usedInputs = setOf("railSource", "sourceProvider", "simkl.ids.tmdb"),
                hydratedFields = mapOf(
                    "title" to "TMDB Hydrated Movie",
                    "poster" to "https://example.test/tmdb-simkl.jpg",
                    "overview" to "TMDB replaced Simkl rail preview fields"
                )
            )
        )

        private val homeUpdateScenarioSpecs = listOf(
            HomeUpdateScenarioSpec(
                name = "addon_first_paint_then_hydrated_home_update",
                firstPaintSource = "ADDON_META_PREVIEW",
                railSource = null,
                sourceProvider = "NETFLIX",
                itemId = "tt16431404",
                itemType = "movie",
                mediaKind = MetadataMediaKind.MOVIE,
                previewFields = mapOf(
                    "title" to "Preview Movie",
                    "poster" to "https://example.test/addon-preview.jpg",
                    "overview" to "Addon preview overview"
                ),
                canonicalProvider = MetadataPrimaryProvider.TMDB,
                canonicalId = "tmdb:872585",
                imdbId = "tt16431404",
                runtimeApiShapes = listOf("tmdb.movie.core"),
                metadataNetworkExecuted = true,
                homeUpdate = HomeUpdateEvent(
                    before = mapOf("title" to "Preview Movie", "poster" to "https://example.test/addon-preview.jpg"),
                    after = mapOf("title" to "TMDB Hydrated Movie", "poster" to "https://example.test/tmdb-hydrated.jpg", "overview" to "TMDB hydrated overview"),
                    changedFields = listOf("title", "poster", "overview"),
                    rowOrderChanged = false,
                    focusChanged = false,
                    displayHashBefore = "addon-preview",
                    displayHashAfter = "addon-hydrated"
                ),
                usedInputs = setOf("preview.stableIds.imdb", "item.type")
            ),
            HomeUpdateScenarioSpec(
                name = "trakt_rail_first_paint_then_tvdb_update",
                firstPaintSource = "RAIL_PREVIEW",
                railSource = "BUILT_IN_TRAKT",
                sourceProvider = "TRAKT",
                itemId = "trakt:show:1",
                itemType = "series",
                mediaKind = MetadataMediaKind.SERIES,
                previewFields = mapOf("title" to "Breaking Bad", "year" to "2008"),
                canonicalProvider = MetadataPrimaryProvider.TVDB,
                canonicalId = "tvdb:81189",
                imdbId = "tt0903747",
                runtimeApiShapes = listOf("tvdb.series.extended"),
                metadataNetworkExecuted = true,
                homeUpdate = HomeUpdateEvent(
                    before = mapOf("title" to "Breaking Bad", "poster" to null),
                    after = mapOf("title" to "Breaking Bad", "poster" to "https://example.test/tvdb-breaking-bad.jpg", "overview" to "TVDB hydrated series overview"),
                    changedFields = listOf("poster", "overview"),
                    rowOrderChanged = false,
                    focusChanged = false,
                    displayHashBefore = "trakt-preview",
                    displayHashAfter = "trakt-tvdb-hydrated"
                ),
                usedInputs = setOf("trakt.ids.tvdb", "trakt.ids.imdb", "item.type"),
                identityMappingsHarvested = mapOf("trakt:show:1" to "tvdb:81189", "tt0903747" to "tvdb:81189")
            ),
            HomeUpdateScenarioSpec(
                name = "tmdb_movie_rail_first_paint_then_tmdb_update",
                firstPaintSource = "RAIL_PREVIEW",
                railSource = "BUILT_IN_TMDB",
                sourceProvider = "TMDB",
                itemId = "tmdb:550",
                itemType = "movie",
                mediaKind = MetadataMediaKind.MOVIE,
                previewFields = mapOf(
                    "title" to "Fight Club",
                    "poster" to "https://image.tmdb.org/t/p/w500/preview.jpg",
                    "rating" to "8.4"
                ),
                canonicalProvider = MetadataPrimaryProvider.TMDB,
                canonicalId = "tmdb:550",
                imdbId = "tt0137523",
                runtimeApiShapes = listOf("tmdb.movie.core", "custom_imdb.ratings"),
                metadataNetworkExecuted = true,
                homeUpdate = HomeUpdateEvent(
                    before = mapOf("title" to "Fight Club", "rating" to "8.4", "poster" to "https://image.tmdb.org/t/p/w500/preview.jpg"),
                    after = mapOf("title" to "Fight Club", "rating" to "8.8", "poster" to "https://image.tmdb.org/t/p/w500/hydrated.jpg"),
                    changedFields = listOf("rating", "poster"),
                    rowOrderChanged = false,
                    focusChanged = false,
                    displayHashBefore = "tmdb-movie-preview",
                    displayHashAfter = "tmdb-movie-hydrated-ratings"
                ),
                usedInputs = setOf("tmdb.id", "resolved.imdb", "item.type"),
                identityMappingsHarvested = mapOf("tmdb:550" to "tt0137523")
            ),
            HomeUpdateScenarioSpec(
                name = "tmdb_tv_rail_first_paint_then_tvdb_update",
                firstPaintSource = "RAIL_PREVIEW",
                railSource = "BUILT_IN_TMDB",
                sourceProvider = "TMDB",
                itemId = "tmdb:tv:1399",
                itemType = "series",
                mediaKind = MetadataMediaKind.SERIES,
                previewFields = mapOf("title" to "Game of Thrones", "poster" to "https://image.tmdb.org/t/p/w500/tv-preview.jpg"),
                canonicalProvider = MetadataPrimaryProvider.TVDB,
                canonicalId = "tvdb:121361",
                imdbId = "tt0944947",
                runtimeApiShapes = listOf("tvdb.series.extended"),
                metadataNetworkExecuted = true,
                homeUpdate = HomeUpdateEvent(
                    before = mapOf("title" to "Game of Thrones", "poster" to "https://image.tmdb.org/t/p/w500/tv-preview.jpg"),
                    after = mapOf("title" to "Game of Thrones", "poster" to "https://example.test/tvdb-got.jpg", "overview" to "TVDB hydrated TV overview"),
                    changedFields = listOf("poster", "overview"),
                    rowOrderChanged = false,
                    focusChanged = false,
                    displayHashBefore = "tmdb-tv-preview",
                    displayHashAfter = "tmdb-tv-tvdb-hydrated"
                ),
                usedInputs = setOf("tmdb.tv.id", "idMappingStore.tvdb", "item.type"),
                identityMappingsHarvested = mapOf("tmdb:tv:1399" to "tvdb:121361", "tt0944947" to "tvdb:121361")
            ),
            HomeUpdateScenarioSpec(
                name = "kitsu_rail_first_paint_then_kitsu_update",
                firstPaintSource = "RAIL_PREVIEW",
                railSource = "BUILT_IN_KITSU",
                sourceProvider = "KITSU",
                itemId = "kitsu:7442",
                itemType = "series",
                mediaKind = MetadataMediaKind.ANIME,
                previewFields = mapOf("title" to "Kitsu Preview Anime", "poster" to "https://example.test/kitsu-preview.jpg"),
                canonicalProvider = MetadataPrimaryProvider.KITSU,
                canonicalId = "kitsu:7442",
                imdbId = null,
                runtimeApiShapes = listOf("kitsu.anime.core"),
                metadataNetworkExecuted = true,
                homeUpdate = HomeUpdateEvent(
                    before = mapOf("title" to "Kitsu Preview Anime", "poster" to "https://example.test/kitsu-preview.jpg"),
                    after = mapOf("title" to "Kitsu Canonical Anime", "poster" to "https://example.test/kitsu-core.jpg", "overview" to "Kitsu hydrated anime overview"),
                    changedFields = listOf("title", "poster", "overview"),
                    rowOrderChanged = false,
                    focusChanged = false,
                    displayHashBefore = "kitsu-preview",
                    displayHashAfter = "kitsu-hydrated"
                ),
                usedInputs = setOf("kitsu.id", "item.type")
            ),
            HomeUpdateScenarioSpec(
                name = "simkl_rail_first_paint_then_tmdb_update",
                firstPaintSource = "RAIL_PREVIEW",
                railSource = "BUILT_IN_SIMKL_DISCOVERY",
                sourceProvider = "SIMKL",
                itemId = "simkl:movie:88",
                itemType = "movie",
                mediaKind = MetadataMediaKind.MOVIE,
                previewFields = mapOf("title" to "Simkl Preview Movie", "poster" to "https://simkl.in/posters/preview.jpg"),
                canonicalProvider = MetadataPrimaryProvider.TMDB,
                canonicalId = "tmdb:5088",
                imdbId = "tt0482571",
                runtimeApiShapes = listOf("tmdb.movie.core"),
                metadataNetworkExecuted = true,
                homeUpdate = HomeUpdateEvent(
                    before = mapOf("title" to "Simkl Preview Movie", "poster" to "https://simkl.in/posters/preview.jpg"),
                    after = mapOf("title" to "The Prestige", "poster" to "https://example.test/tmdb-prestige.jpg", "overview" to "TMDB hydrated Simkl preview"),
                    changedFields = listOf("title", "poster", "overview"),
                    rowOrderChanged = false,
                    focusChanged = false,
                    displayHashBefore = "simkl-preview",
                    displayHashAfter = "simkl-tmdb-hydrated"
                ),
                usedInputs = setOf("simkl.ids.tmdb", "simkl.ids.imdb", "item.type"),
                identityMappingsHarvested = mapOf("simkl:movie:88" to "tmdb:5088", "tt0482571" to "tmdb:5088")
            ),
            HomeUpdateScenarioSpec(
                name = "hydration_failure_keeps_preview",
                firstPaintSource = "RAIL_PREVIEW",
                railSource = "BUILT_IN_TRAKT",
                sourceProvider = "TRAKT",
                itemId = "trakt:show:missing",
                itemType = "series",
                mediaKind = MetadataMediaKind.SERIES,
                previewFields = mapOf("title" to "Preview Only Show", "year" to "2026"),
                canonicalProvider = MetadataPrimaryProvider.TVDB,
                canonicalId = null,
                imdbId = "tt0000000",
                runtimeApiShapes = emptyList(),
                metadataNetworkExecuted = false,
                homeUpdate = HomeUpdateEvent(
                    before = mapOf("title" to "Preview Only Show", "state" to "PREVIEW_ONLY"),
                    after = mapOf("title" to "Preview Only Show", "state" to "FAILED_USING_PREVIEW"),
                    changedFields = listOf("state"),
                    rowOrderChanged = false,
                    focusChanged = false,
                    displayHashBefore = "failure-preview",
                    displayHashAfter = "failure-state-updated"
                ),
                usedInputs = setOf("trakt.ids.imdb", "item.type")
            ),
            HomeUpdateScenarioSpec(
                name = "cache_hit_updates_home_without_network",
                firstPaintSource = "RAIL_PREVIEW",
                railSource = "BUILT_IN_TMDB",
                sourceProvider = "TMDB",
                itemId = "tmdb:872585",
                itemType = "movie",
                mediaKind = MetadataMediaKind.MOVIE,
                previewFields = mapOf("title" to "Preview Cached Movie", "poster" to "https://example.test/cache-preview.jpg"),
                canonicalProvider = MetadataPrimaryProvider.TMDB,
                canonicalId = "tmdb:872585",
                imdbId = "tt16431404",
                runtimeApiShapes = listOf("tmdb.movie.core"),
                metadataNetworkExecuted = false,
                homeUpdate = HomeUpdateEvent(
                    before = mapOf("title" to "Preview Cached Movie", "poster" to "https://example.test/cache-preview.jpg"),
                    after = mapOf("title" to "Cached TMDB Movie", "poster" to "https://example.test/cache-hit.jpg"),
                    changedFields = listOf("title", "poster"),
                    rowOrderChanged = false,
                    focusChanged = false,
                    displayHashBefore = "cache-preview",
                    displayHashAfter = "cache-hit-overlay"
                ),
                usedInputs = setOf("tmdb.id", "item.type")
            ),
            HomeUpdateScenarioSpec(
                name = "focused_item_hydrates_before_offscreen_items",
                firstPaintSource = "RAIL_PREVIEW",
                railSource = "BUILT_IN_TMDB",
                sourceProvider = "TMDB",
                itemId = "tmdb:focused:550",
                itemType = "movie",
                mediaKind = MetadataMediaKind.MOVIE,
                previewFields = mapOf("title" to "Focused Preview", "priority" to "P0"),
                canonicalProvider = MetadataPrimaryProvider.TMDB,
                canonicalId = "tmdb:550",
                imdbId = "tt0137523",
                trigger = "FOCUSED_HOME_ITEM",
                runtimeApiShapes = listOf("tmdb.movie.core"),
                metadataNetworkExecuted = false,
                homeUpdate = HomeUpdateEvent(
                    before = mapOf("title" to "Focused Preview", "priority" to "P0"),
                    after = mapOf("title" to "Focused Hydrated", "priority" to "P0"),
                    changedFields = listOf("title"),
                    rowOrderChanged = false,
                    focusChanged = false,
                    displayHashBefore = "focused-preview",
                    displayHashAfter = "focused-hydrated"
                ),
                usedInputs = setOf("focus.itemKey", "tmdb.id", "item.type")
            ),
            HomeUpdateScenarioSpec(
                name = "hydration_result_ignored_after_profile_switch",
                firstPaintSource = "RAIL_PREVIEW",
                railSource = "BUILT_IN_SIMKL_DISCOVERY",
                sourceProvider = "SIMKL",
                itemId = "simkl:movie:ignored",
                itemType = "movie",
                mediaKind = MetadataMediaKind.MOVIE,
                previewFields = mapOf("title" to "Profile Preview"),
                canonicalProvider = MetadataPrimaryProvider.TMDB,
                canonicalId = "tmdb:999",
                imdbId = "tt9999999",
                runtimeApiShapes = emptyList(),
                metadataNetworkExecuted = false,
                homeUpdate = HomeUpdateEvent(
                    before = mapOf("title" to "Profile Preview", "state" to "HYDRATING"),
                    after = mapOf("title" to "Profile Preview", "state" to "IGNORED_PROFILE_SWITCH"),
                    changedFields = listOf("state"),
                    rowOrderChanged = false,
                    focusChanged = false,
                    displayHashBefore = "profile-preview",
                    displayHashAfter = "profile-state-ignored"
                ),
                usedInputs = setOf("profile.session", "simkl.ids.tmdb", "item.type")
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

private data class RailScenarioSpec(
    val name: String,
    val railSource: String,
    val sourceProvider: String,
    val itemId: String,
    val itemType: String,
    val mediaKind: MetadataMediaKind,
    val previewFields: Map<String, String?>,
    val routeProvider: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider? = null,
    val apiShapeId: String = "",
    val targetIds: Map<com.nexio.tv.core.metadata.router.MetadataPrimaryProvider, String> = emptyMap(),
    val imdbId: String? = null,
    val requiresIdentityNetwork: Boolean = false,
    val usedInputs: Set<String> = emptySet(),
    val hydratedFields: Map<String, String?> = emptyMap()
)

private data class HomeUpdateScenarioSpec(
    val name: String,
    val firstPaintSource: String,
    val railSource: String?,
    val sourceProvider: String,
    val itemId: String,
    val itemType: String,
    val mediaKind: MetadataMediaKind,
    val previewFields: Map<String, String?>,
    val canonicalProvider: MetadataPrimaryProvider?,
    val canonicalId: String?,
    val imdbId: String?,
    val trigger: String = "VISIBLE_HOME_HYDRATION",
    val runtimeApiShapes: List<String>,
    val metadataNetworkExecuted: Boolean,
    val identityNetworkExecuted: Boolean = false,
    val homeUpdate: HomeUpdateEvent,
    val usedInputs: Set<String> = emptySet(),
    val identityMappingsHarvested: Map<String, String> = emptyMap()
)

private class AuditMetadataProviderAdapter(
    override val provider: com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
) : MetadataProviderAdapter {
    private var itemId: String = "unknown"
    private var trace: MetadataAuditTraceCollector? = null
    private var scenario: MetadataAuditScenario = MetadataAuditScenario("default", MetadataDepth.DETAIL_CORE)
    private var deterministicFields: Map<String, String?> = emptyMap()

    fun bind(
        itemId: String,
        trace: MetadataAuditTraceCollector,
        scenario: MetadataAuditScenario,
        deterministicFields: Map<String, String?> = emptyMap()
    ) {
        this.itemId = itemId
        this.trace = trace
        this.scenario = scenario
        this.deterministicFields = deterministicFields
    }

    fun reset() {
        itemId = "unknown"
        trace = null
        scenario = MetadataAuditScenario("default", MetadataDepth.DETAIL_CORE)
        deterministicFields = emptyMap()
    }

    override fun supports(step: ProviderPlanStep): Boolean =
        step.apiShapeId != "rpdb.poster_template" &&
            step.apiShapeId != "topposters.poster_template"

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
                fields = deterministicFields.toResolvedFieldValues(route).ifEmpty {
                    mapOf(
                        ResolvedField.CANONICAL_ID to FieldValue(route.targetIds[route.provider] ?: route.parentId, FieldOwner.PRIMARY),
                        ResolvedField.TITLE to FieldValue("Runtime ${route.provider.name} title", FieldOwner.PRIMARY),
                        ResolvedField.POSTER to FieldValue("https://example.test/${route.provider.name.lowercase()}-poster.jpg", FieldOwner.PRIMARY)
                    )
                }
            ),
            localizationPayloads = localizationPayloads
        )
    }

    private fun Map<String, String?>.toResolvedFieldValues(route: MetadataRoute): Map<ResolvedField, FieldValue> =
        buildMap<ResolvedField, FieldValue> {
            put(ResolvedField.CANONICAL_ID, FieldValue(route.targetIds[route.provider] ?: route.parentId, FieldOwner.PRIMARY))
            this@toResolvedFieldValues["title"]?.let { put(ResolvedField.TITLE, FieldValue(it, FieldOwner.PRIMARY)) }
            this@toResolvedFieldValues["overview"]?.let { put(ResolvedField.OVERVIEW, FieldValue(it, FieldOwner.PRIMARY)) }
            this@toResolvedFieldValues["poster"]?.let { put(ResolvedField.POSTER, FieldValue(it, FieldOwner.PRIMARY)) }
            this@toResolvedFieldValues["backdrop"]?.let { put(ResolvedField.BACKDROP, FieldValue(it, FieldOwner.PRIMARY)) }
            this@toResolvedFieldValues["runtime"]?.toIntOrNull()?.let { put(ResolvedField.RUNTIME, FieldValue(it, FieldOwner.PRIMARY)) }
            this@toResolvedFieldValues["year"]?.let { put(ResolvedField.RELEASE_DATE, FieldValue(it, FieldOwner.PRIMARY)) }
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
            else -> tmdbLanguage(route.language)
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
            else -> "en-US"
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
