package com.nexio.tv.core.player

import android.util.Log
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class FfmpegStreamMetadataProbeResult(
    val streams: List<FfmpegStreamMetadata>
) {
    val hasEmbeddedAssSsaSubtitleStream: Boolean
        get() = streams.any { stream ->
            stream.codecType.equals("subtitle", ignoreCase = true) &&
                stream.codecName.normalizedCodecName() in ASS_SSA_SUBTITLE_CODECS
        }
}

data class FfmpegStreamMetadata(
    val codecType: String,
    val codecName: String?,
    val width: Int? = null,
    val height: Int? = null,
    val avgFrameRate: String? = null,
    val rFrameRate: String? = null,
    val colorTransfer: String? = null,
    val colorPrimaries: String? = null,
    val dvProfile: Int? = null,
    val hdr10Plus: Boolean = false
)

internal interface FfmpegStreamMetadataBackend {
    fun probeStreamMetadataJson(url: String, requestHeadersBlob: String?): String?
}

private object DefaultFfmpegStreamMetadataBackend : FfmpegStreamMetadataBackend {
    override fun probeStreamMetadataJson(url: String, requestHeadersBlob: String?): String? {
        return FfmpegLibrary.probeDolbyVisionStreamMetadataJson(url, requestHeadersBlob)
    }
}

object FfmpegStreamMetadataProbe {
    private const val TAG = "FfmpegStreamMetadata"
    private const val MAX_CACHE_ENTRIES = 12
    private const val LOG_CHUNK_SIZE = 3000
    private const val PROBE_SIZE_BYTES = 10_000
    private const val ANALYZE_DURATION_US = 10_000
    private const val READ_WRITE_TIMEOUT_US = 5_000_000

