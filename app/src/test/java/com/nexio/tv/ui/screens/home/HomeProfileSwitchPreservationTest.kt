package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.posterSlot
import com.nexio.tv.ui.screens.home.testutil.HomeReducerTestFixtures.slotsWith
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

class HomeProfileSwitchPreservationTest {
    @Test
    fun `profile_switch_does_not_clear_shared_resolved_artwork`() {
        // After a profile switch, the rail re-emits as FIRST_PAINT. Any RESOLVED
        // overlay (already shared across profiles in the cache layer) must beat
        // the new FIRST_PAINT. The reducer enforces this via the non-downgrade rule.
        val freshFirstPaint = slotsWith(poster = posterSlot(DisplaySourceRank.FIRST_PAINT, "https://addon/raw.jpg", "TRAKT"))
        val sharedResolved = slotsWith(poster = posterSlot(DisplaySourceRank.RESOLVED, "nexio-artwork://decision/shared", "TVDB"))
        val merged = HomeRailProjectionReducer.reduce(freshFirstPaint, overlay = sharedResolved, existing = null, profile = null)
        assertEquals(DisplaySourceRank.RESOLVED, merged.poster.rank)
        assertEquals("TVDB", merged.poster.provider)
    }

    @Ignore("covered by MetadataDiskCacheStoreTest — cache freshness is a layer below the reducer")
    @Test
    fun `profile_switch_does_not_refetch_metadata_when_cache_fresh`() {
    }

    @Ignore("covered by ContinueWatchingSnapshotStoreTest — CW boundary lives in the snapshot store, not the reducer")
    @Test
    fun `profile2_does_not_see_profile1_continue_watching`() {
    }
}
