package com.nexio.tv.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.net.URLDecoder

internal data class PlayerNavigationArgs(
    val streamUrl: String,
    val title: String,
    val streamName: String?,
    val serviceKey: String?,
    val year: String?,
    val headersJson: String?,
    val contentId: String?,
    val contentType: String?,
    val contentName: String?,
    val originalLanguage: String?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String?,
    val initialSeason: Int?,
    val initialEpisode: Int?,
    val initialEpisodeTitle: String?,
    val bingeGroup: String?,
    val rememberedAudioLanguage: String?,
    val rememberedAudioName: String?,
    val playerBackend: String,
    val filename: String?,
    val videoHash: String?,
    val videoSize: Long?,
    val startFromBeginning: Boolean,
    val launchSource: PlayerLaunchSource,
    val resumePositionMs: Long?,
    val resumeDurationMs: Long?,
    val resumeProgressPercent: Float?,
    val resumeLastWatchedMs: Long?,
    val resumeSource: String?
) {
    companion object {
        private const val URL_SAFE_BASE64_PREFIX = "u64_"

        fun from(savedStateHandle: SavedStateHandle): PlayerNavigationArgs {
            fun decodedOrNull(key: String): String? {
                val value = savedStateHandle.get<String>(key) ?: return null
                return if (value.isNotEmpty()) URLDecoder.decode(value, "UTF-8") else null
            }

            fun decodedStreamUrl(): String? {
                val value = savedStateHandle.get<String>("streamUrl") ?: return null
                if (value.isEmpty()) return null
                if (value.startsWith(URL_SAFE_BASE64_PREFIX)) {
                    val payload = value.removePrefix(URL_SAFE_BASE64_PREFIX)
                    return runCatching {
                        String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
                    }.getOrNull()
                }
                return URLDecoder.decode(value, "UTF-8")
            }

            return PlayerNavigationArgs(
                streamUrl = decodedStreamUrl() ?: "",
                title = decodedOrNull("title") ?: "",
                streamName = decodedOrNull("streamName"),
                serviceKey = decodedOrNull("serviceKey"),
                year = decodedOrNull("year"),
                headersJson = decodedOrNull("headers"),
                // NavController already decodes these IDs.
                contentId = savedStateHandle.get<String>("contentId")?.takeIf { it.isNotEmpty() },
                contentType = savedStateHandle.get<String>("contentType")?.takeIf { it.isNotEmpty() },
                contentName = decodedOrNull("contentName"),
                originalLanguage = decodedOrNull("originalLanguage"),
                poster = decodedOrNull("poster"),
                backdrop = decodedOrNull("backdrop"),
                logo = decodedOrNull("logo"),
                videoId = savedStateHandle.get<String>("videoId")?.takeIf { it.isNotEmpty() },
                initialSeason = savedStateHandle.get<String>("season")?.toIntOrNull(),
                initialEpisode = savedStateHandle.get<String>("episode")?.toIntOrNull(),
                initialEpisodeTitle = decodedOrNull("episodeTitle"),
                bingeGroup = decodedOrNull("bingeGroup"),
                rememberedAudioLanguage = decodedOrNull("rememberedAudioLanguage"),
                rememberedAudioName = decodedOrNull("rememberedAudioName"),
                playerBackend = decodedOrNull("playerBackend") ?: "INTERNAL",
                filename = decodedOrNull("filename"),
                videoHash = savedStateHandle.get<String>("videoHash")?.takeIf { it.isNotEmpty() },
                videoSize = savedStateHandle.get<String>("videoSize")?.toLongOrNull(),
                startFromBeginning = savedStateHandle.get<String>("startFromBeginning")?.toBooleanStrictOrNull() == true,
                launchSource = PlayerLaunchSource.from(decodedOrNull("launchSource")),
                resumePositionMs = savedStateHandle.get<String>("resumePositionMs")?.toLongOrNull(),
                resumeDurationMs = savedStateHandle.get<String>("resumeDurationMs")?.toLongOrNull(),
                resumeProgressPercent = savedStateHandle.get<String>("resumeProgressPercent")?.toFloatOrNull(),
                resumeLastWatchedMs = savedStateHandle.get<String>("resumeLastWatchedMs")?.toLongOrNull(),
                resumeSource = decodedOrNull("resumeSource")
            )
        }
    }
}
