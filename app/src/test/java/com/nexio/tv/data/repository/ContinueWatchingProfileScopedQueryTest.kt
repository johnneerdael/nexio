package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingProfileScopedQueryTest {

    @Test
    fun `mapping yields one record per resume item with profile id`() {
        val snapshot = ContinueWatchingSnapshot(
            resumeItems = listOf(
                makeWatchProgress(contentId = "tt100", season = 1, episode = 2)
            )
        )
        val owned = ProfileOwnedContinueWatchingSnapshot(profileId = 2, snapshot = snapshot)

        val records = owned.toContinueWatchingRecords()

        assertEquals(1, records.size)
        val first = records.first()
        assertEquals(2, first.profileId)
        assertEquals("tt100", first.parentId)
        assertEquals(ContinueWatchingRecord.Source.LOCAL, first.source)
        assertEquals(1, first.episodeContext?.season)
        assertEquals(2, first.episodeContext?.number)
    }

    @Test
    fun `mapping yields empty list for empty snapshot`() {
        val owned = ProfileOwnedContinueWatchingSnapshot(profileId = 1)
        assertTrue(owned.toContinueWatchingRecords().isEmpty())
    }

    @Test
    fun `mapping rejects non-positive profileId via record init`() {
        val snapshot = ContinueWatchingSnapshot(
            resumeItems = listOf(makeWatchProgress("tt1", null, null))
        )
        val owned = ProfileOwnedContinueWatchingSnapshot(profileId = 0, snapshot = snapshot)
        try {
            owned.toContinueWatchingRecords()
            assertTrue("expected IllegalArgumentException for profileId=0", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("profileId") == true)
        }
    }

    private fun makeWatchProgress(
        contentId: String,
        season: Int?,
        episode: Int?
    ): WatchProgress {
        val videoId = if (season != null && episode != null) {
            "$contentId:s${season}e${episode}"
        } else {
            contentId
        }
        return WatchProgress(
            contentId = contentId,
            contentType = if (season != null && episode != null) "series" else "movie",
            name = contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = null,
            position = 1000L,
            duration = 5000L,
            lastWatched = 42L
        )
    }
}
