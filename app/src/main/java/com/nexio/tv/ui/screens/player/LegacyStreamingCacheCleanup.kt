package com.nexio.tv.ui.screens.player

import java.io.File

internal object LegacyStreamingCacheCleanup {
    private const val LEGACY_PLAYER_VOD_CACHE_DIR = "player_vod_cache_v2"

    fun cleanup(cacheRoot: File) {
        val legacyDir = File(cacheRoot, LEGACY_PLAYER_VOD_CACHE_DIR)
        if (!legacyDir.exists()) return
        if (!legacyDir.isDirectory) return
        runCatching { legacyDir.deleteRecursively() }
    }
}
