package com.nexio.tv.core.trace

import com.google.gson.Gson
import com.nexio.tv.core.integration.ByteArrayIntegrationCacheStore
import com.nexio.tv.core.integration.DefaultIntegrationRuntime
import com.nexio.tv.core.integration.IntegrationBackoffManager
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationCodec
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationPlaybackGate
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSingleFlight
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.InMemoryIntegrationProviderBackoffDao
import com.nexio.tv.core.integration.ProfileBoundaryEnforcer
import com.nexio.tv.core.integration.ProfileExecutionContext
import com.nexio.tv.core.integration.ProviderRequestGate
import com.nexio.tv.core.integration.RecordingIntegrationAuditSink
import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.integration.defaultIntegrationPolicyRegistry
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldResolver
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.InMemoryAnimeIdentityIndex
import com.nexio.tv.core.metadata.router.InMemoryIdMappingStore
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRequestNormalizer
import com.nexio.tv.core.metadata.router.MetadataRouter
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.ui.screens.home.toFirstPaintHomeDisplayMetadata
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end: drive real emission sites through a real FileRuntimeTraceSink, read the JSONL
 * back, run the validator. Catches schema drift between emission key names and validator
 * lookups that the per-site unit tests miss.
 */
class RuntimeTraceValidatorRealEmissionTest {
    @get:Rule val tmp = TemporaryFolder()

    private object StringIntegrationCodec : IntegrationCodec<String> {
        override val mimeType: String = "text/plain"
        override fun encode(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
        override fun decode(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)
    }

    @After
    fun resetStaticSinks() {
        ProfileBoundaryEnforcer.installTraceSink(NoopRuntimeTraceSink) { null }
        com.nexio.tv.data.repository.ContinueWatchingSnapshotService
            .installTraceSink(NoopRuntimeTraceSink) { null }
        FirstPaintTracer.install(
            events = TraceMetadataEvents(NoopRuntimeTraceSink, sessionId = { null }),
            profileHashProvider = { null }
        )
    }

    @Test
    fun `real emissions across runtime metadata profile and first-paint validate as PASS`() = runTest {
        // 1. Build a real session + file sink.
        val tracesRoot = tmp.newFolder("traces")
        val gson = Gson()
        val manager = TraceSessionManager(
            tracesRoot = tracesRoot,
            gson = gson,
            clock = { 1_700_000_000_000L },
            buildInfo = TraceBuildInfo(
                appVersion = "1.0",
                buildType = "debug",
                gitSha = "deadbeef",
                deviceModel = "Pixel",
                androidVersion = "14"
            )
        )
        manager.start(TraceMode.SAFE_METADATA_RUNTIME, activeProfileHash = "ph_active")
        val session = checkNotNull(manager.activeSession())
        val sink = manager.activeSink()

        // 2. Wire static-sink slots that production wires via Hilt.
        ProfileBoundaryEnforcer.installTraceSink(sink) { session.traceSessionId }
        com.nexio.tv.data.repository.ContinueWatchingSnapshotService
            .installTraceSink(sink) { session.traceSessionId }
        val metadataEvents = TraceMetadataEvents(sink, sessionId = { session.traceSessionId })
        FirstPaintTracer.install(metadataEvents, profileHashProvider = { "ph_first_paint" })

        // 3. Drive runtime.operation_start + runtime.operation_finish via DefaultIntegrationRuntime.call().
        val registry = defaultIntegrationPolicyRegistry()
        val runtime = DefaultIntegrationRuntime(
            cacheStore = ByteArrayIntegrationCacheStore(),
            requestGate = ProviderRequestGate(registry),
            backoffManager = IntegrationBackoffManager(InMemoryIntegrationProviderBackoffDao()),
            singleFlight = IntegrationSingleFlight(),
            playbackGate = IntegrationPlaybackGate(),
            registry = registry,
            auditSink = RecordingIntegrationAuditSink(),
            traceSink = sink
        )
        runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = "tmdb.movie.core",
                operationKey = "tmdb.movie.core:550:en",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.GlobalContent,
                profileContext = null,
                call = { IntegrationCallResult.Success("ok") }
            )
        )

