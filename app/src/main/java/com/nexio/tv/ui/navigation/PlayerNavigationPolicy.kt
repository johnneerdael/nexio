package com.nexio.tv.ui.navigation

import com.nexio.tv.ui.screens.player.PlayerLaunchSource

internal fun shouldReturnDirectLibraryPlaybackToLibrary(
    launchSource: PlayerLaunchSource,
    contentId: String? = null,
    videoId: String? = null
): Boolean {
    return launchSource == PlayerLaunchSource.LIBRARY_DIRECT ||
        isDebridLibraryEntryId(contentId) ||
        isDebridLibraryEntryId(videoId)
}

private fun isDebridLibraryEntryId(id: String?): Boolean {
    return id?.startsWith("rd:", ignoreCase = true) == true ||
        id?.startsWith("pm:", ignoreCase = true) == true
}
