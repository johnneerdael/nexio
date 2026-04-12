package com.nexio.tv.ui.screens.player

import java.io.File

internal object ObsoletePlaybackCacheCleanup {
    private val obsoleteDirectoryNames = setOf(
        "stream-cache",
        "player_vod_cache_v2"
    )

    fun cleanup(cacheRoot: File) {
        obsoleteDirectoryNames.forEach { directoryName ->
            val staleDir = File(cacheRoot, directoryName)
            if (!staleDir.exists()) return@forEach
            if (!staleDir.isDirectory) return@forEach
            runCatching { staleDir.deleteRecursively() }
        }
    }
}
