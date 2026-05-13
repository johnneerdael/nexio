package com.nexio.tv.notices

import com.nexio.tv.notices.model.RemoteNoticeManifest
import com.nexio.tv.notices.model.RemoteNoticeManifestItem
import java.time.Instant

internal object RemoteNoticeSelector {
    fun selectNewestEligible(
        manifest: RemoteNoticeManifest,
        now: Instant,
        baselineAt: Instant,
        seenIds: Set<String>,
        appVersion: String
    ): RemoteNoticeManifestItem? {
        if (manifest.schemaVersion != 1) return null

        return manifest.notices
            .asSequence()
            .mapNotNull { item -> item.toCandidateOrNull() }
            .filter { candidate -> !candidate.publishedAt.isAfter(now) }
            .filter { candidate -> candidate.publishedAt.isAfter(baselineAt) }
            .filter { candidate -> candidate.expiresAt == null || candidate.expiresAt.isAfter(now) }
            .filter { candidate -> candidate.item.id !in seenIds }
            .filter { candidate -> candidate.item.minVersion?.let { min -> !isRemoteNewer(min, appVersion) } ?: true }
            .filter { candidate -> candidate.item.maxVersion?.let { max -> !isRemoteNewer(appVersion, max) } ?: true }
            .sortedWith(compareByDescending<RemoteNoticeCandidate> { it.publishedAt }.thenBy { it.item.id })
            .firstOrNull()
            ?.item
    }

    private fun RemoteNoticeManifestItem.toCandidateOrNull(): RemoteNoticeCandidate? {
        val cleanId = id.trim()
        val cleanTitle = title.trim()
        val cleanUrl = markdownUrl.trim()
        if (cleanId.isBlank() || cleanTitle.isBlank() || cleanUrl.isBlank()) return null
        if (!cleanUrl.startsWith("https://", ignoreCase = true)) return null

        val publishedInstant = runCatching { Instant.parse(publishedAt.trim()) }.getOrNull() ?: return null
        val expiresInstant = expiresAt?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { Instant.parse(raw) }.getOrNull() ?: return null
        }

        return RemoteNoticeCandidate(
            item = copy(
                id = cleanId,
                title = cleanTitle,
                publishedAt = publishedAt.trim(),
                markdownUrl = cleanUrl,
                minVersion = minVersion?.trim()?.takeIf { it.isNotBlank() },
                maxVersion = maxVersion?.trim()?.takeIf { it.isNotBlank() },
                expiresAt = expiresAt?.trim()?.takeIf { it.isNotBlank() }
            ),
            publishedAt = publishedInstant,
            expiresAt = expiresInstant
        )
    }

    private fun normalizeVersion(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    private fun parseVersionParts(raw: String?): List<Int>? {
        val normalized = normalizeVersion(raw)
        if (normalized.isBlank()) return null

        val parts = normalized.split('.', '-', '_')
            .filter { it.isNotBlank() }
            .mapNotNull { token -> token.takeWhile { char -> char.isDigit() }.toIntOrNull() }

        return parts.takeIf { it.isNotEmpty() }
    }

    private fun isRemoteNewer(remote: String?, local: String?): Boolean {
        val remoteParts = parseVersionParts(remote)
        val localParts = parseVersionParts(local)

        if (remoteParts == null || localParts == null) {
            val r = normalizeVersion(remote)
            val l = normalizeVersion(local)
            return r.isNotBlank() && l.isNotBlank() && r != l
        }

        val max = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until max) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }
}

private data class RemoteNoticeCandidate(
    val item: RemoteNoticeManifestItem,
    val publishedAt: Instant,
    val expiresAt: Instant?
)
