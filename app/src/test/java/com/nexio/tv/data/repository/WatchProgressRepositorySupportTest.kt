package com.nexio.tv.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressRepositorySupportTest {

    @Test
    fun `shouldHydrateRemoteWatchedSeriesSeeds waits for both remote snapshots`() {
        assertFalse(
            shouldHydrateRemoteWatchedSeriesSeeds(
                remoteProgressLoaded = false,
                remoteWatchedSeriesSeedsLoaded = false
            )
        )
        assertFalse(
            shouldHydrateRemoteWatchedSeriesSeeds(
                remoteProgressLoaded = true,
                remoteWatchedSeriesSeedsLoaded = false
            )
        )
        assertFalse(
            shouldHydrateRemoteWatchedSeriesSeeds(
                remoteProgressLoaded = false,
                remoteWatchedSeriesSeedsLoaded = true
            )
        )
        assertTrue(
            shouldHydrateRemoteWatchedSeriesSeeds(
                remoteProgressLoaded = true,
                remoteWatchedSeriesSeedsLoaded = true
            )
        )
    }
}
