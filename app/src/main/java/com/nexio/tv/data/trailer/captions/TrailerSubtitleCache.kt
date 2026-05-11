package com.nexio.tv.data.trailer.captions

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "TrailerSubtitleCache"

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
    @ApplicationContext private val applicationContext: Context,
    private val subtitleTranslationService: com.nexio.tv.data.repository.SubtitleTranslationService,
    private val subtitleTranslationSettingsDataStore: com.nexio.tv.data.local.SubtitleTranslationSettingsDataStore
) {

    private val mutex = Mutex()
    private val baseDir: File by lazy {
        File(applicationContext.cacheDir, "trailer-subtitles").apply { mkdirs() }
    }

    /**
     * Returns a `file://` URI to an SRT file for the given track, fetching
     * and caching on first call. Returns `null` on network or parse failure.
     *
     * When [SelectedTrailerCaptionTrack.translateTo] is set and AI translation
     * is enabled in settings, the source SRT is fetched first (cached at
     * `<hash>-<src>.srt`), then translated and cached at
     * `<hash>-<src>-<tgt>.srt`. The translated URI is returned on success;
     * all failure paths fall back to the source URI so caption rendering is
     * never broken by a translation error.
     */
    suspend fun ensure(selected: SelectedTrailerCaptionTrack): String? =
        mutex.withLock {
            val sourceTarget = sourceCacheFileFor(selected)
            val translatedTarget = translatedCacheFileFor(selected)

            // 1. Translated cache hit?
            if (selected.translateTo != null && translatedTarget != null &&
                translatedTarget.exists() && translatedTarget.length() > 0
            ) {
                return@withLock translatedTarget.toURI().toString()
            }

            // 2. Source cache hit OR fetch + parse + write source.
            val sourceUri = if (sourceTarget.exists() && sourceTarget.length() > 0) {
                sourceTarget.toURI().toString()
            } else {
                val srv3Url = buildSrv3Url(selected.copy(translateTo = null))
                Log.d(TAG, "fetching srv3 url=$srv3Url")
                val xml = fetchSrv3(srv3Url)
                if (xml == null) {
                    Log.d(TAG, "fetch returned null (HTTP failure)")
                    return@withLock null
                }
                Log.d(TAG, "fetch ok bytes=${xml.length} preview=${xml.take(200).replace('\n', ' ')}")
                val lines = SrvCaptionParser.parse(xml)
                if (lines.isEmpty()) {
                    Log.d(TAG, "parse produced zero caption lines")
                    return@withLock null
                }
                Log.d(TAG, "parsed lines=${lines.size}")
                val srt = SrtSerializer.serialize(lines)
                try {
                    sourceTarget.writeText(srt, Charsets.UTF_8)
                } catch (e: IOException) {
                    Log.d(TAG, "source SRT write failed ${e.message}")
                    return@withLock null
                }
                sourceTarget.toURI().toString()
            }

            // 3. No translation requested → return source URI.
            if (selected.translateTo == null || translatedTarget == null) {
                return@withLock sourceUri
            }
            if (selected.translateTo.equals(selected.languageCode, ignoreCase = true)) {
                return@withLock sourceUri
            }

            // 4. AI translation gate.
            val settings = currentTranslationSettings()
            if (settings == null || !settings.enabled || settings.apiKey.isBlank()) {
                Log.d(TAG, "AI translation disabled — serving source SRT")
                return@withLock sourceUri
            }

            // 5. Translate.
            val sourceSrt = try {
                sourceTarget.readText(Charsets.UTF_8)
            } catch (e: IOException) {
                Log.d(TAG, "source SRT read failed ${e.message}")
                return@withLock sourceUri
            }
            val translatedSrt = try {
                subtitleTranslationService.translateSrtAtomically(
                    srt = sourceSrt,
                    sourceLanguageCode = selected.languageCode,
                    targetLanguageCode = selected.translateTo,
                    settings = settings
                )
            } catch (e: Throwable) {
                Log.d(TAG, "translateSrtAtomically threw ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (translatedSrt.isNullOrBlank()) {
                Log.d(TAG, "translation returned null/blank — serving source SRT")
                return@withLock sourceUri
            }
            try {
                translatedTarget.writeText(translatedSrt, Charsets.UTF_8)
            } catch (e: IOException) {
                Log.d(TAG, "translated SRT write failed ${e.message}")
                return@withLock sourceUri
            }
            Log.d(TAG, "translated SRT cached at ${translatedTarget.absolutePath}")
            translatedTarget.toURI().toString()
        }

    /**
     * Cache file path for the source SRT (no translation suffix).
     */
    private fun sourceCacheFileFor(selected: SelectedTrailerCaptionTrack): File {
        return cacheFileFor(selected.copy(translateTo = null))
    }

    /**
     * Cache file path for the translated SRT, or null when no translation
     * is requested.
     */
    private fun translatedCacheFileFor(selected: SelectedTrailerCaptionTrack): File? {
        if (selected.translateTo.isNullOrBlank()) return null
        return cacheFileFor(selected)
    }

    /**
     * Snapshot the current AI translation settings. Returns null when the
     * datastore hasn't emitted yet.
     */
    private suspend fun currentTranslationSettings(): com.nexio.tv.domain.model.SubtitleTranslationSettings? {
        return subtitleTranslationSettingsDataStore.settings.firstOrNull()
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
            // SOCS GDPR-consent cookie — YoutubeHttpHandler.cs:36-41 pre-seeds
            // its cookie container with this value to avoid being treated as a
            // fresh anonymous client (which gets rate-limited harder).
            setRequestProperty("Cookie", "SOCS=CAISEwgDEgk4MTM4MzYzNTIaAmVuIAEaBgiApPzGBg")
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                Log.d(TAG, "fetchSrv3 non-200 code=$code")
                return@withContext null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.d(TAG, "fetchSrv3 IOException ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
