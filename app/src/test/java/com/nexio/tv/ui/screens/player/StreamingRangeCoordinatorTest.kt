package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StreamingRangeCoordinatorTest {

    @Test
    fun fallbackRange_overlapsContainedFillRange() {
        val coordinator = StreamingRangeCoordinator()
        val token = coordinator.markFallbackOwned(start = 100, endExclusive = 200)

        assertTrue(coordinator.isOwnedByPlaybackFallback(start = 120, endExclusive = 180))

        coordinator.clearFallbackOwnership(token)
        assertFalse(coordinator.isOwnedByPlaybackFallback(start = 120, endExclusive = 180))
    }

    @Test
    fun fallbackRange_overlapsFillRangeStartingBeforeFallback() {
        val coordinator = StreamingRangeCoordinator()
        coordinator.markFallbackOwned(start = 100, endExclusive = 200)

        assertTrue(coordinator.isOwnedByPlaybackFallback(start = 50, endExclusive = 150))
    }

    @Test
    fun adjacentRanges_doNotOverlap() {
        val coordinator = StreamingRangeCoordinator()
        coordinator.markFallbackOwned(start = 100, endExclusive = 200)

        assertFalse(coordinator.isOwnedByPlaybackFallback(start = 0, endExclusive = 100))
        assertFalse(coordinator.isOwnedByPlaybackFallback(start = 200, endExclusive = 300))
    }

    @Test
    fun earlierLongFallbackRange_overlapsAfterLaterShortFallbackRange() {
        val coordinator = StreamingRangeCoordinator()
        coordinator.markFallbackOwned(start = 0, endExclusive = 1000)
        coordinator.markFallbackOwned(start = 500, endExclusive = 600)

        assertTrue(coordinator.isOwnedByPlaybackFallback(start = 800, endExclusive = 900))
    }

    @Test
    fun playbackFallbackTrackingDataSource_clearsOwnershipWhenOpenFails() {
        val coordinator = StreamingRangeCoordinator()
        val upstream = mockk<DataSource>()
        every { upstream.open(any()) } throws IllegalStateException("boom")
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse("https://example.com/video.mkv"))
            .setPosition(100L)
            .setLength(50L)
            .build()
        val trackingDataSource = PlaybackFallbackTrackingDataSource(upstream, coordinator)

        try {
            trackingDataSource.open(dataSpec)
            throw AssertionError("expected open to throw")
        } catch (_: IllegalStateException) {
        }

        assertFalse(coordinator.isOwnedByPlaybackFallback(start = 100, endExclusive = 150))
    }

    @Test
    fun playbackFallbackTrackingDataSource_ownsOpenEndedUnsetLengthRange() {
        val coordinator = StreamingRangeCoordinator()
        val upstream = mockk<DataSource>()
        every { upstream.open(any()) } returns C.LENGTH_UNSET.toLong()
        every { upstream.close() } just runs
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse("https://example.com/video.mkv"))
            .setPosition(100L)
            .setLength(C.LENGTH_UNSET.toLong())
            .build()
        val trackingDataSource = PlaybackFallbackTrackingDataSource(upstream, coordinator)

        trackingDataSource.open(dataSpec)

        val farFutureStart = 100L + 64L * 1024L * 1024L
        assertTrue(
            coordinator.isOwnedByPlaybackFallback(
                start = farFutureStart,
                endExclusive = farFutureStart + 1L * 1024L * 1024L
            )
        )

        trackingDataSource.close()
    }
}
