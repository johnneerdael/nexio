package com.nexio.tv.data.repository.benchmark

import android.content.Context
import android.provider.Settings
import com.nexio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

internal const val PUBLIC_DASHBOARD_ROUTE = "public"
private const val SHA3_HASH_ALGORITHM = "SHA3-512"
private const val SHA512_HASH_ALGORITHM = "SHA-512"

internal sealed class CollectorPublicDashboardLinkResult {
    data class Available(val token: String, val url: String) : CollectorPublicDashboardLinkResult()
    data class Unavailable(val reason: Reason) : CollectorPublicDashboardLinkResult()

    internal enum class Reason {
        BASE_URL_MISSING,
        ANDROID_ID_MISSING,
        HASHING_FAILED
    }
}

@Singleton
internal class CollectorPublicDashboardLinkProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    internal fun resolvePublicDashboardLink(): CollectorPublicDashboardLinkResult {
        val baseUrl = BuildConfig.SHADOW_DATA_COLLECTION_BASE_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return CollectorPublicDashboardLinkResult.Unavailable(
                CollectorPublicDashboardLinkResult.Reason.BASE_URL_MISSING
            )
        }

        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().trimOrNull()
            ?: return CollectorPublicDashboardLinkResult.Unavailable(
                CollectorPublicDashboardLinkResult.Reason.ANDROID_ID_MISSING
            )

        val token = hashAndroidId(androidId)
        if (token.isBlank()) {
            return CollectorPublicDashboardLinkResult.Unavailable(
                CollectorPublicDashboardLinkResult.Reason.HASHING_FAILED
            )
        }

        return CollectorPublicDashboardLinkResult.Available(
            token = token,
            url = "$baseUrl/$PUBLIC_DASHBOARD_ROUTE/$token"
        )
    }

    companion object {
        private fun deriveToken(androidId: String, algorithm: String): String? {
            return runCatching {
                MessageDigest.getInstance(algorithm)
                    .digest(androidId.toByteArray(Charsets.UTF_8))
            }.map { digest ->
                Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
            }.getOrNull()
        }

        fun hashAndroidId(androidId: String): String {
            return deriveToken(androidId, SHA3_HASH_ALGORITHM)
                ?: deriveToken(androidId, SHA512_HASH_ALGORITHM)
                ?: ""
        }
    }
}

private fun String?.trimOrNull(): String? {
    if (this == null) return null
    val trimmed = trim()
    return trimmed.ifBlank { null }
}
