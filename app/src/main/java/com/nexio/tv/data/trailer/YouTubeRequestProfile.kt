package com.nexio.tv.data.trailer

const val YOUTUBE_STABLE_WEB_USER_AGENT: String =
    "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
const val YOUTUBE_STABLE_ACCEPT_LANGUAGE: String = "en-US,en;q=0.9"
const val YOUTUBE_STABLE_ORIGIN: String = "https://www.youtube.com"
const val YOUTUBE_STABLE_REFERER: String = "https://www.youtube.com/"

fun buildStableYouTubeRequestHeaders(
    cookieHeader: String? = null
): Map<String, String> {
    return buildMap {
        put("accept-language", YOUTUBE_STABLE_ACCEPT_LANGUAGE)
        put("user-agent", YOUTUBE_STABLE_WEB_USER_AGENT)
        put("origin", YOUTUBE_STABLE_ORIGIN)
        put("referer", YOUTUBE_STABLE_REFERER)
        cookieHeader
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { put("Cookie", it) }
    }
}