        // 4. Drive metadata.route_decision via MetadataRouter.
        val router = MetadataRouter(
            normalizer = MetadataRequestNormalizer(traceEvents = metadataEvents),
            animeIdentityIndex = InMemoryAnimeIdentityIndex(),
            idMappingStore = InMemoryIdMappingStore(),
            traceEvents = metadataEvents
        )
        router.route(
            MetadataRequest(
                contentId = "kitsu:7442",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(itemType = "series"),
                language = "en",
                seasonNumber = null,
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        // 5. Drive metadata.field_selected via FieldResolver.
        val resolver = FieldResolver(traceEvents = metadataEvents)
        resolver.resolve(
            primary = MetadataCandidate(
                provider = MetadataPrimaryProvider.TMDB,
                fields = mapOf(
                    ResolvedField.TITLE to FieldValue(value = "Hello", owner = FieldOwner.PRIMARY)
                )
            ),
            secondary = emptyList()
        )

        // 6. Drive profile.boundary_check (PASS) via ProfileBoundaryEnforcer.
        ProfileBoundaryEnforcer.validateRequest(
            provider = IntegrationProvider.TMDB,
            scope = IntegrationScope.GlobalContent,
            cacheKey = "metadata:tmdb:550",
            profileContext = null
        )
        // Also drive a Profile-scoped check to exercise ProfileBoundCallHasProfileHash.
        val profileCtx = ProfileExecutionContext(
            profileId = 1,
            sessionId = "px",
            displayLanguage = "en",
            region = "US"
        )
        ProfileBoundaryEnforcer.validateRequest(
            provider = IntegrationProvider.TMDB,
            scope = IntegrationScope.ProfileLocal(profileId = 1),
            cacheKey = "profile:1:resolved",
            profileContext = profileCtx
        )

        // 7. Drive metadata.first_paint via the UI wrapper.
        MetaPreview(
            id = "tt0111161",
            type = ContentType.MOVIE,
            name = "The Shawshank Redemption",
            poster = "https://example.com/poster.jpg",
            posterShape = PosterShape.POSTER,
            background = "https://example.com/bg.jpg",
            logo = null,
            description = "Two imprisoned men bond over a number of years.",
            releaseInfo = "1994",
            imdbRating = 9.3f,
            genres = listOf("Drama")
        ).toFirstPaintHomeDisplayMetadata()

        // 8. Stop session, read JSONL, parse, validate.
        manager.stop()
        val eventsFile = File(File(tracesRoot, session.traceSessionId), "trace-events.jsonl")
        assertTrue("trace-events.jsonl exists", eventsFile.isFile)

        val parsed = eventsFile.readLines()
            .filter { it.isNotBlank() }
            .map { gson.fromJson(it, TraceEventEnvelope::class.java) as TraceEventEnvelope<*> }
        assertTrue("expected at least 5 events, got ${parsed.size}", parsed.size >= 5)

        val report = RuntimeTraceValidator().validate(parsed.asSequence())
        assertEquals(
            "real-emission session must validate PASS — schema drift between an emission site and a validator rule. failures=${report.failures}",
            TraceVerdict.PASS,
            report.verdict
        )
    }

    @Test
    fun `composite production trace sink records runtime cache decisions with active session id`() = runTest {
        val tracesRoot = tmp.newFolder("composite-traces")
        val gson = Gson()
        val manager = TraceSessionManager(
            tracesRoot = tracesRoot,
            gson = gson,
            clock = { 1_700_000_000_000L },
            buildInfo = TraceBuildInfo(
                appVersion = "1.0",
                buildType = "releaseProfileable",
                gitSha = "deadbeef",
                deviceModel = "Pixel",
                androidVersion = "14"
            )
        )
        manager.start(TraceMode.SAFE_METADATA_RUNTIME, activeProfileHash = "ph_active")
        val session = checkNotNull(manager.activeSession())
        val sink = CompositeRuntimeTraceSink(
            listOf(
                manager.activeSink(),
                LogcatRuntimeTraceSink(
                    gate = object : LogcatChannelGate {
                        override fun isEnabled(channel: LogcatTraceChannel): Boolean = false
                    }
                )
            )
        )
        val registry = defaultIntegrationPolicyRegistry()
        val runtime = DefaultIntegrationRuntime(
            cacheStore = ByteArrayIntegrationCacheStore(),
            requestGate = ProviderRequestGate(registry),
            backoffManager = IntegrationBackoffManager(InMemoryIntegrationProviderBackoffDao()),
            singleFlight = IntegrationSingleFlight(),
            playbackGate = IntegrationPlaybackGate(),
            registry = registry,
            auditSink = RecordingIntegrationAuditSink(),
            traceSink = sink
        )
        val spec = IntegrationSpec(
            provider = IntegrationProvider.KITSU,
            apiShapeId = "kitsu.anime.core",
            operationKey = "kitsu.anime.core:7442:en",
            cacheKey = "metadata:KITSU:kitsu.anime.core:7442:en",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(ttlMs = 60_000L),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
            profileContext = null,
            load = { IntegrationLoadResult.Success("payload") }
        )

        val first = runtime.get(spec)
        val second = runtime.get(spec)

        assertTrue("first runtime.get should return Updated, got $first", first is IntegrationFetchResult.Updated)
        assertTrue("second runtime.get should return Fresh, got $second", second is IntegrationFetchResult.Fresh)

        manager.stop()
        val eventsFile = File(File(tracesRoot, session.traceSessionId), "trace-events.jsonl")
        assertTrue("trace-events.jsonl exists", eventsFile.isFile)
        val parsed = eventsFile.readLines()
            .filter { it.isNotBlank() }
            .map { gson.fromJson(it, TraceEventEnvelope::class.java) as TraceEventEnvelope<*> }
        val hit = parsed.firstOrNull { event ->
            val payload = event.payload as? Map<*, *> ?: return@firstOrNull false
            event.eventType == "runtime.cache_decision" &&
                payload["decision"] == TraceCacheDecision.HIT.name &&
                payload["networkSuppressed"] == true
        }

        assertTrue(
            "expected runtime.cache_decision HIT with networkSuppressed=true, got events=${parsed.map { it.eventType }}",
            hit != null
        )
        assertEquals(session.traceSessionId, hit!!.traceSessionId)
    }

    /**
     * F2-E-03: drive a TVDB episode-bundle scenario through real TraceMetadataEvents emission
     * and validate that LocalizationPlanPrecedesProviderSteps sees PASS end-to-end.
     *
     * Emits (in sequence):
     *   1. metadata.route_decision  (provider=TVDB) — triggers rule scan
     *   2. metadata.localization_plan (provider=TVDB) — satisfies the rule
     *   3. metadata.provider_plan   (provider=TVDB) — terminates the rule's inner scan
     *   4. metadata.field_selected  ×2 (TITLE, OVERVIEW for episodes) — exercises episode-bundle fields
     *
     * Uses Option B (direct emit via TraceMetadataEvents) because the existing test uses real
     * emitters only via the integration runtime and router — TvdbMetadataProviderAdapter is not
     * wired in this harness and pulling in its full dependency graph would exceed the task scope.
     */
    @Test
    fun `LocalizationPlanPrecedesProviderSteps validates TVDB episode-bundle real emission`() = runTest {
        val sink = RecordingTraceSink()
        val traceEvents = TraceMetadataEvents(sink, sessionId = { "tvdb-episode-bundle-session" })

        // 1. route_decision for TVDB series (tvdb:81189 = Breaking Bad)
        traceEvents.emitRouteDecision(
            contentId = "tvdb:81189",
            parentId = "tvdb:81189",
            itemType = "series",
            provider = "TVDB",
            mediaKind = "SERIES",
            reason = "primary_provider_match",
            usedInputs = listOf("tvdbId", "contentType"),
            ignoredInputs = emptyList(),
            targetIdRequiresIdentityResolution = false,
            targetIds = mapOf("tvdb" to "81189")
        )

        // 2. localization_plan for TVDB — this is the event LocalizationPlanPrecedesProviderSteps checks for.
        //    localeCollapsedToFallback = false because the user's locale (en) is on the TVDB whitelist.
        traceEvents.emitLocalizationPlan(
            contentId = "tvdb:81189",
            provider = "TVDB",
            policyVersion = 2,
            requestedLanguage = "en",
            fallbackLanguage = "en",
            requestedIsFallback = true,
            allowProviderFallbackForMissingLocalizedFields = true,
            perEpisodeFallbacksAttempted = 0,
            perEpisodeFallbacksAllowed = 5,
            localeCollapsedToFallback = false
        )

        // 3. provider_plan — terminates the inner loop in LocalizationPlanPrecedesProviderSteps.
        //    requiresIdentityResolution = false to avoid triggering IdentityResolutionPrecedesProviderConflict.
        traceEvents.emitProviderPlan(
            contentId = "tvdb:81189",
            provider = "TVDB",
            mediaKind = "SERIES",
            depth = "DETAIL_CORE",
            steps = listOf(
                mapOf(
                    "apiShapeId" to "tvdb.series.episodes.language",
                    "requiresIdentityResolution" to false,
                    "cacheKeyTemplate" to "tvdb:series:{tvdbId}:episodes:lang:{lang}"
                )
            )
        )

        // 4a. field_selected for episode title — TITLE with ownershipRule set (FieldHasOwnershipRule check)
        //     and empty rejectedCandidates (SecondaryDoesNotOverwritePrimary check on protected fields)
        traceEvents.emitFieldSelected(
            contentId = "tvdb:81189:S01E01",
            field = "TITLE",
            selectedProvider = "TVDB",
            sourceRole = "PRIMARY",
            valuePreview = "Pilot",
            ownershipRule = "primary_always_wins",
            rejectedCandidates = emptyList()
        )

        // 4b. field_selected for episode overview
        traceEvents.emitFieldSelected(
            contentId = "tvdb:81189:S01E01",
            field = "OVERVIEW",
            selectedProvider = "TVDB",
            sourceRole = "PRIMARY",
            valuePreview = "Walt White, a struggling high school chemistry teacher…",
            ownershipRule = "primary_always_wins",
            rejectedCandidates = emptyList()
        )

        // Validate: pass recorded events through the full validator.
        val report = RuntimeTraceValidator().validate(sink.events.asSequence())
        assertEquals(
            "TVDB episode-bundle emission must validate PASS — LocalizationPlanPrecedesProviderSteps or another rule is failing. failures=${report.failures}",
            TraceVerdict.PASS,
            report.verdict
        )
    }
}
