package com.nexio.tv.core.trace

import com.google.gson.Gson
import com.nexio.tv.core.integration.ByteArrayIntegrationCacheStore
import com.nexio.tv.core.integration.DefaultIntegrationRuntime
import com.nexio.tv.core.integration.IntegrationBackoffManager
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationPlaybackGate
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSingleFlight
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.InMemoryIntegrationProviderBackoffDao
import com.nexio.tv.core.integration.ProfileBoundaryEnforcer
import com.nexio.tv.core.integration.ProfileExecutionContext
import com.nexio.tv.core.integration.ProviderRequestGate
import com.nexio.tv.core.integration.RecordingIntegrationAuditSink
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
}
