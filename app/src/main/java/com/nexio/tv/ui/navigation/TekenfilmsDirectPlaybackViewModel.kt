package com.nexio.tv.ui.navigation

import android.util.Log
import androidx.lifecycle.ViewModel
import com.nexio.tv.core.addon.TekenfilmsHomePlaybackPolicy
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.repository.StreamRepository
import com.nexio.tv.ui.screens.player.PlayerLaunchSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TekenfilmsDirectPlaybackViewModel @Inject constructor(
    private val streamRepository: StreamRepository
) : ViewModel() {
    suspend fun buildPlayerRoute(item: MetaPreview): String? {
        if (
            !TekenfilmsHomePlaybackPolicy.isTekenfilmsItem(
                addonBaseUrl = TekenfilmsHomePlaybackPolicy.BASE_URL,
                addonId = TekenfilmsHomePlaybackPolicy.ADDON_ID,
                catalogId = TekenfilmsHomePlaybackPolicy.CATALOG_ID,
                itemType = item.apiType,
                itemId = item.id
            )
        ) {
            return null
        }
        return when (
            val result = streamRepository.getStreamsFromAddon(
                baseUrl = TekenfilmsHomePlaybackPolicy.BASE_URL,
                type = TekenfilmsHomePlaybackPolicy.TYPE,
                videoId = item.id
            )
        ) {
            is NetworkResult.Success -> {
                val stream = result.data.firstOrNull { stream -> !stream.getStreamUrl().isNullOrBlank() }
                val url = stream?.getStreamUrl()?.takeIf { url -> url.isNotBlank() } ?: return null
                Screen.Player.createRoute(
                    streamUrl = url,
                    title = item.name,
                    streamName = stream.getDisplayName(),
                    headers = stream.behaviorHints?.proxyHeaders?.request,
                    contentId = item.id,
                    contentType = item.apiType,
                    contentName = item.name,
                    originalLanguage = chooseNavOriginalLanguage(item),
                    poster = item.displayPoster,
                    backdrop = item.displayBackground,
                    logo = item.displayLogo,
                    videoId = item.id,
                    filename = stream.behaviorHints?.filename,
                    videoHash = stream.behaviorHints?.videoHash,
                    videoSize = stream.behaviorHints?.videoSize,
                    launchSource = PlayerLaunchSource.STREAM,
                    addonBaseUrl = TekenfilmsHomePlaybackPolicy.BASE_URL
                )
            }

            is NetworkResult.Error -> {
                Log.w(TAG, "Tekenfilms direct playback stream lookup failed: ${result.message}")
                null
            }

            NetworkResult.Loading -> null
        }
    }

    private companion object {
        private const val TAG = "TekenfilmsDirectPlayback"
    }
}
