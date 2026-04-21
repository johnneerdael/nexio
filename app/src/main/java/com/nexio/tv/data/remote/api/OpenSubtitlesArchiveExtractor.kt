package com.nexio.tv.data.remote.api

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Downloads a subtitle pointed at by an rest.opensubtitles.org row and returns a
 * local file that the player can load via `file://`.
 *
 * rest.opensubtitles.org serves subtitles in three shapes:
 *  - plain `.srt` (rare, usually `ZipDownloadLink` is provided instead)
 *  - gzipped `.srt` (Content-Type `application/x-gzip`)
 *  - `.zip` archive containing one subtitle file
 *
 * We sniff the payload magic bytes rather than trust the server extension.
 */
class OpenSubtitlesArchiveExtractor(
    private val okHttpClient: OkHttpClient,
    private val cacheDir: File,
    private val userAgent: String
) {

    /**
     * Fetch [url] and write the inner subtitle file into [cacheDir] under
     * a stable name derived from [subtitleId]. Returns the written file, or
     * null if the download failed or no subtitle could be extracted.
     */
    fun download(url: String, subtitleId: String): File? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        val body = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.bytes() ?: return null
        }
        if (body.isEmpty()) return null

        val (bytes, extension) = when {
            isZip(body) -> extractZip(body) ?: return null
            isGzip(body) -> decompressGzip(body) to "srt"
            else -> body to "srt"
        }
        if (bytes.isEmpty()) return null

        if (!cacheDir.exists()) cacheDir.mkdirs()
        val safeId = subtitleId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifBlank { bytes.size.toString() }
        val out = File(cacheDir, "os-$safeId.$extension")
        out.writeBytes(bytes)
        return out
    }

    private fun isGzip(data: ByteArray): Boolean =
        data.size >= 2 && data[0] == 0x1F.toByte() && data[1] == 0x8B.toByte()

    private fun isZip(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte() &&
            (data[2] == 0x03.toByte() || data[2] == 0x05.toByte() || data[2] == 0x07.toByte())

    private fun decompressGzip(data: ByteArray): ByteArray =
        GZIPInputStream(data.inputStream()).use { it.readBytes() }

    private fun extractZip(data: ByteArray): Pair<ByteArray, String>? {
        ZipInputStream(data.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: return null
                if (entry.isDirectory) continue
                val name = entry.name.lowercase()
                if (name.endsWith(".srt") || name.endsWith(".vtt") || name.endsWith(".ass") || name.endsWith(".ssa")) {
                    val ext = name.substringAfterLast('.', missingDelimiterValue = "srt")
                    return zis.readBytes() to ext
                }
            }
        }
    }
}
