package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityResolutionTraceTest {
    @Test
    fun `successful tmdb to tvdb lookup emits identity_resolution event with success true`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = MetadataIdentityResolver(
            lookup = object : MetadataIdentityResolver.Lookup {
                override suspend fun tmdbToTvdb(tmdbId: String): String? = "tvdb:399838"
                override suspend fun tvdbToTmdb(tvdbId: String): String? = null
            },
            traceEvents = events
        )

        val route = MetadataRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = "tmdb:1399",
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT,
            sourceContext = MetadataSourceContext(itemType = "series"),
            language = "en",
            seasonNumber = null,
            targetIds = mapOf(MetadataPrimaryProvider.TVDB to "tmdb:1399"),
            targetIdRequiresIdentityResolution = true,
            trace = emptyList()
        )

        resolver.resolve(route)

        val resolutions = sink.events.filter { it.eventType == "metadata.identity_resolution" }
        assertEquals(1, resolutions.size)
        val payload = resolutions.first().payload as Map<*, *>
        assertEquals("tmdb:1399", payload["sourceId"])
        assertEquals("TVDB", payload["targetProvider"])
        assertEquals(true, payload["success"])
        assertEquals("tvdb:399838", payload["resultId"])
    }

    @Test
    fun `failed lookup emits identity_resolution with success false`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = MetadataIdentityResolver(
            lookup = object : MetadataIdentityResolver.Lookup {
                override suspend fun tmdbToTvdb(tmdbId: String): String? = null
                override suspend fun tvdbToTmdb(tvdbId: String): String? = null
            },
            traceEvents = events
        )
        val route = MetadataRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = "tmdb:1399",
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.ROUTING_ID_TYPE_CONFLICT,
            sourceContext = MetadataSourceContext(itemType = "series"),
            language = "en",
            seasonNumber = null,
            targetIds = mapOf(MetadataPrimaryProvider.TVDB to "tmdb:1399"),
            targetIdRequiresIdentityResolution = true,
            trace = emptyList()
        )

        resolver.resolve(route)

        val resolutions = sink.events.filter { it.eventType == "metadata.identity_resolution" }
        assertEquals(1, resolutions.size)
        val payload = resolutions.first().payload as Map<*, *>
        assertEquals(false, payload["success"])
        assertTrue("resultId is null on miss", payload["resultId"] == null)
    }

    @Test
    fun `route that does not require resolution emits no event`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val resolver = MetadataIdentityResolver(
            lookup = object : MetadataIdentityResolver.Lookup {
                override suspend fun tmdbToTvdb(tmdbId: String): String? = "x"
                override suspend fun tvdbToTmdb(tvdbId: String): String? = "x"
            },
            traceEvents = events
        )
        val route = MetadataRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "tmdb:550",
            mediaKind = MetadataMediaKind.MOVIE,
            reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
            sourceContext = MetadataSourceContext(itemType = "movie"),
            language = "en",
            seasonNumber = null,
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:550"),
            targetIdRequiresIdentityResolution = false,
            trace = emptyList()
        )
        resolver.resolve(route)
        assertTrue(sink.events.isEmpty())
    }
}