    // The bundled FFmpeg network probe path is shared with DV/AFR probing and is not safe to
    // enter concurrently on every Android build.
    private val nativeProbeLock = Any()
    private val cache = object : LinkedHashMap<ProbeKey, FfmpegStreamMetadataProbeResult>(
        MAX_CACHE_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ProbeKey, FfmpegStreamMetadataProbeResult>?
        ): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }
    @Volatile
    private var backend: FfmpegStreamMetadataBackend = DefaultFfmpegStreamMetadataBackend
    @Volatile
    private var diagnosticsEnabled: Boolean = false

    fun setDiagnosticsEnabled(enabled: Boolean) {
        diagnosticsEnabled = enabled
    }

    internal fun setBackendForTesting(testBackend: FfmpegStreamMetadataBackend) {
        synchronized(nativeProbeLock) {
            backend = testBackend
            cache.clear()
        }
    }

    internal fun resetForTesting() {
        synchronized(nativeProbeLock) {
            backend = DefaultFfmpegStreamMetadataBackend
            cache.clear()
        }
        diagnosticsEnabled = false
    }

    suspend fun probe(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): FfmpegStreamMetadataProbeResult? = withContext(Dispatchers.IO) {
        probeBlocking(url = url, headers = headers)
    }

    fun probeBlocking(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): FfmpegStreamMetadataProbeResult? {
        val startedAtMs = System.currentTimeMillis()
        val headerBlob = headers.toProbeHeaderBlob()
        if (diagnosticsEnabled) {
            Log.i(
                TAG,
                "FFMPEG_PROBE_START url=$url command=${buildProbeDebugCommand(url, headerBlob)}"
            )
        }
        return runCatching {
            val key = ProbeKey(url = url, requestHeadersBlob = headerBlob)
            synchronized(nativeProbeLock) {
                cache[key]?.let { cached ->
                    if (diagnosticsEnabled) {
                        Log.i(
                            TAG,
                            "FFMPEG_PROBE_CACHE_HIT elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                                "url=$url streams=${cached.streams.size}"
                        )
                    }
                    return cached
                }
                val rawJson = backend.probeStreamMetadataJson(url, headerBlob)
                if (diagnosticsEnabled) {
                    logChunked(
                        prefix = "FFMPEG_PROBE_RAW_RESPONSE elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                            "url=$url bytes=${rawJson?.length ?: 0}",
                        value = rawJson ?: "<null>"
                    )
                }
                val parsed = rawJson?.let(::parse)
                if (parsed == null || parsed.streams.isEmpty()) {
                    Log.w(
                        TAG,
                        "FFmpeg stream metadata probe returned no streams " +
                            "elapsedMs=${System.currentTimeMillis() - startedAtMs} url=$url"
                    )
                    return null
                }
                if (diagnosticsEnabled) {
                    Log.i(
                        TAG,
                        "FFMPEG_PROBE_PARSED elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                            "url=$url streams=${parsed.streams.size}"
                    )
                }
                parsed.also { cache[key] = it }
            }
        }.getOrElse { error ->
            Log.w(
                TAG,
                "FFmpeg stream metadata probe failed elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                    "url=$url error=${error.message}",
                error
            )
            null
        }
    }

    internal fun debugProbeCommandForTesting(url: String, requestHeadersBlob: String?): String =
        buildProbeDebugCommand(url, requestHeadersBlob)

    internal fun parseForTesting(json: String?): FfmpegStreamMetadataProbeResult {
        return parse(json) ?: FfmpegStreamMetadataProbeResult(emptyList())
    }

    internal fun parse(json: String?): FfmpegStreamMetadataProbeResult? {
        if (json.isNullOrBlank()) return null
        val streams = runCatching {
            JsonParser.parseString(json)
                .asJsonObject
                .getAsJsonArray("streams")
                ?.mapNotNull { element ->
                    val obj = element?.asJsonObject ?: return@mapNotNull null
                    FfmpegStreamMetadata(
                        codecType = obj.get("codec_type")?.asString ?: return@mapNotNull null,
                        codecName = obj.get("codec_name")?.asString,
                        width = obj.get("width")?.asIntOrNull(),
                        height = obj.get("height")?.asIntOrNull(),
                        avgFrameRate = obj.get("avg_frame_rate")?.asStringOrNull(),
                        rFrameRate = obj.get("r_frame_rate")?.asStringOrNull(),
                        colorTransfer = obj.get("color_transfer")?.asStringOrNull(),
                        colorPrimaries = obj.get("color_primaries")?.asStringOrNull(),
                        dvProfile = obj.get("dv_profile")?.asIntOrNull()
                            ?: obj.firstDolbyVisionProfileFromSideData(),
                        hdr10Plus = obj.get("hdr10_plus")?.asBooleanOrNull() ?: false
                    )
                }
                .orEmpty()
        }.getOrNull() ?: return null
        return FfmpegStreamMetadataProbeResult(streams)
    }

    private fun buildProbeDebugCommand(url: String, requestHeadersBlob: String?): String {
        val headersArg = requestHeadersBlob
            ?.takeIf { it.isNotBlank() }
            ?.let { " -headers ${it.shellQuoteForLog()}" }
            .orEmpty()
        return "ffprobe -v error -rw_timeout $READ_WRITE_TIMEOUT_US " +
            "-probesize $PROBE_SIZE_BYTES -analyzeduration $ANALYZE_DURATION_US" +
            headersArg +
            " -select_streams v:0,s " +
            "-show_entries stream=index,codec_type,codec_name,width,height,avg_frame_rate,r_frame_rate:" +
            "stream_side_data=side_data_type,dv_profile -of json ${url.shellQuoteForLog()}"
    }

    private fun logChunked(prefix: String, value: String) {
        if (value.length <= LOG_CHUNK_SIZE) {
            Log.i(TAG, "$prefix raw=$value")
            return
        }

        var chunkIndex = 0
        var offset = 0
        while (offset < value.length) {
            val end = (offset + LOG_CHUNK_SIZE).coerceAtMost(value.length)
            Log.i(
                TAG,
                "$prefix chunk=$chunkIndex range=$offset..$end raw=${value.substring(offset, end)}"
            )
            chunkIndex += 1
            offset = end
        }
    }
}

private data class ProbeKey(
    val url: String,
    val requestHeadersBlob: String?
)

private val ASS_SSA_SUBTITLE_CODECS = setOf(
    "ass",
    "ssa",
    "s_text/ass",
    "s_text/ssa"
)

private fun String?.normalizedCodecName(): String {
    return this
        ?.trim()
        ?.lowercase(Locale.US)
        .orEmpty()
}

private fun Map<String, String>.toProbeHeaderBlob(): String? {
    val entries = filterKeys { !it.equals("Range", ignoreCase = true) }
    if (entries.isEmpty()) return null
    return entries.entries.joinToString(separator = "\r\n", postfix = "\r\n") { (key, value) ->
        "$key: $value"
    }
}

private fun String.shellQuoteForLog(): String =
    "'" + replace("\\", "\\\\")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("'", "'\"'\"'") + "'"

private fun JsonElement.asStringOrNull(): String? {
    return runCatching { asString }.getOrNull()
}

private fun JsonElement.asIntOrNull(): Int? {
    return runCatching { asInt }.getOrNull()
}

private fun JsonElement.asBooleanOrNull(): Boolean? {
    return runCatching { asBoolean }.getOrNull()
}

private fun com.google.gson.JsonObject.firstDolbyVisionProfileFromSideData(): Int? {
    return getAsJsonArray("side_data_list")
        ?.asSequence()
        ?.mapNotNull { sideData ->
            sideData
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("dv_profile")
                ?.asIntOrNull()
        }
        ?.firstOrNull()
}
