package com.nexio.tv.core.artwork

import java.util.concurrent.ConcurrentHashMap

fun ArtworkCandidate.toPersistedCandidate(policyVersion: Int): PersistedArtworkCandidate {
    val candidateSource = source
    if (candidateSource is ArtworkSource.RemoteUrl && sourceRole != ArtworkSourceRole.PREMIUM) {
        ArtworkRemoteSourceRegistry.put(candidateSource.normalizedUrlHash, candidateSource.rawUrl)
    }

    return PersistedArtworkCandidate(
        provider = provider,
        sourceRole = sourceRole,
        sourceHash = when (val candidateSource = source) {
            is ArtworkSource.RemoteUrl -> candidateSource.normalizedUrlHash
            is ArtworkSource.ProviderTemplate -> candidateSource.providerPathHash
            is ArtworkSource.LocalAsset -> candidateSource.assetKey.value
            is ArtworkSource.Placeholder -> null
            is UnavailableRemoteArtworkSource -> candidateSource.normalizedUrlHash
        },
        redactedSourceForTrace = when (val candidateSource = source) {
            is ArtworkSource.RemoteUrl -> candidateSource.redactedUrlForTrace
            is UnavailableRemoteArtworkSource -> candidateSource.redactedUrlForTrace
            else -> null
        },
        providerTemplate = (source as? ArtworkSource.ProviderTemplate)?.let { template ->
            PersistedProviderTemplate(
                provider = template.provider,
                imageType = imageType,
                idType = template.idType,
                mediaId = template.mediaId,
                providerPathHash = template.providerPathHash,
                settingsHash = template.settingsHash,
                credentialHash = template.credentialHash,
                imageLanguage = imageLanguage,
                policyVersion = policyVersion,
                pathParams = template.pathParams
            )
        },
        priority = priority
    )
}

internal object ArtworkRemoteSourceRegistry {
    private val sourcesByHash = ConcurrentHashMap<String, SensitiveArtworkUrl>()

    fun put(
        normalizedUrlHash: String,
        source: SensitiveArtworkUrl
    ) {
        sourcesByHash[normalizedUrlHash] = source
    }

    fun get(normalizedUrlHash: String): SensitiveArtworkUrl? =
        sourcesByHash[normalizedUrlHash]
}
