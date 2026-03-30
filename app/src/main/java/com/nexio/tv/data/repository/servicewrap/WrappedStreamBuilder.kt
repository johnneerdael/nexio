package com.nexio.tv.data.repository.servicewrap

import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WrappedStreamBuilder @Inject constructor() {

    fun build(
        candidate: WrapCandidate,
        resolvedStreams: List<ResolvedServiceWrapStream>
    ): List<Stream> {
        val uniqueKeys = HashSet<String>()
        return resolvedStreams.mapNotNull { resolved ->
            val dedupeKey = buildString {
                append(resolved.normalizedInfoHash)
                append('|')
                append(resolved.provider.providerId)
                append('|')
                append(resolved.selectedFileIndex)
                append('|')
                append(resolved.playbackUrl.lowercase(Locale.US))
            }
            if (!uniqueKeys.add(dedupeKey)) {
                return@mapNotNull null
            }

            val filename = resolved.filename?.trim()?.takeIf { it.isNotBlank() }
            val folderName = resolved.folderName?.trim()?.takeIf { it.isNotBlank() }
            val description = buildList {
                add("${resolved.provider.shortName}+ cached")
                folderName?.let { add("📁 $it") }
                filename?.let { add("📄 $it") }
                resolved.sizeBytes?.takeIf { it > 0L }?.let { add("💾 ${formatBytes(it)}") }
                resolved.bitrate?.takeIf { it > 0L }?.let { add("🎚 ${formatBitrate(it)}") }
                add("Wrapped via ${resolved.provider.displayName}")
                add("Source: ${candidate.sourceAddonName}")
            }.joinToString("\n")

            Stream(
                name = "⚡ ${resolved.provider.displayName}",
                title = candidate.sourceStream.title,
                description = description,
                url = resolved.playbackUrl,
                ytId = null,
                infoHash = resolved.normalizedInfoHash,
                fileIdx = resolved.selectedFileIndex,
                externalUrl = null,
                behaviorHints = StreamBehaviorHints(
                    notWebReady = false,
                    bingeGroup = candidate.sourceStream.behaviorHints?.bingeGroup,
                    countryWhitelist = candidate.sourceStream.behaviorHints?.countryWhitelist,
                    proxyHeaders = null,
                    videoHash = candidate.sourceStream.behaviorHints?.videoHash,
                    videoSize = resolved.sizeBytes,
                    filename = filename
                ),
                sources = candidate.sourceStream.sources,
                addonName = candidate.sourceAddonName,
                addonLogo = candidate.sourceAddonLogo,
                addonParserPreset = AddonParserPreset.GENERIC,
                serviceWrapSourceHash = resolved.normalizedInfoHash,
                wrappedProviderId = resolved.provider.providerId,
                wrappedOriginalAddonName = candidate.sourceAddonName,
                wrappedOriginalStreamKey = candidate.sourceStreamKey
            )
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        val formatted = if (value >= 10 || unitIndex == 0) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return "$formatted ${units[unitIndex]}"
    }

    private fun formatBitrate(bitrate: Long): String {
        val megaBitsPerSecond = bitrate / 1_000_000.0
        return if (megaBitsPerSecond >= 10) {
            "${megaBitsPerSecond.toLong()} Mbps"
        } else {
            String.format(Locale.US, "%.1f Mbps", megaBitsPerSecond)
        }
    }
}
