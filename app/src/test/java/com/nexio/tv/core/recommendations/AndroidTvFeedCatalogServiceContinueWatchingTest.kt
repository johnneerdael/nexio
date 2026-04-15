package com.nexio.tv.core.recommendations

import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import com.nexio.tv.data.repository.TrackingNextUpEntry
import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidTvFeedCatalogServiceContinueWatchingTest {

    @Test
    fun `continue watching feed does not include scheduled reemit rows`() {
        val visibleResume = WatchProgress(
            contentId = "resume-show",
            contentType = "series",
            name = "Resume Show",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "resume-show:1:1",
            season = 1,
            episode = 1,
            episodeTitle = "Episode 1",
            position = 50L,
            duration = 100L,
            lastWatched = 1_000L,
            progressPercent = 50f
        )
        val visibleNextUp = nextUp(
            contentId = "visible-next-up",
            episode = 2,
            activityAtMs = 900L
        )
        val withheldNextUp = nextUp(
            contentId = "withheld-next-up",
            episode = 3,
            activityAtMs = 800L,
            availabilityInstantMs = 20_000L
        )

        val items = buildContinueWatchingItemsForAndroidTvFeed(
            ContinueWatchingSnapshot(
                resumeItems = listOf(visibleResume),
                nextUpItems = listOf(visibleNextUp),
                scheduledReemit = listOf(withheldNextUp),
                updatedAtMs = 1_000L
            )
        )

        assertEquals(
            listOf("resume-show", "visible-next-up"),
            items.map { it.id }
        )
    }

    private fun nextUp(
        contentId: String,
        episode: Int,
        activityAtMs: Long,
        availabilityInstantMs: Long? = null
    ): TrackingNextUpEntry {
        return TrackingNextUpEntry(
            contentId = contentId,
            name = contentId,
            season = 1,
            episode = episode,
            episodeTitle = "Episode $episode",
            videoId = "$contentId:1:$episode",
            firstAired = null,
            firstAiredMs = 1_000L,
            activityAtMs = activityAtMs,
            tvdbAvailabilityInstantMs = availabilityInstantMs
        )
    }
}
