package com.nexio.tv.data.repository.servicewrap

import com.nexio.tv.core.stream.AioParserSupport
import com.nexio.tv.core.stream.AioStrictFileParser
import com.nexio.tv.core.stream.AioStrictStreamParser
import com.nexio.tv.core.stream.ParsedStreamInfo
import com.nexio.tv.domain.model.Stream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class ServiceWrapRequestContext(
    val contentType: String,
    val season: Int?,
    val episode: Int?
)

data class ServiceWrapResolvedBatch(
    val addonName: String,
    val addonLogo: String?,
    val wrappedStreams: List<Stream>,
    val isTerminal: Boolean = true
)

data class ServiceWrapResolutionBatch(
    val streams: List<ResolvedServiceWrapStream>,
    val isTerminal: Boolean
)

data class ServiceWrapResolutionChunkBatch(
    val streamsByHash: Map<String, List<ResolvedServiceWrapStream>>,
    val isTerminal: Boolean
) {
    companion object {
        fun terminalEmpty(hashes: Collection<String>): ServiceWrapResolutionChunkBatch {
            return ServiceWrapResolutionChunkBatch(
                streamsByHash = hashes.associateWith { emptyList() },
                isTerminal = true
            )
        }
    }
}

data class ServiceWrapResolvedChunk(
    val candidate: WrapCandidate,
    val wrappedStreams: List<Stream>,
    val isTerminal: Boolean
)

interface ServiceWrapSession {
    fun processAddonStreams(
        addonName: String,
        addonLogo: String?,
        streams: List<Stream>
    ): ServiceWrapProcessResult

    fun inFlightCount(): Int
}

data class ServiceWrapProcessResult(
    val visibleStreams: List<Stream>,
    val launchedWrapCount: Int
)

enum class ServiceWrapProvider(
    val providerId: String,
    val displayName: String,
    val shortName: String
) {
    REAL_DEBRID(providerId = "RD", displayName = "Real-Debrid", shortName = "RD"),
    PREMIUMIZE(providerId = "PM", displayName = "Premiumize", shortName = "PM"),
    TORBOX(providerId = "TB", displayName = "TorBox", shortName = "TB"),
    EASY_DEBRID(providerId = "ED", displayName = "EasyDebrid", shortName = "ED")
}

data class WrapCandidate(
    val normalizedInfoHash: String,
    val magnetUri: String,
    val sourceStream: Stream,
    val sourceAddonName: String,
    val sourceAddonLogo: String?,
    val sourceStreamKey: String,
    val sourceParsed: ParsedStreamInfo
)

data class ResolvedServiceWrapStream(
    val provider: ServiceWrapProvider,
    val normalizedInfoHash: String,
    val playbackUrl: String,
    val selectedFileIndex: Int,
    val filename: String?,
    val folderName: String?,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val bitrate: Long?,
    val width: Int?,
    val height: Int?,
    val sourceLink: String? = null
)

internal fun normalizeInfoHash(value: String): String? {
    val trimmed = value.trim()
    val matched = BTIH_HASH_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
        ?: PLAIN_HASH_REGEX.find(trimmed)?.value
        ?: return null
    return matched.uppercase(Locale.US)
}

internal fun extractInfoHash(stream: Stream): String? {
    normalizeInfoHash(stream.infoHash.orEmpty())?.let { return it }
    stream.getStreamUrl()?.let { url ->
        runCatching { java.net.URLDecoder.decode(url, StandardCharsets.UTF_8.name()) }
            .getOrNull()
            ?.let { decoded ->
                normalizeInfoHash(decoded)?.let { return it }
            }
        normalizeInfoHash(url)?.let { return it }
    }
    stream.sources.orEmpty().forEach { source ->
        normalizeInfoHash(source)?.let { return it }
        runCatching { java.net.URLDecoder.decode(source, StandardCharsets.UTF_8.name()) }
            .getOrNull()
            ?.let { decoded ->
                normalizeInfoHash(decoded)?.let { return it }
            }
    }
    return null
}

internal fun buildMagnetUri(hash: String, stream: Stream, parsed: ParsedStreamInfo): String {
    val existing = stream.getStreamUrl()
        ?.takeIf { it.startsWith("magnet:", ignoreCase = true) }
    if (existing != null) return existing

    val displayName = stream.behaviorHints?.filename
        ?: parsed.filename
        ?: stream.getDisplayName()
    return buildString {
        append("magnet:?xt=urn:btih:")
        append(hash)
        if (displayName.isNotBlank()) {
            append("&dn=")
            append(URLEncoder.encode(displayName, StandardCharsets.UTF_8.name()))
        }
    }
}

internal fun stableWrapStreamKey(stream: Stream): String {
    val hints = stream.behaviorHints
    return buildString {
        append(stream.addonName)
        append('|')
        append(stream.name.orEmpty())
        append('|')
        append(stream.title.orEmpty())
        append('|')
        append(stream.description.orEmpty())
        append('|')
        append(stream.url.orEmpty())
        append('|')
        append(stream.externalUrl.orEmpty())
        append('|')
        append(stream.infoHash.orEmpty())
        append('|')
        append(stream.fileIdx ?: -1)
        append('|')
        append(hints?.filename.orEmpty())
        append('|')
        append(hints?.videoSize ?: -1L)
    }
}

internal fun parseFileCandidate(path: String?): ParsedFileCandidate? {
    val normalizedPath = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val filename = normalizedPath.substringAfterLast('/').trim().takeIf { it.isNotBlank() } ?: return null
    val folderName = normalizedPath
        .substringBeforeLast('/', "")
        .substringAfterLast('/')
        .trim()
        .takeIf { it.isNotBlank() }
    val fileParsed = AioStrictFileParser.parse(filename)
    val folderParsed = folderName?.let(AioStrictFileParser::parse)
    return ParsedFileCandidate(
        path = normalizedPath,
        filename = filename,
        folderName = folderName,
        parsed = AioParserSupport.mergeParsedFiles(fileParsed, folderParsed) ?: fileParsed
    )
}

data class ParsedFileCandidate(
    val path: String?,
    val filename: String,
    val folderName: String? = null,
    val parsed: com.nexio.tv.core.stream.AioStrictParsedFile
)

internal fun parseSourceStream(stream: Stream): ParsedStreamInfo = AioStrictStreamParser.parse(stream)

private val BTIH_HASH_REGEX = Regex("""(?i)btih:([a-f0-9]{40})""")
private val PLAIN_HASH_REGEX = Regex("""(?i)\b[a-f0-9]{40}\b""")
