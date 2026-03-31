package com.nexio.tv.ui.screens.detail

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MetaDetailsProgressSupportTest {

    @Test
    fun `resolveSeriesEpisodeProgressIds includes route meta and remembered aliases`() {
        val ids = resolveSeriesEpisodeProgressIds(
            routeItemId = "tmdb:101",
            metaId = "tt1234567",
            rememberedRouteIds = setOf("trakt:42"),
            rememberedMetaIds = setOf("tmdb:101", "tt1234567")
        )

        assertEquals(
            setOf("tmdb:101", "tt1234567", "trakt:42"),
            ids
        )
    }

    @Test
    fun `LatestSeriesWatchedBadgeScheduler keeps the newest write last`() = runTest {
        val writes = mutableListOf<Boolean>()
        val scheduler = LatestSeriesWatchedBadgeScheduler(this) { _, watched ->
            if (!watched) delay(25)
            writes += watched
        }

        scheduler.submit(ids = setOf("tmdb:101"), watched = false)
        scheduler.submit(ids = setOf("tmdb:101", "tt1234567"), watched = true)
        advanceUntilIdle()

        assertEquals(listOf(false, true), writes)
    }
}
