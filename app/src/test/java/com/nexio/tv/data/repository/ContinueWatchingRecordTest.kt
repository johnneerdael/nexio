package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContinueWatchingRecordTest {
    @Test
    fun `identity key includes profile parent and episode key`() {
        val record = ContinueWatchingRecord(
            profileId = 2,
            parentId = "tt1234",
            contentId = "tt1234:s1e3",
            provider = TrackingProvider.TRAKT,
            routingVersion = 4,
            positionMs = 1000L,
            durationMs = 5000L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(season = 1, number = 3),
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.LOCAL,
            updatedAt = 1_700_000_000_000L
        )
        assertEquals("profile:2:continue-watching:tt1234:s1e3", record.identityKey())
    }

    @Test
    fun `identity key falls back to parentId when episode context is null`() {
        val record = ContinueWatchingRecord(
            profileId = 1,
            parentId = "tt9999",
            contentId = "tt9999",
            provider = TrackingProvider.SIMKL,
            routingVersion = 1,
            positionMs = 100L,
            durationMs = 1000L,
            episodeContext = null,
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.SYNTHETIC,
            updatedAt = 1L
        )
        assertEquals("profile:1:continue-watching:tt9999", record.identityKey())
    }

    @Test
    fun `rejects non-positive profileId`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContinueWatchingRecord(
                profileId = 0,
                parentId = "x",
                contentId = "x",
                provider = TrackingProvider.TRAKT,
                routingVersion = 1,
                positionMs = 0L,
                durationMs = 1L,
                episodeContext = null,
                clickTimeDisplayMetadata = null,
                source = ContinueWatchingRecord.Source.LOCAL,
                updatedAt = 1L
            )
        }
    }
}
