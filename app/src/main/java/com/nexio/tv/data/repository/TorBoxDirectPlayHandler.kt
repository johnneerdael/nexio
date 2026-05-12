package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.debrid.TorBoxIntegrationProvider
import com.nexio.tv.data.local.TorBoxResumeStore
import com.nexio.tv.data.local.TorBoxSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

sealed interface TorBoxResolvedPlayback {
    data class Resolved(
        val url: String,
        val torrentId: Int,
        val fileId: Int,
        val fileName: String,
        val resumePositionMs: Long,
    ) : TorBoxResolvedPlayback

    data class Failed(val message: String) : TorBoxResolvedPlayback
}

/**
 * Resolves a TorBox playback URL on demand. Called once per card click (and again on
 * autoplay-next). Reads any existing resume position from [TorBoxResumeStore] so the Player
 * can seek to it.
 */
@Singleton
class TorBoxDirectPlayHandler @Inject constructor(
    private val torBoxProvider: TorBoxIntegrationProvider,
    private val resumeStore: TorBoxResumeStore,
    private val settings: TorBoxSettingsDataStore,
) {
    suspend fun resolve(torrentId: Int, fileId: Int, fileName: String): TorBoxResolvedPlayback {
        val apiKey = settings.settings.first().apiKey.trim()
        if (apiKey.isBlank()) {
            return TorBoxResolvedPlayback.Failed("TorBox is not connected.")
        }
        val url = try {
            torBoxProvider.requestDownloadLink(apiKey = apiKey, torrentId = torrentId, fileId = fileId)
                .orEmpty()
                .trim()
        } catch (t: Throwable) {
            return TorBoxResolvedPlayback.Failed(t.message ?: "TorBox playback request failed.")
        }
        if (url.isBlank()) {
            return TorBoxResolvedPlayback.Failed("TorBox returned no playback link.")
        }
        val resume = resumeStore.loadPosition(torrentId, fileId) ?: 0L
        return TorBoxResolvedPlayback.Resolved(
            url = url,
            torrentId = torrentId,
            fileId = fileId,
            fileName = fileName,
            resumePositionMs = resume,
        )
    }
}
