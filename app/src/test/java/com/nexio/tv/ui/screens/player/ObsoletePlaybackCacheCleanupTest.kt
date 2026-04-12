package com.nexio.tv.ui.screens.player

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObsoletePlaybackCacheCleanupTest {

    @Test
    fun cleanup_removesStreamingCacheAndVodCacheV2() {
        val root = createTempDirectory("obsolete-playback-cache").toFile()
        val streamCache = File(root, "stream-cache").apply {
            mkdirs()
            File(this, "cached.exo").writeText("old-stream-cache")
        }
        val vodCacheV2 = File(root, "player_vod_cache_v2").apply {
            mkdirs()
            File(this, "cached.exo").writeText("old-v2-cache")
        }

        try {
            ObsoletePlaybackCacheCleanup.cleanup(root)

            assertFalse(streamCache.exists())
            assertFalse(vodCacheV2.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanup_preservesRestoredVodCacheDirectory() {
        val root = createTempDirectory("obsolete-playback-cache").toFile()
        val restoredVodCache = File(root, "player_vod_cache").apply {
            mkdirs()
            File(this, "cached.exo").writeText("stable-cache")
        }

        try {
            ObsoletePlaybackCacheCleanup.cleanup(root)

            assertTrue(restoredVodCache.exists())
            assertTrue(File(restoredVodCache, "cached.exo").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
