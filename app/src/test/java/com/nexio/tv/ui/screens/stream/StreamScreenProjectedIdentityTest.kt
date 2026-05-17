package com.nexio.tv.ui.screens.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamScreenProjectedIdentityTest {

    @Test
    fun `addon meta lookup uses projected imdb parent id when stream fetch id is projected`() {
        val metaId = projectedAddonMetaId(
            streamFetchVideoId = "tt42178219:2:2",
            contentId = "tvdb:413033",
            videoId = "tvdb:413033:2:2"
        )

        assertEquals("tt42178219", metaId)
    }

    @Test
    fun `addon meta video match prefers projected imdb episode id and keeps raw fallback`() {
        assertTrue(
            matchesProjectedAddonMetaVideo(
                candidateVideoId = "tt42178219:2:2",
                streamFetchVideoId = "tt42178219:2:2",
                videoId = "tvdb:413033:2:2"
            )
        )
        assertTrue(
            matchesProjectedAddonMetaVideo(
                candidateVideoId = "tvdb:413033:2:2",
                streamFetchVideoId = "tt42178219:2:2",
                videoId = "tvdb:413033:2:2"
            )
        )
        assertFalse(
            matchesProjectedAddonMetaVideo(
                candidateVideoId = "tvdb:413033:2:3",
                streamFetchVideoId = "tt42178219:2:2",
                videoId = "tvdb:413033:2:2"
            )
        )
    }

    @Test
    fun `stream fetch fallback tries playback imdb and raw video id after empty projected result`() {
        assertEquals(
            listOf("tt16288804:2:2", "tvdb:413033:2:2"),
            streamFetchFallbackVideoIds(
                streamFetchVideoId = "tt42178219:2:2",
                videoId = "tvdb:413033:2:2",
                imdbId = "tt16288804",
                season = 2,
                episode = 2
            )
        )
    }
}
