package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.MimeTypes
import com.nexio.tv.ui.screens.player.TrackInfo
import java.util.Locale

internal enum class EmbeddedSubtitleContainer(val logValue: String) {
    MATROSKA("mkv"),
    MP4("mp4")
}

internal data class EmbeddedSubtitleEligibilityResult(
    val eligible: Boolean,
    val container: EmbeddedSubtitleContainer?,
    val reason: String
)

internal object EmbeddedSubtitleHarvestEligibility {
    fun evaluate(
        streamUrl: String,
        filename: String?,
        selectedTrack: TrackInfo?,
        selectedAddonSubtitlePresent: Boolean,
        autoTranslateEnabled: Boolean
    ): EmbeddedSubtitleEligibilityResult {
        val container = containerFor(streamUrl, filename)

        if (!autoTranslateEnabled) {
            return EmbeddedSubtitleEligibilityResult(false, container, "auto_translate_disabled")
        }
        if (selectedAddonSubtitlePresent) {
            return EmbeddedSubtitleEligibilityResult(false, container, "addon_subtitle_selected")
        }

        val track = selectedTrack
            ?: return EmbeddedSubtitleEligibilityResult(false, container, "no_track")

        if (container == null) {
            return EmbeddedSubtitleEligibilityResult(false, null, "unsupported_container")
        }
        if (isBitmapSubtitleTrack(track) || !isSupportedTextTrack(track)) {
            return EmbeddedSubtitleEligibilityResult(false, container, "unsupported_track")
        }

        return EmbeddedSubtitleEligibilityResult(true, container, "eligible")
    }

    fun isEligible(
        streamUrl: String,
        filename: String?,
        selectedTrack: TrackInfo?,
        selectedAddonSubtitlePresent: Boolean,
        autoTranslateEnabled: Boolean
    ): Boolean {
        return evaluate(
            streamUrl = streamUrl,
            filename = filename,
            selectedTrack = selectedTrack,
            selectedAddonSubtitlePresent = selectedAddonSubtitlePresent,
            autoTranslateEnabled = autoTranslateEnabled
        ).eligible
    }

    fun containerFor(streamUrl: String, filename: String?): EmbeddedSubtitleContainer? {
        if (hasContainerExtension(streamUrl, filename, ".mkv", ".mk3d", ".mka")) {
            return EmbeddedSubtitleContainer.MATROSKA
        }
        if (hasContainerExtension(streamUrl, filename, ".mp4", ".m4v", ".mov")) {
            return EmbeddedSubtitleContainer.MP4
        }
        return null
    }

    fun isMatroska(streamUrl: String, filename: String?): Boolean {
        return containerFor(streamUrl, filename) == EmbeddedSubtitleContainer.MATROSKA
    }

    fun isMp4(streamUrl: String, filename: String?): Boolean {
        return containerFor(streamUrl, filename) == EmbeddedSubtitleContainer.MP4
    }

    fun isSupportedTextTrack(track: TrackInfo): Boolean {
        if (isSubRip(track)) return true

        return normalizedMimeType(track) in supportedTextMimeTypes ||
            normalizedCodec(track).matchesAnyCodec(
                "tx3g",
                "wvtt",
                "stpp",
                "ttml",
                "webvtt",
                "vtt"
            )
    }

    fun isSubRip(track: TrackInfo): Boolean {
        val mimeType = normalizedMimeType(track)
        if (mimeType == MimeTypes.APPLICATION_SUBRIP || mimeType == "application/x-subrip") {
            return true
        }

        val codec = normalizedCodec(track)
        return codec == MimeTypes.APPLICATION_SUBRIP ||
            codec == "application/x-subrip" ||
            codec == "subrip" ||
            codec == "srt"
    }

    fun isBitmapSubtitleTrack(track: TrackInfo): Boolean {
        return normalizedMimeType(track) in bitmapSubtitleMimeTypes ||
            normalizedCodec(track).matchesAnyCodec(
                "pgs",
                "vobsub",
                "dvd_subtitle",
                "dvbsub",
                "dvbsubs"
            )
    }

    private fun hasContainerExtension(
        streamUrl: String,
        filename: String?,
        vararg extensions: String
    ): Boolean {
        return sequenceOf(streamUrl, filename.orEmpty())
            .map { it.trim().lowercase(Locale.ROOT) }
            .any { value ->
                val path = value.substringBefore('?').substringBefore('#')
                extensions.any { extension ->
                    path.endsWith(extension) ||
                        value.contains("$extension/") ||
                        value.contains("$extension%")
                }
            }
    }

    private fun normalizedMimeType(track: TrackInfo): String? {
        return track.mimeType?.trim()?.lowercase(Locale.ROOT)
    }

    private fun normalizedCodec(track: TrackInfo): String {
        return track.codec?.trim()?.lowercase(Locale.ROOT).orEmpty()
    }

    private fun String.matchesAnyCodec(vararg codecs: String): Boolean {
        if (isEmpty()) return false

        return split(',').any { rawCodec ->
            val codec = rawCodec.trim()
            codecs.any { expected ->
                codec == expected || codec.startsWith("$expected.")
            }
        }
    }

    private val supportedTextMimeTypes = setOf(
        MimeTypes.APPLICATION_MEDIA3_CUES,
        MimeTypes.APPLICATION_TX3G,
        MimeTypes.APPLICATION_MP4VTT,
        MimeTypes.APPLICATION_TTML,
        MimeTypes.TEXT_VTT,
        "application/ttml+xml"
    )

    private val bitmapSubtitleMimeTypes = setOf(
        MimeTypes.APPLICATION_PGS,
        MimeTypes.APPLICATION_VOBSUB,
        MimeTypes.APPLICATION_DVBSUBS,
        "application/pgs",
        "application/vobsub",
        "application/dvbsubs"
    )
}
