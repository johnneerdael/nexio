package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeCoordinateTest {

    @Test
    fun `keys equal when provider seriesId season and episode match regardless of absoluteNumber`() {
        val a = EpisodeCoordinate(ProviderId.TVDB, "305074", 3, 1, absoluteNumber = 39)
        val b = EpisodeCoordinate(ProviderId.TVDB, "305074", 3, 1, absoluteNumber = null)
        assertEquals(a.identityKey, b.identityKey)
    }

    @Test
    fun `identityKey distinguishes provider`() {
        val tvdb = EpisodeCoordinate(ProviderId.TVDB, "305074", 3, 1)
        val kitsu = EpisodeCoordinate(ProviderId.KITSU, "13881", 3, 1)
        assert(tvdb.identityKey != kitsu.identityKey)
    }
}
