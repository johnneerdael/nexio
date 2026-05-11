package com.nexio.tv.data.trailer.cipher

import com.nexio.tv.data.trailer.YOUTUBE_STABLE_WEB_USER_AGENT
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Fetches and caches the YouTube player JS for cipher extraction.
 * The actual JS source is ~3 MB; we keep the parsed [CipherManifest]
 * in memory keyed by the player JS URL. When YouTube rotates the
 * player, a new URL appears in the watch page and the cache naturally
 * invalidates.
 */
@Singleton
class PlayerSourceCache @Inject constructor() {

    private val mutex = Mutex()
    private var cachedUrl: String? = null
    private var cachedManifest: CipherManifest? = null

    /**
     * Extract the player JS URL from a watch-page HTML response.
     * Returns null if the URL can't be located (malformed HTML or
     * an unrecognized YouTube response shape).
     */
    fun extractPlayerJsUrl(watchPageHtml: String): String? {
        val patterns = listOf(
            Regex(""""jsUrl":"(/s/player/[^"]+/base\.js)""""),
            Regex("""<script\s+src="(/s/player/[^"]+/base\.js)""""),
            Regex(""""PLAYER_JS_URL":"(/s/player/[^"]+/base\.js)"""")
        )
        for (pattern in patterns) {
            pattern.find(watchPageHtml)?.let { m ->
                val path = m.groupValues[1].replace("\\/", "/")
                return "https://www.youtube.com$path"
            }
        }
        return null
    }

    /**
     * Returns the parsed [CipherManifest] for the given player JS URL.
     * Fetches the JS on cache miss, parses it, and stores the result.
     * Returns null on network or parse failure.
     */
    suspend fun getCipherManifest(playerJsUrl: String): CipherManifest? = mutex.withLock {
        if (cachedUrl == playerJsUrl && cachedManifest != null) {
            return@withLock cachedManifest
        }

        val playerJs = fetchPlayerJs(playerJsUrl) ?: return@withLock null
        val manifest = PlayerSourceParser.parse(playerJs)
        cachedUrl = playerJsUrl
        cachedManifest = manifest
        manifest
    }

    private suspend fun fetchPlayerJs(playerJsUrl: String): String? = withContext(Dispatchers.IO) {
        val conn = (URL(playerJsUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", YOUTUBE_STABLE_WEB_USER_AGENT)
            setRequestProperty("Referer", "https://www.youtube.com/")
            setRequestProperty("Accept", "*/*")
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
