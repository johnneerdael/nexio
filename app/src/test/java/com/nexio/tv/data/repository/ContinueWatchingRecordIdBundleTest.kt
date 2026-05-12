package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingRecordIdBundleTest {

    @Test
    fun `record defaults to empty idBundle for back compat`() {
        val r = baseRecord()
        assertEquals(ContinueWatchingIdBundle(), r.idBundle)
    }

    @Test
    fun `record retains explicit idBundle`() {
        val bundle = ContinueWatchingIdBundle(imdb = "tt1", tmdb = "1")
        val r = baseRecord().copy(idBundle = bundle)
        assertEquals(bundle, r.idBundle)
    }

    private fun baseRecord() = ContinueWatchingRecord(
        profileId = 1,
        parentId = "tt0903747",
        contentId = "tt0903747",
        provider = TrackingProvider.TRAKT,
        routingVersion = 1,
        positionMs = 1000L,
        durationMs = 60_000L,
        episodeContext = null,
        clickTimeDisplayMetadata = null,
        source = ContinueWatchingRecord.Source.LOCAL,
        updatedAt = 1000L,
    )
}
