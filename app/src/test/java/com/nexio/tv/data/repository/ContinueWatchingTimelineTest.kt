package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingTimelineTest {

    @Test
    fun `mixed timeline interleaves resumes and next up by activity`() {
        val selection = splitNextUpCandidatesForContinueWatching(
            resumes = listOf(resumeRef("show-a", 1_000L, suppressNextUp = true)),
            nextUpItems = listOf(nextUp("show-b", activityAtMs = 900L, firstAiredMs = 850L)),
            nextUpRef = ::nextUpRef,
            nowMs = 2_000L
        )

        val timeline = buildMixedContinueWatchingTimeline(
            resumeItems = listOf(
                resume(contentId = "show-a", season = 1, episode = 2, lastWatched = 1_000L),
                resume(contentId = "movie-a", lastWatched = 800L)
            ),
            nextUpItems = selection.mainFeedItems,
            resumeRef = ::resumeRef,
            nextUpRef = ::nextUpRef
        )

        assertEquals(
            listOf("show-a", "show-b", "movie-a"),
            timeline.map {
                when (it) {
                    is ContinueWatchingTimelineRow.Resume -> it.value.contentId
                    is ContinueWatchingTimelineRow.NextUp -> it.value.contentId
                }
            }
        )
    }

    @Test
    fun `resume wins when activity is near-equal`() {
        val timeline = buildMixedContinueWatchingTimeline(
            resumeItems = listOf(resume(contentId = "resume-show", lastWatched = 1_000L)),
            nextUpItems = listOf(nextUp(contentId = "next-show", activityAtMs = 1_030L, firstAiredMs = 900L)),
            resumeRef = ::resumeRef,
            nextUpRef = ::nextUpRef,
            nearEqualWindowMs = 60_000L
        )

        val first = timeline.first() as ContinueWatchingTimelineRow.Resume
        assertEquals("resume-show", first.value.contentId)
    }

    @Test
    fun `paused episode suppresses same show next up from main feed`() {
        val selection = splitNextUpCandidatesForContinueWatching(
            resumes = listOf(resumeRef("show-a", 90_000L, suppressNextUp = true)),
            nextUpItems = listOf(
                nextUp(contentId = "show-a", season = 2, episode = 4, activityAtMs = 100_000L, firstAiredMs = 900L),
                nextUp(contentId = "show-b", activityAtMs = 800L, firstAiredMs = 700L)
            ),
            nextUpRef = ::nextUpRef,
            nowMs = 2_000L
        )

        assertEquals(listOf("show-b"), selection.mainFeedItems.map { it.contentId })
        assertEquals(listOf("show-a", "show-b"), selection.syntheticRailItems.map { it.contentId })
    }

    @Test
    fun `provider next up beats stale same-show local resume`() {
        val selection = splitNextUpCandidatesForContinueWatching(
            resumes = listOf(resumeRef("show-a", 1_000L, suppressNextUp = true)),
            nextUpItems = listOf(
                nextUp(contentId = "show-a", season = 5, episode = 3, activityAtMs = 200_000L, firstAiredMs = 900L)
            ),
            nextUpRef = ::nextUpRef,
            nowMs = 300_000L
        )

        assertEquals(listOf("show-a"), selection.mainFeedItems.map { it.contentId })
    }

    @Test
    fun `mixed timeline keeps provider next up instead of stale same-show resume`() {
        val timeline = buildMixedContinueWatchingTimeline(
            resumeItems = listOf(
                resume(contentId = "show-a", season = 1, episode = 2, lastWatched = 1_000L)
            ),
            nextUpItems = listOf(
                nextUp(contentId = "show-a", season = 5, episode = 3, activityAtMs = 200_000L, firstAiredMs = 900L)
            ),
            resumeRef = ::resumeRef,
            nextUpRef = ::nextUpRef
        )

        assertEquals(1, timeline.size)
        val first = timeline.single() as ContinueWatchingTimelineRow.NextUp
        assertEquals(5, first.value.season)
        assertEquals(3, first.value.episode)
    }

    @Test
    fun `unaired next up is excluded from main feed but retained for synthetic rail`() {
        val futureEntry = nextUp(
            contentId = "future-show",
            activityAtMs = 900L,
            firstAiredMs = 5_000L
        )

        val selection = splitNextUpCandidatesForContinueWatching(
            resumes = emptyList(),
            nextUpItems = listOf(futureEntry),
            nextUpRef = ::nextUpRef,
            nowMs = 4_000L
        )

        assertTrue(selection.mainFeedItems.isEmpty())
        assertEquals(listOf("future-show"), selection.syntheticRailItems.map { it.contentId })
    }

    @Test
    fun `main feed includes unaired item once air date passes`() {
        val selection = splitNextUpCandidatesForContinueWatching(
            resumes = emptyList(),
            nextUpItems = listOf(nextUp(contentId = "show-a", activityAtMs = 900L, firstAiredMs = 5_000L)),
            nextUpRef = ::nextUpRef,
            nowMs = 5_000L
        )

        assertEquals(listOf("show-a"), selection.mainFeedItems.map { it.contentId })
    }

    private fun resume(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        lastWatched: Long
    ): WatchProgress {
        return WatchProgress(
            contentId = contentId,
            contentType = if (season != null && episode != null) "series" else "movie",
            name = contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = if (season != null && episode != null) "$contentId:$season:$episode" else contentId,
            season = season,
            episode = episode,
            episodeTitle = null,
            position = 50L,
            duration = 100L,
            lastWatched = lastWatched,
            progressPercent = 50f
        )
    }

    private fun resumeRef(progress: WatchProgress): ContinueWatchingResumeRef {
        return ContinueWatchingResumeRef(
            contentId = progress.contentId,
            activityAtMs = progress.lastWatched,
            suppressNextUp = progress.season != null && progress.episode != null
        )
    }

    private fun resumeRef(
        contentId: String,
        activityAtMs: Long,
        suppressNextUp: Boolean
    ): ContinueWatchingResumeRef {
        return ContinueWatchingResumeRef(
            contentId = contentId,
            activityAtMs = activityAtMs,
            suppressNextUp = suppressNextUp
        )
    }

    private fun nextUp(
        contentId: String,
        season: Int = 1,
        episode: Int = 1,
        activityAtMs: Long,
        firstAiredMs: Long
    ): TrackingNextUpEntry {
        return TrackingNextUpEntry(
            contentId = contentId,
            name = contentId,
            season = season,
            episode = episode,
            episodeTitle = "Episode $episode",
            videoId = "$contentId:$season:$episode",
            firstAired = "2026-03-23T00:00:00.000Z",
            firstAiredMs = firstAiredMs,
            activityAtMs = activityAtMs
        )
    }

    private fun nextUpRef(entry: TrackingNextUpEntry): ContinueWatchingNextUpRef {
        return ContinueWatchingNextUpRef(
            contentId = entry.contentId,
            activityAtMs = entry.activityAtMs,
            firstAiredMs = entry.firstAiredMs
        )
    }
}
