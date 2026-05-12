package com.nexio.tv.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin facade over [DebridLibraryService.nextPlayableFileInTorrent] so the Player module does
 * not have to depend on the full library service surface.
 */
@Singleton
class TorBoxAutoplayNext @Inject constructor(
    private val library: DebridLibraryService,
) {
    suspend fun nextEntryInSameTorrent(
        torrentId: Int,
        currentFileId: Int,
    ): DebridLibraryService.Companion.TorBoxNextFile? =
        library.nextPlayableFileInTorrent(torrentId = torrentId, currentFileId = currentFileId)
}
