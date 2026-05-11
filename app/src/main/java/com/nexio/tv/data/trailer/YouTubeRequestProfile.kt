package com.nexio.tv.data.trailer

const val YOUTUBE_STABLE_WEB_USER_AGENT: String =
    "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
const val YOUTUBE_STABLE_ACCEPT_LANGUAGE: String = "en-US,en;q=0.9"
const val YOUTUBE_STABLE_ORIGIN: String = "https://www.youtube.com"
const val YOUTUBE_STABLE_REFERER: String = "https://www.youtube.com/"

enum class YouTubeRequestProfile { WEB, IOS, ANDROID }

/**
 * Build HTTP request headers matching the wire fingerprint YouTube expects for
 * each client. iOS/Android profiles deliberately exclude `origin`, `referer`,
 * and `accept-language`: those are web-client signals, and YouTube's WAF
 * correlates them against the URL signing — a mismatch on a signed
 * `googlevideo.com` segment fetch can yield 403. iOS/Android profiles also
 * include `X-Goog-Api-Format-Version: 2`, which the native apps always send.
 */
fun buildYouTubeRequestHeaders(
    profile: YouTubeRequestProfile,
    userAgent: String? = null,
    cookieHeader: String? = null
): Map<String, String> = buildMap {
    when (profile) {
        YouTubeRequestProfile.WEB -> {
            put("accept-language", YOUTUBE_STABLE_ACCEPT_LANGUAGE)
            put("user-agent", userAgent?.takeIf { it.isNotBlank() } ?: YOUTUBE_STABLE_WEB_USER_AGENT)
            put("origin", YOUTUBE_STABLE_ORIGIN)
            put("referer", YOUTUBE_STABLE_REFERER)
        }
        YouTubeRequestProfile.IOS,
        YouTubeRequestProfile.ANDROID -> {
            val ua = userAgent?.takeIf { it.isNotBlank() }
                ?: error("userAgent required for $profile profile")
            put("user-agent", ua)
            put("x-goog-api-format-version", "2")
        }
    }
    cookieHeader
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { put("Cookie", it) }
}

/**
 * Backwards-compatible alias for the legacy web-profile header builder.
 * Existing callers outside this package keep working unchanged.
 */
fun buildStableYouTubeRequestHeaders(
    cookieHeader: String? = null
): Map<String, String> = buildYouTubeRequestHeaders(
    profile = YouTubeRequestProfile.WEB,
    cookieHeader = cookieHeader
)
