package com.nexio.tv.core.recommendations

import android.content.Context
import android.net.Uri
import com.nexio.tv.core.util.toHexLowercase
import java.io.File
import java.security.MessageDigest

internal object AndroidTvChannelArtwork {
    private const val AUTHORITY_SUFFIX = ".channelart"
    private const val ROOT_DIR = "android_tv_channel_art"
    private const val POSTERS_DIR = "posters"
    private const val POSTER_PATH = "poster"
    private val FILE_NAME_REGEX = Regex("^[a-f0-9]{64}\\.jpg$")

    fun authority(context: Context): String {
        return "${context.packageName}$AUTHORITY_SUFFIX"
    }

    fun posterUri(context: Context, diskCacheKey: String): Uri {
        return Uri.Builder()
            .scheme("content")
            .authority(authority(context))
            .appendPath(POSTER_PATH)
            .appendPath(fileNameForDiskCacheKey(diskCacheKey))
            .build()
    }

    fun posterFile(context: Context, diskCacheKey: String): File {
        return File(posterDirectory(context), fileNameForDiskCacheKey(diskCacheKey))
    }

    fun posterDirectoryForMaintenance(context: Context): File {
        return posterDirectory(context)
    }

    fun resolvePosterFile(context: Context, uri: Uri): File? {
        if (uri.scheme != "content") return null
        if (uri.authority != authority(context)) return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        if (segments[0] != POSTER_PATH) return null
        val fileName = segments[1]
        if (!FILE_NAME_REGEX.matches(fileName)) return null
        val directory = posterDirectory(context).canonicalFile
        val file = File(directory, fileName).canonicalFile
        return file.takeIf { candidate ->
            candidate.parentFile == directory
        }
    }

    private fun posterDirectory(context: Context): File {
        return File(context.cacheDir, "$ROOT_DIR/$POSTERS_DIR")
    }

    private fun fileNameForDiskCacheKey(diskCacheKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(diskCacheKey.toByteArray(Charsets.UTF_8))
            .toHexLowercase()
        return "$digest.jpg"
    }
}
