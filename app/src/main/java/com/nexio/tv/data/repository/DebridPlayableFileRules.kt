package com.nexio.tv.data.repository

import java.util.Locale

internal val STRICT_PLAYABLE_VIDEO_EXTENSIONS: Set<String> = setOf(
    "3g2",
    "3gp",
    "amv",
    "asf",
    "avi",
    "divx",
    "f4v",
    "flv",
    "m2t",
    "m2ts",
    "m4v",
    "mkv",
    "mod",
    "mov",
    "mp2",
    "mp4",
    "mpe",
    "mpeg",
    "mpg",
    "mpv",
    "mts",
    "mxf",
    "nsv",
    "ogv",
    "qt",
    "rm",
    "roq",
    "svi",
    "ts",
    "vob",
    "webm",
    "wmv"
)

internal fun isStrictPlayableVideoCandidate(
    filename: String?,
    folder: String? = null,
    fullPath: String? = null
): Boolean {
    val normalizedPath = when {
        !fullPath.isNullOrBlank() -> normalizeCandidatePath(fullPath)
        !folder.isNullOrBlank() || !filename.isNullOrBlank() -> {
            normalizeCandidatePath(
                listOfNotNull(folder?.trim('/'), filename?.trim())
                    .filter { it.isNotBlank() }
                    .joinToString("/")
            )
        }
        else -> normalizeCandidatePath(filename)
    }
    if (normalizedPath.isNullOrBlank()) return false
    if (isLikelySampleVideoFile(filename = extractFilenameFromCandidatePath(normalizedPath), folder = extractFolderFromCandidatePath(normalizedPath))) {
        return false
    }
    val extension = normalizedPath.substringAfterLast('.', "").lowercase(Locale.US)
    return extension in STRICT_PLAYABLE_VIDEO_EXTENSIONS
}

internal fun isLikelySampleVideoFile(filename: String?, folder: String? = null): Boolean {
    val normalizedPath = listOfNotNull(folder, filename)
        .joinToString("/")
        .trim()
        .lowercase(Locale.US)
    if (normalizedPath.isBlank()) return false
    val segments = normalizedPath.split('/').filter { it.isNotBlank() }
    if (segments.any { it == "sample" || it == "samples" }) return true
    val extractedFilename = extractFilenameFromCandidatePath(normalizedPath).orEmpty()
    return extractedFilename.startsWith("sample.") ||
        extractedFilename.startsWith("sample-") ||
        extractedFilename.contains(".sample.")
}

internal fun extractFilenameFromCandidatePath(path: String?): String? {
    val normalizedPath = normalizeCandidatePath(path) ?: return null
    return normalizedPath.substringAfterLast('/').takeIf { it.isNotBlank() }
}

private fun extractFolderFromCandidatePath(path: String?): String? {
    val normalizedPath = normalizeCandidatePath(path) ?: return null
    return normalizedPath.substringBeforeLast('/', "").takeIf { it.isNotBlank() }
}

private fun normalizeCandidatePath(path: String?): String? {
    val normalized = path
        ?.substringBefore('?')
        ?.trim()
        ?.replace('\\', '/')
        .orEmpty()
    return normalized.takeIf { it.isNotBlank() }
}
