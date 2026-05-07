package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvdbMetadataProviderAdapterEpisodeFieldSelectedTraceTest {

    @Test
    fun `localized episode bundle emits one field_selected per (episode, field) pair`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val provider = mockk<TvdbIntegrationProvider>()

        val bundle = sampleBundle(seriesId = 73244, episodeCount = 3)
        coEvery {
            provider.fetchLocalizedSeasonEpisodeBundle(any(), any(), any(), any())
        } returns bundle

        val adapter = TvdbMetadataProviderAdapter(
            integrationProvider = provider,
            traceEvents = events,
            artworkCandidateMapper = TvdbArtworkCandidateMapper(),
            artworkDecisionResolver = mockk(relaxed = true)
        )

        val route = sampleTvdbRoute(tvdbId = 73244, language = "nld")
        val step = ProviderPlanStep(
            apiShapeId = TvdbApiShapes.SERIES_EPISODES_LANGUAGE,
            provider = MetadataPrimaryProvider.TVDB,
            role = ProviderPlanRole.SECONDARY,
            required = false
        )

        adapter.execute(route, step)

        val fieldSelected = sink.events.filter { it.eventType == "metadata.field_selected" }
        // Expect 6 events: 3 episodes × 2 fields (title + overview)
        assertEquals(
            "expected 6 episode field_selected events, got ${fieldSelected.size} " +
                "(event types: ${sink.events.map { it.eventType }})",
            6,
            fieldSelected.size
        )
        val contentIds = fieldSelected.map { (it.payload as Map<*, *>)["contentId"] as String }
        assertTrue(
            "all contentIds should match tvdb:<id>:s<season>e<episode>, got $contentIds",
            contentIds.all { it.matches(Regex("tvdb:\\d+:s\\d+e\\d+")) }
        )
    }
}

private fun sampleBundle(seriesId: Int, episodeCount: Int): LocalizedEpisodeBundle {
    val policy = LocalizationPolicy.tvdb("nld")
    val episodes = (1..episodeCount).associate { ep ->
        Pair(1, ep) to LocalizedEpisodeMetadata(
            metadata = TvEpisodeMetadata(
                providerEpisodeId = "tvdb:$seriesId-s1e$ep",
                seasonNumber = 1,
                episodeNumber = ep,
                airDate = null,
                title = "Episode $ep",
                overview = "Overview for episode $ep",
                runtimeMinutes = null
            ),
            fieldSources = mapOf(
                "title" to LocalizedEpisodeFieldSource(
                    selectedLanguage = NormalizedLanguage("nld", "nld"),
                    sourceShape = "tvdb.episode.translation",
                    fallbackRole = FallbackRole.LOCALIZED,
                    rejectedCandidates = emptyList()
                ),
                "overview" to LocalizedEpisodeFieldSource(
                    selectedLanguage = NormalizedLanguage("eng", "eng"),
                    sourceShape = "tvdb.episode.translation",
                    fallbackRole = FallbackRole.LANGUAGE_FALLBACK,
                    rejectedCandidates = emptyList()
                )
            )
        )
    }
    return LocalizedEpisodeBundle(
        episodes = episodes,
        policy = policy,
        perEpisodeTranslationFallbacksAttempted = 0,
        maxPerEpisodeTranslationFallbacksAllowed = 8
    )
}

private fun sampleTvdbRoute(tvdbId: Int, language: String): MetadataRoute = MetadataRoute(
    provider = MetadataPrimaryProvider.TVDB,
    parentId = "tvdb:$tvdbId",
    mediaKind = MetadataMediaKind.SERIES,
    reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
    sourceContext = MetadataSourceContext(),
    language = language,
    seasonNumber = 1,
    targetIds = mapOf(MetadataPrimaryProvider.TVDB to tvdbId.toString()),
    trace = emptyList()
)
