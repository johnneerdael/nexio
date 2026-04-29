package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-B-07 pin: when MetadataRequestNormalizer coerces ContentType.TV → MediaKind.SERIES,
 * it MUST emit a metadata.normalizer_warning trace event with reason
 * "TV_TYPE_COERCED_TO_SERIES" and the request's contentId. Other ContentType mappings
 * remain silent.
 */
class MetadataRequestNormalizerTvWarningTest {

    private fun newSink(): Pair<RuntimeTraceSink, MutableList<TraceEventEnvelope<*>>> {
        val emitted = mutableListOf<TraceEventEnvelope<*>>()
        val sink = mockk<RuntimeTraceSink>(relaxed = true)
        every { sink.emit(any()) } answers { emitted += firstArg<TraceEventEnvelope<*>>() }
        return sink to emitted
    }

    @Test
    fun `TV ContentType emits normalizer_warning trace event`() {
        val (sink, emitted) = newSink()
        val traceEvents = TraceMetadataEvents(sink) { "test-session" }
        val normalizer = MetadataRequestNormalizer(traceEvents = traceEvents)

        val req = MetadataRequest(
            contentId = "tt0944947",
            contentType = ContentType.TV,
            depth = MetadataDepth.PREVIEW,
            sourceContext = MetadataSourceContext(),
            language = null
        )

        val normalized = normalizer.normalize(req)

        // Mapping still produces SERIES — we don't break the coercion, just warn
        assertEquals(MetadataMediaKind.SERIES, normalized.mediaKind)

        val warnings = emitted.filter { it.eventType == "metadata.normalizer_warning" }
        assertTrue("expected at least one normalizer_warning event", warnings.isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val payload = warnings.first().payload as Map<String, Any?>
        assertEquals("TV_TYPE_COERCED_TO_SERIES", payload["reason"])
        assertEquals("tt0944947", payload["contentId"])
    }

    @Test
    fun `MOVIE ContentType emits no warning`() {
        val (sink, emitted) = newSink()
        val traceEvents = TraceMetadataEvents(sink) { "test-session" }
        val normalizer = MetadataRequestNormalizer(traceEvents = traceEvents)

        val req = MetadataRequest(
            contentId = "tt0111161",
            contentType = ContentType.MOVIE,
            depth = MetadataDepth.PREVIEW,
            sourceContext = MetadataSourceContext(),
            language = null
        )
        normalizer.normalize(req)

        val warnings = emitted.filter { it.eventType == "metadata.normalizer_warning" }
        assertTrue("MOVIE must not emit a warning", warnings.isEmpty())
    }

    @Test
    fun `SERIES ContentType emits no warning`() {
        val (sink, emitted) = newSink()
        val traceEvents = TraceMetadataEvents(sink) { "test-session" }
        val normalizer = MetadataRequestNormalizer(traceEvents = traceEvents)

        val req = MetadataRequest(
            contentId = "tt0944947",
            contentType = ContentType.SERIES,
            depth = MetadataDepth.PREVIEW,
            sourceContext = MetadataSourceContext(),
            language = null
        )
        normalizer.normalize(req)

        val warnings = emitted.filter { it.eventType == "metadata.normalizer_warning" }
        assertTrue("SERIES (already correct) must not emit a warning", warnings.isEmpty())
    }
}
