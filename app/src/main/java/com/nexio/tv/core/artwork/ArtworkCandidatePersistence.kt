package com.nexio.tv.core.artwork

fun ArtworkCandidate.toPersistedCandidate(
    policyVersion: Int,
    remoteSourceStore: ArtworkRemoteSourceStore = NoopArtworkRemoteSourceStore
): PersistedArtworkCandidate {
    val candidateSource = source
    if (candidateSource is ArtworkSource.RemoteUrl) {
        // Register the URL->hash mapping unconditionally for RemoteUrl candidates.
        // FileBackedArtworkRemoteSourceStore.put drops URLs that contain embedded
        // credentials (api.ratingposterdb.com, api.top-posters.com) via
        // isPremiumProviderRawUrl(), so the previous sourceRole != PREMIUM guard
        // here was redundant — and it blocked clean PREMIUM URLs from providers
        // like Fanart.tv from ever reaching the renderer, causing assetKey->URL
        // lookups to fail at paint time and the rejected primary URL to surface.
        remoteSourceStore.put(candidateSource.normalizedUrlHash, candidateSource.rawUrl)
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
