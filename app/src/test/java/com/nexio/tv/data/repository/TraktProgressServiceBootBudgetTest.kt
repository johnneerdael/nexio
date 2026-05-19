package com.nexio.tv.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TraktProgressServiceBootBudgetTest {
    private val source = File("app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt").readText()

    @Test
    fun `trakt boot critical progress does not fetch episode history pages`() {
        assertTrue(source.contains("TraktProgressRefreshMode.BOOT_CRITICAL"))
        assertTrue(source.contains("if (mode == TraktProgressRefreshMode.BOOT_CRITICAL)"))
        assertTrue(source.contains("emptyList()"))
        assertTrue(source.contains("fetchRecentEpisodeHistorySnapshot()"))
    }

    @Test
    fun `trakt boot critical progress does not force per show watched progress validation`() {
        assertTrue(source.contains("forceValidation = activityChanged && refreshMode != TraktProgressRefreshMode.BOOT_CRITICAL"))
    }
}
