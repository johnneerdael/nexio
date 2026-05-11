package com.nexio.tv.data.trailer.captions

import android.content.Context
import com.nexio.tv.data.trailer.SelectedTrailerCaptionTrack
import com.nexio.tv.data.trailer.YOUTUBE_STABLE_WEB_USER_AGENT
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Fetches a YouTube SRV3 caption track, parses + converts to SRT, and
 * caches the result under `cacheDir/trailer-subtitles/`. Returns a
 * `file://` URI suitable for `MediaItem.SubtitleConfiguration` with
 * `application/x-subrip` MIME (ExoPlayer's SubripParser).
 *
 * Cache key is derived from a SHA-1 of the caption baseUrl plus the
 * effective tlang. baseUrl already encodes the video and source-track
 * identity; translated tracks cache independently via the tlang suffix.
 */
@Singleton
class TrailerSubtitleCache @Inject constructor(
    @ApplicationContext private val applicationContext: Context
) {

    private val mutex = Mutex()
    private val baseDir: File by lazy {
        File(applicationContext.cacheDir, "trailer-subtitles").apply { mkdirs() }
    }

    /**
     * Returns a `file://` URI to an SRT file for the given track, fetching
     * and caching on first call. Returns `null` on network or parse failure.
     */
    suspend fun ensure(selected: SelectedTrailerCaptionTrack): String? =
        mutex.withLock {
            val target = cacheFileFor(selected)
            if (target.exists() && target.length() > 0) {
                return@withLock target.toURI().toString()
            }
            val srv3Url = buildSrv3Url(selected)
            val xml = fetchSrv3(srv3Url) ?: return@withLock null
            val lines = SrvCaptionParser.parse(xml)
            if (lines.isEmpty()) return@withLock null
            val srt = SrtSerializer.serialize(lines)
            try {
                target.writeText(srt, Charsets.UTF_8)
            } catch (e: IOException) {
                return@withLock null
            }
            target.toURI().toString()
        }

    private fun cacheFileFor(selected: SelectedTrailerCaptionTrack): File {
        val sourceKey = buildString {
            append(selected.baseUrl)
            selected.translateTo?.takeIf { it.isNotBlank() }?.let {
                append('|').append(it)
            }
        }
        val hash = sha1Hex(sourceKey).take(16)
        val lang = selected.languageCode.replace('/', '_')
        val tlangSuffix = selected.translateTo?.takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()
        return File(baseDir, "$hash-$lang$tlangSuffix.srt")
    }

    private fun sha1Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Construct the SRV3 URL: strip pre-existing `format=`, `fmt=`, and
     * `tlang=` from the baseUrl (YouTube web baseUrls carry
     * `format=json3` by default which we don't want), then append
     * `format=3&fmt=3` plus any `tlang=` translation. Matches
     * YoutubeExplode's ClosedCaptionController.cs belt-and-suspenders.
     */
    internal fun buildSrv3Url(selected: SelectedTrailerCaptionTrack): String {
        val cleaned = selected.baseUrl
            .replace(Regex("&format=[^&]*"), "")
            .replace(Regex("&fmt=[^&]*"), "")
            .replace(Regex("&tlang=[^&]*"), "")
        val separator = if (cleaned.contains('?')) "&" else "?"
        val builder = StringBuilder(cleaned)
        builder.append(separator).append("format=3&fmt=3")
        selected.translateTo
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.append("&tlang=").append(it) }
        return builder.toString()
    }

    private suspend fun fetchSrv3(url: String): String? = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", YOUTUBE_STABLE_WEB_USER_AGENT)
            setRequestProperty("Referer", "https://www.youtube.com/")
            setRequestProperty("Origin", "https://www.youtube.com")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        try {
            if (conn.responseCode != 200) return@withContext null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
