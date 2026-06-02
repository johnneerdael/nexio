package com.nexio.tv.ui.navigation

import android.util.Log
import androidx.lifecycle.ViewModel
import com.nexio.tv.core.addon.TekenfilmsHomePlaybackPolicy
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.repository.StreamRepository
import com.nexio.tv.ui.screens.player.PlayerLaunchSource
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class TekenfilmsDirectPlaybackViewModel @Inject constructor(
    private val streamRepository: StreamRepository
) : ViewModel() {
    suspend fun buildPlayerRoute(
        item: MetaPreview,
        addonBaseUrl: String = TekenfilmsHomePlaybackPolicy.BASE_URL
    ): String? {
        val normalizedBaseUrl = TekenfilmsHomePlaybackPolicy.normalizeBaseUrl(addonBaseUrl)
        if (
            !TekenfilmsHomePlaybackPolicy.isTekenfilmsItem(
                addonBaseUrl = normalizedBaseUrl,
                addonId = TekenfilmsHomePlaybackPolicy.ADDON_ID,
                catalogId = TekenfilmsHomePlaybackPolicy.CATALOG_ID,
                itemType = item.apiType,
                itemId = item.id
            )
        ) {
            return null
        }
        val streamType = item.apiType.trim().lowercase(Locale.ROOT)
        return when (
            val result = streamRepository.getStreamsFromAddon(
                baseUrl = normalizedBaseUrl,
                type = streamType,
                videoId = item.id
            )
        ) {
            is NetworkResult.Success -> {
                val stream = result.data.firstOrNull { stream -> !stream.getStreamUrl().isNullOrBlank() }
                val url = stream?.getStreamUrl()?.takeIf { url -> url.isNotBlank() }
                if (stream != null && url != null) {
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
                        addonBaseUrl = normalizedBaseUrl
                    )
                } else {
                    null
                }
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
