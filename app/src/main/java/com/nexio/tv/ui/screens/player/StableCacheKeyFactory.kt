package com.nexio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory

@UnstableApi
internal class StableCacheKeyFactory(
    private val contentIdentityProvider: (Uri) -> String? = ::defaultContentIdentityExtractor
) : CacheKeyFactory {

    override fun buildCacheKey(dataSpec: DataSpec): String {
        val uri = dataSpec.uri
        val fileSize = dataSpec.length
        val identity = contentIdentityProvider(uri)
        return if (identity != null && fileSize != C.LENGTH_UNSET.toLong()) {
            "nexio:v1:$identity:$fileSize"
        } else {
            "nexio:v1:url:${normalizedPathKey(uri)}"
        }
    }

    companion object {

        fun defaultContentIdentityExtractor(uri: Uri): String? {
            val host = uri.host?.lowercase() ?: return null
            val pathSegments = uri.pathSegments ?: return null

            return when {
                host.contains("real-debrid.com") -> {
                    // Pattern: /d/{hash}/{filename}
                    val dIndex = pathSegments.indexOf("d")
                    if (dIndex >= 0 && dIndex + 1 < pathSegments.size) {
                        pathSegments[dIndex + 1].takeIf { it.length >= 8 }
                    } else null
                }
                host.contains("alldebrid.com") -> {
                    val dlIndex = pathSegments.indexOf("dl")
                    if (dlIndex >= 0 && dlIndex + 1 < pathSegments.size) {
                        pathSegments[dlIndex + 1].takeIf { it.isNotEmpty() }
                    } else null
                }
                host.contains("premiumize.me") -> {
                    // Look for a hash-like segment (32+ hex chars)
                    pathSegments.firstOrNull { segment ->
                        segment.length >= 32 && segment.all { it.isLetterOrDigit() }
                    }
                }
                host.contains("torbox.app") -> {
                    val dlIndex = pathSegments.indexOf("download")
                    if (dlIndex >= 0 && dlIndex + 1 < pathSegments.size) {
                        pathSegments[dlIndex + 1].takeIf { it.isNotEmpty() }
                    } else null
                }
                else -> null
            }
        }

        fun normalizedPathKey(uri: Uri): String {
            val path = uri.path?.trimEnd('/') ?: return uri.toString()
            return path.ifEmpty { uri.toString() }
        }
    }
}
