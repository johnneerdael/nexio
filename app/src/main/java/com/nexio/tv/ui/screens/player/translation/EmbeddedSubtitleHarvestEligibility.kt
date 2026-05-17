package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.MimeTypes
import com.nexio.tv.ui.screens.player.TrackInfo
import java.util.Locale

internal object EmbeddedSubtitleHarvestEligibility {
    fun isEligible(
        streamUrl: String,
        filename: String?,
        selectedTrack: TrackInfo?,
        selectedAddonSubtitlePresent: Boolean,
        autoTranslateEnabled: Boolean
    ): Boolean {
        if (!autoTranslateEnabled) return false
        if (selectedAddonSubtitlePresent) return false
        val track = selectedTrack ?: return false
        if (!isMatroska(streamUrl, filename)) return false
        return isSubRip(track)
    }

    fun isMatroska(streamUrl: String, filename: String?): Boolean {
        return sequenceOf(streamUrl, filename.orEmpty())
            .map { it.trim().lowercase(Locale.ROOT) }
            .any { value ->
                val path = value.substringBefore('?').substringBefore('#')
                path.endsWith(".mkv") ||
                    path.endsWith(".mk3d") ||
                    path.endsWith(".mka") ||
                    value.contains(".mkv/") ||
                    value.contains(".mkv%")
            }
    }

    fun isSubRip(track: TrackInfo): Boolean {
        val mimeType = track.mimeType?.trim()?.lowercase(Locale.ROOT)
        if (mimeType == MimeTypes.APPLICATION_SUBRIP || mimeType == "application/x-subrip") {
            return true
        }

        val codec = track.codec?.trim()?.lowercase(Locale.ROOT)
        return codec == MimeTypes.APPLICATION_SUBRIP ||
            codec == "application/x-subrip" ||
            codec == "subrip" ||
            codec == "srt"
    }
}
