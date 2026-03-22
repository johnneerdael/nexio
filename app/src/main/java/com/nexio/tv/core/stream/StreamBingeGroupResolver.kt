package com.nexio.tv.core.stream

import com.nexio.tv.domain.model.Stream
import java.util.Locale

object StreamBingeGroupResolver {
    fun resolve(stream: Stream): String? {
        stream.behaviorHints?.bingeGroup
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val parsed = AioStrictStreamParser.parse(stream)
        return buildFallback(stream, parsed)
    }

    fun buildFallback(stream: Stream, parsed: ParsedStreamInfo): String? {
        val parts = buildList {
            add("addon=${normalize(stream.addonName)}")
            parsed.serviceId?.let { add("service=${normalize(it)}") }
            add("type=${normalize(parsed.transportKind.name)}")
            parsed.title?.let { add("title=${normalize(it)}") }
            parsed.year?.let { add("year=${normalize(it)}") }
            parsed.resolution?.let { add("res=${normalize(it)}") }
            parsed.quality?.let { add("quality=${normalize(it)}") }
            parsed.encode?.let { add("encode=${normalize(it)}") }
            parsed.releaseGroup?.let { add("group=${normalize(it)}") }
            if (parsed.visualTags.isNotEmpty()) {
                add("visual=${parsed.visualTags.joinToString(",") { normalize(it) }}")
            }
            if (parsed.audioTags.isNotEmpty()) {
                add("audio=${parsed.audioTags.joinToString(",") { normalize(it) }}")
            }
            if (parsed.audioChannels.isNotEmpty()) {
                add("channels=${parsed.audioChannels.joinToString(",") { normalize(it) }}")
            }
            if (parsed.languages.isNotEmpty()) {
                add("langs=${parsed.languages.joinToString(",") { normalize(it) }}")
            }
        }
        return parts.takeIf { it.size >= 4 }?.joinToString("|")
    }

    private fun normalize(value: String): String {
        return value
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("""[^\p{L}\p{N}]+"""), "")
    }
}
