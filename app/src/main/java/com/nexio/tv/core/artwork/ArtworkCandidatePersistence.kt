package com.nexio.tv.core.artwork

fun ArtworkCandidate.toPersistedCandidate(policyVersion: Int): PersistedArtworkCandidate =
    PersistedArtworkCandidate(
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
