package com.nexio.tv.core.metadata.router.resolver

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerResolverPlaybackTest {
    @Test
    fun `selects typed youtube ref from fallback ids without constructing watch url`() {
        val resolver = TrailerResolver(
            TraceMetadataEvents(RecordingTraceSink(), sessionId = { "s1" })
        )

        val resolution = resolver.resolveTrailer(
            TrailerResolveRequest(
                itemKey = "movie:tmdb:550",
                title = "Fight Club",
                year = "1999",
                stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523"),
                fallbackYtIds = listOf("  SUXWAEX2jlg  "),
                surface = TrailerSurface.DETAIL,
                type = "movie",
                contentId = "tmdb:550"
            )
        )

        assertTrue(resolution.availability.available)
        assertEquals("fallback_youtube_id", resolution.availability.reason)
        assertEquals(
            TrailerPlaybackRef.YouTubeId("SUXWAEX2jlg"),
            resolution.selected
        )
        assertEquals(
            listOf(TrailerPlaybackRef.YouTubeId("SUXWAEX2jlg")),
            resolution.candidates
        )
        assertFalse(resolution.toString().contains("youtube.com/watch"))
        assertFalse(resolution.toString().contains("youtu.be"))
    }
}
