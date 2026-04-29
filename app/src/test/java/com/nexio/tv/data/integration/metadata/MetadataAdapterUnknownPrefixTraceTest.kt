package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.trace.TraceMetadataEvents
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2-C-07 pin: when TmdbMetadataProviderAdapter receives a route whose targetIds[TMDB] value
 * carries an unrecognised prefix (e.g. "spotify:", "netflix-id-format") it MUST emit a
 * `metadata.identity_resolution` trace event with `reason = "unsupported_id_prefix"` and
 * `success = false`, rather than silently returning emptyCandidate.
 */
class MetadataAdapterUnknownPrefixTraceTest {

    @Test
    fun `unknown prefix triggers identity_resolution event with unsupported_id_prefix`() = runTest {
        val sink = RecordingTraceSink()
        val traceEvents = TraceMetadataEvents(sink, sessionId = { "test-session" })

        // TmdbMetadataProviderAdapter only needs integrationProvider (won't be reached for
        // unknown-prefix routes) and traceEvents. We use a relaxed mock for the provider.
        val adapter = TmdbMetadataProviderAdapter(
            integrationProvider = mockk(relaxed = true),
            traceEvents = traceEvents
        )

        // Route carries an unknown prefix ("spotify:") in targetIds[TMDB] —
        // MetadataProviderTargetIds.tmdbInt will return null, triggering the F2-C-07 path.
        val route = MetadataRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "spotify:abc123",
            mediaKind = MetadataMediaKind.MOVIE,
            reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
            sourceContext = MetadataSourceContext(),
            language = null,
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "spotify:abc123"),
            trace = emptyList()
        )
        val step = ProviderPlanStep(
            apiShapeId = TmdbApiShapes.MOVIE_CORE,
            provider = MetadataPrimaryProvider.TMDB,
            role = ProviderPlanRole.PRIMARY_CORE,
            required = true
        )

        val result = adapter.execute(route, step)

        // Behavior unchanged: result must be empty (no TMDB fields resolved)
        val candidate = result.candidate
        assertTrue(
            "expected empty candidate fields for unknown prefix, got ${candidate?.fields?.keys}",
            candidate == null || candidate.fields.isEmpty()
        )

        // F2-C-07 assertion: a metadata.identity_resolution event must have been emitted
        val identityEvents = sink.events.filter { it.eventType == "metadata.identity_resolution" }
        assertTrue(
            "expected at least one metadata.identity_resolution event for unknown prefix, " +
                "but none was emitted (all events: ${sink.events.map { it.eventType }})",
            identityEvents.isNotEmpty()
        )

        @Suppress("UNCHECKED_CAST")
        val payload = identityEvents.first().payload as Map<String, Any?>
        assertEquals("success should be false for unsupported prefix", false, payload["success"])
        assertEquals(
            "reason should be unsupported_id_prefix",
            "unsupported_id_prefix",
            payload["reason"]
        )
    }

    @Test
    fun `unknown prefix with no-colon unrecognised value also triggers observability event`() = runTest {
        val sink = RecordingTraceSink()
        val traceEvents = TraceMetadataEvents(sink, sessionId = { "test-session-2" })
        val adapter = TmdbMetadataProviderAdapter(
            integrationProvider = mockk(relaxed = true),
            traceEvents = traceEvents
        )

        // "netflix-id-format" has no colon — MetadataProviderTargetIds.tmdbInt treats the whole
        // string as a bare value (no prefix stripping) and toIntOrNull() will still return null
        // because it's not a numeric TMDB id.
        val route = MetadataRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "netflix-id-format",
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
            sourceContext = MetadataSourceContext(),
            language = null,
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "netflix-id-format"),
            trace = emptyList()
        )
        val step = ProviderPlanStep(
            apiShapeId = TmdbApiShapes.TV_CORE,
            provider = MetadataPrimaryProvider.TMDB,
            role = ProviderPlanRole.PRIMARY_CORE,
            required = true
        )

        adapter.execute(route, step)

        val identityEvents = sink.events.filter { it.eventType == "metadata.identity_resolution" }
        assertTrue(
            "expected identity_resolution event for non-numeric ID, " +
                "but none was emitted (all events: ${sink.events.map { it.eventType }})",
            identityEvents.isNotEmpty()
        )

        @Suppress("UNCHECKED_CAST")
        val payload = identityEvents.first().payload as Map<String, Any?>
        assertEquals(false, payload["success"])
        assertEquals("unsupported_id_prefix", payload["reason"])
    }
}
