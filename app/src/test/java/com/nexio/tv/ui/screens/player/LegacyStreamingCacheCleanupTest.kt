package com.nexio.tv.ui.screens.player

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyStreamingCacheCleanupTest {

    @Test
    fun cleanupDeletesLegacyPlayerVodCacheDirectory() {
        val root = Files.createTempDirectory("legacy-cache-cleanup").toFile()
        val legacy = java.io.File(root, "player_vod_cache_v2").apply {
            mkdirs()
            resolve("legacy.exo").writeText("legacy")
        }

        LegacyStreamingCacheCleanup.cleanup(root)

        assertFalse(legacy.exists())
    }

    @Test
    fun cleanupLeavesCurrentStreamCacheDirectory() {
        val root = Files.createTempDirectory("legacy-cache-cleanup-current").toFile()
        val current = java.io.File(root, "stream-cache").apply {
            mkdirs()
            resolve("current.exo").writeText("current")
        }

        LegacyStreamingCacheCleanup.cleanup(root)

        assertTrue(current.exists())
        assertTrue(current.resolve("current.exo").exists())
    }
}
