package com.nexio.tv.ui.navigation

import com.nexio.tv.ui.screens.player.PlayerLaunchSource

internal fun shouldReturnDirectLibraryPlaybackToLibrary(
    launchSource: PlayerLaunchSource
): Boolean = launchSource == PlayerLaunchSource.LIBRARY_DIRECT
