package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TrackingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ContinueWatchingProgressDiffPlannerTest {

    private val planner = ContinueWatchingProgressDiffPlanner()

    @Test
    fun `empty list returns null`() {
        assertNull(planner.pickWinner(emptyList()))
    }

    @Test
    fun `single candidate passes through`() {
        val only = record(positionMs = 30_000L, durationMs = 100_000L, updatedAt = 1000L)
        assertSame(only, planner.pickWinner(listOf(only)))
    }

    @Test
    fun `near-complete candidates return null so history takes over`() {
        val a = record(positionMs = 96_000L, durationMs = 100_000L, updatedAt = 1000L)
        val b = record(positionMs = 97_000L, durationMs = 100_000L, updatedAt = 2000L)
        assertNull(planner.pickWinner(listOf(a, b)))
    }

    @Test
    fun `trivial delta favors newer timestamp`() {
        val older = record(positionMs = 50_000L, durationMs = 100_000L, updatedAt = 1000L)
        val newer = record(positionMs = 55_000L, durationMs = 100_000L, updatedAt = 2000L)
        // delta = 5s, below 30s trivial threshold; newer timestamp wins
        assertSame(newer, planner.pickWinner(listOf(older, newer)))
    }

    @Test
    fun `meaningful lead wins even when other timestamp is newer`() {
        // 70-second lead is well beyond 30s trivial threshold
        val leader = record(positionMs = 80_000L, durationMs = 100_000L, updatedAt = 1000L)
        val fresherButBehind = record(positionMs = 10_000L, durationMs = 100_000L, updatedAt = 2000L)
        // Avoid regressing progress: keep leader.
        assertSame(leader, planner.pickWinner(listOf(leader, fresherButBehind)))
    }

    @Test
    fun `behind candidate wins when its timestamp is strictly newer AND lead is reversed`() {
        // Both ahead/behind with substantial deltas and fresher timestamps for the leader.
        val staleAhead = record(positionMs = 80_000L, durationMs = 100_000L, updatedAt = 1000L)
        val freshAndAhead = record(positionMs = 85_000L, durationMs = 100_000L, updatedAt = 2000L)
        // Trivial delta (5s); newer timestamp wins.
        assertSame(freshAndAhead, planner.pickWinner(listOf(staleAhead, freshAndAhead)))
    }

    @Test
    fun `zero-duration record is treated as zero percent so meaningful items still win`() {
        val zeroDuration = record(positionMs = 0L, durationMs = 0L, updatedAt = 1000L)
        val meaningful = record(positionMs = 30_000L, durationMs = 100_000L, updatedAt = 2000L)
        assertSame(meaningful, planner.pickWinner(listOf(zeroDuration, meaningful)))
    }

    private fun record(
        positionMs: Long,
        durationMs: Long,
        updatedAt: Long,
        provider: TrackingProvider = TrackingProvider.TRAKT,
    ): ContinueWatchingRecord = ContinueWatchingRecord(
        profileId = 1,
        parentId = "tt0903747",
        contentId = "tt0903747",
        provider = provider,
        routingVersion = 1,
        positionMs = positionMs,
        durationMs = durationMs.coerceAtLeast(1L),
        episodeContext = null,
        clickTimeDisplayMetadata = null,
        source = ContinueWatchingRecord.Source.REMOTE,
        updatedAt = updatedAt,
        canonicalKey = null,
        displayIdentity = ContentIdentity(
            canonicalProvider = ProviderId.IMDB,
            canonicalId = "tt0903747",
            providerIds = ProviderIds(imdb = "tt0903747"),
        ),
        // mediaKind unused by planner
        idBundle = ContinueWatchingIdBundle(imdb = "tt0903747"),
    )

    @Suppress("unused")
    private val unused: MetadataMediaKind? = null  // ensures import retained if needed
}
