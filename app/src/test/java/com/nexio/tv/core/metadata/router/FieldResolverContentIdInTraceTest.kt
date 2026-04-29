package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-B-05 pin: metadata.field_selected event payload contentId MUST be the actual request
 * contentId (e.g. "tmdb:550"), not the provider name (e.g. "TMDB").
 *
 * The test passes a `requestContentId` argument to FieldResolver.resolveWithPreview(...)
 * and asserts that emitted field_selected events carry it as `payload["contentId"]`.
 */
class FieldResolverContentIdInTraceTest {

    @Test
    fun `field_selected event carries request contentId, not provider name`() {
        val sink = RecordingTraceSink()
        val traceEvents = TraceMetadataEvents(sink, sessionId = { "test-session" })
        val resolver = FieldResolver(traceEvents = traceEvents)

        val previewCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue(value = "Fight Club", owner = FieldOwner.PRIMARY)
            ),
            sourceProvider = "stremio.cinemeta",
            sourceRole = SourceRole.ADDON_PREVIEW
        )

        resolver.resolveWithPreview(
            preview = previewCandidate,
            primary = null,
            secondary = emptyList(),
            requestContentId = "tmdb:550"
        )

        val fieldSelectedEvents = sink.events.filter { it.eventType == "metadata.field_selected" }
        assertTrue("at least one field_selected event emitted", fieldSelectedEvents.isNotEmpty())
        fieldSelectedEvents.forEach { ev ->
            val contentId = (ev.payload as Map<*, *>)["contentId"] as? String
            assertEquals(
                "F-B-05: contentId must be request id, not provider name (got $contentId)",
                "tmdb:550",
                contentId
            )
        }
    }
}
