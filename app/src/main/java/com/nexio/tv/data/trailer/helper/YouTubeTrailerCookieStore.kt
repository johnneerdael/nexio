package com.nexio.tv.data.trailer.helper

import android.webkit.CookieManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CookieRecord(
    val domain: String,
    val path: String,
    val name: String,
    val value: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val expiresAtEpochSeconds: Long? = null,
    val includeSubdomains: Boolean = true
)

data class YouTubeTrailerCookieSnapshot(
    val cookieHeader: String,
    val refreshedAtEpochMs: Long,
    val cookieCount: Int
)

private data class CookieExportTarget(
    val url: String,
    val domain: String,
    val secure: Boolean
)

private val cookieExportTargets = listOf(
    CookieExportTarget(
        url = "https://www.youtube.com",
        domain = ".youtube.com",
        secure = true
    ),
    CookieExportTarget(
        url = "https://m.youtube.com",
        domain = ".youtube.com",
        secure = true
    ),
    CookieExportTarget(
        url = "https://music.youtube.com",
        domain = ".youtube.com",
        secure = true
    ),
    CookieExportTarget(
        url = "https://accounts.google.com",
        domain = ".google.com",
        secure = true
    ),
    CookieExportTarget(
        url = "https://www.google.com",
        domain = ".google.com",
        secure = true
    )
)

private val youtubeAuthCookieNames = setOf(
    "SID",
    "HSID",
    "SSID",
    "SAPISID",
    "APISID",
    "__Secure-1PSID",
    "__Secure-3PSID",
    "LOGIN_INFO",
    "VISITOR_INFO1_LIVE",
    "PREF"
)

fun parseCookieHeader(
    header: String,
    domain: String,
    path: String = "/",
    secure: Boolean = true,
    httpOnly: Boolean = true
): List<CookieRecord> {
    return header.split(";")
        .mapNotNull { rawPart ->
            val part = rawPart.trim()
            if (part.isBlank()) return@mapNotNull null
            val separatorIndex = part.indexOf('=')
            if (separatorIndex <= 0 || separatorIndex == part.lastIndex) return@mapNotNull null
            val name = part.substring(0, separatorIndex).trim()
            val value = part.substring(separatorIndex + 1).trim()
            if (name.isBlank() || value.isBlank()) return@mapNotNull null
            CookieRecord(
                domain = domain,
                path = path,
                name = name,
                value = value,
                secure = secure,
                httpOnly = httpOnly
            )
        }
}

@Singleton
class YouTubeTrailerCookieStore @Inject constructor() {
    private val cookieManager: CookieManager
        get() = CookieManager.getInstance()

    fun hasAuthenticatedYouTubeCookies(): Boolean {
        return collectCookieRecords().any { it.name in youtubeAuthCookieNames }
    }

    suspend fun snapshotCurrentSession(): YouTubeTrailerCookieSnapshot? = withContext(Dispatchers.IO) {
        cookieManager.flush()
        val youtubeCookieHeader = cookieManager.getCookie("https://www.youtube.com").orEmpty().trim()
        val records = collectCookieRecords()
            .distinctBy { Triple(it.domain, it.path, it.name) }
        if (youtubeCookieHeader.isBlank() || records.none { it.name in youtubeAuthCookieNames }) {
            return@withContext null
        }
        YouTubeTrailerCookieSnapshot(
            cookieHeader = youtubeCookieHeader,
            refreshedAtEpochMs = System.currentTimeMillis(),
            cookieCount = records.size
        )
    }

    suspend fun currentYouTubeCookieHeader(): String? = withContext(Dispatchers.IO) {
        cookieManager.flush()
        cookieManager.getCookie("https://www.youtube.com")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun clearSession() = withContext(Dispatchers.IO) {
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
    }

    private fun collectCookieRecords(): List<CookieRecord> {
        return cookieExportTargets.flatMap { target ->
            val header = cookieManager.getCookie(target.url).orEmpty()
            parseCookieHeader(
                header = header,
                domain = target.domain,
                secure = target.secure
            )
        }
    }
}
