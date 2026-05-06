package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.ArtworkApiShapes
import com.nexio.tv.core.integration.IntegrationProvider

class ArtworkSourceMaterializer(
    private val remoteSourcesByHash: Map<String, SensitiveArtworkUrl>,
    private val remoteSourceStore: ArtworkRemoteSourceStore = NoopArtworkRemoteSourceStore
) {
    fun materialize(decision: ArtworkDecision): MaterializedArtworkSource? {
        val candidate = decision.selectedCandidate
        val template = candidate.providerTemplate
        if (template != null) {
            return MaterializedArtworkSource(
                source = ArtworkSource.ProviderTemplate(
                    provider = template.provider,
                    idType = template.idType,
                    mediaId = template.mediaId,
                    providerPathHash = template.providerPathHash,
                    settingsHash = template.settingsHash,
                    credentialHash = template.credentialHash,
                    pathParams = template.pathParams
                ),
                assetKey = ArtworkCacheKeys.assetKeyForProviderTemplate(template),
                provider = template.provider,
                runtimeProvider = template.provider.runtimeProvider(),
                apiShapeId = template.provider.providerTemplateApiShapeId(template.imageType),
                sourceHash = candidate.sourceHash ?: template.providerPathHash ?: template.mediaId
            )
        }

        val sourceHash = candidate.sourceHash ?: return null
        val provider = candidate.provider ?: ArtworkProviderId.AddonPreview
        val rawSource = remoteSourcesByHash[sourceHash] ?: remoteSourceStore.get(sourceHash)
        return MaterializedArtworkSource(
            source = if (rawSource != null) {
                ArtworkSource.RemoteUrl.of(rawSource, sourceHash)
            } else {
                UnavailableRemoteArtworkSource(
                    normalizedUrlHash = sourceHash,
                    redactedUrlForTrace = candidate.redactedSourceForTrace
                )
            },
            assetKey = ArtworkCacheKeys.assetKeyForRemoteUrl(
                provider = provider,
                imageType = decision.imageType,
                normalizedUrlHash = sourceHash,
                variant = null,
                policyVersion = decision.policyVersion
            ),
            provider = provider,
            runtimeProvider = provider.runtimeProvider(),
            apiShapeId = provider.remoteImageApiShapeId(),
            sourceHash = sourceHash
        )
    }

    private fun ArtworkProviderId.runtimeProvider(): IntegrationProvider =
        when (this) {
            is ArtworkProviderId.RuntimeProvider -> providerId
            ArtworkProviderId.RailPreview -> IntegrationProvider.ADDON
            ArtworkProviderId.AddonPreview -> IntegrationProvider.ADDON
            ArtworkProviderId.Placeholder -> IntegrationProvider.ADDON
        }

    private fun ArtworkProviderId.providerTemplateApiShapeId(imageType: ArtworkType): String =
        when ((this as? ArtworkProviderId.RuntimeProvider)?.providerId) {
            IntegrationProvider.RPDB -> ArtworkApiShapes.RPDB_POSTER_TEMPLATE
            IntegrationProvider.TOP_POSTERS -> when (imageType) {
                ArtworkType.THUMBNAIL -> ArtworkApiShapes.TOP_POSTERS_THUMBNAIL
                else -> ArtworkApiShapes.TOP_POSTERS_POSTER_TEMPLATE
            }
            else -> ArtworkApiShapes.GENERIC_IMAGE_FETCH
        }

    private fun ArtworkProviderId.remoteImageApiShapeId(): String =
        when (this) {
            ArtworkProviderId.RailPreview -> ArtworkApiShapes.RAIL_PREVIEW_IMAGE_FETCH
            ArtworkProviderId.AddonPreview -> ArtworkApiShapes.ADDON_PREVIEW_IMAGE_FETCH
            else -> ArtworkApiShapes.GENERIC_IMAGE_FETCH
        }
}

data class MaterializedArtworkSource(
    val source: ArtworkSource,
    val assetKey: ArtworkAssetKey,
    val provider: ArtworkProviderId?,
    val runtimeProvider: IntegrationProvider,
    val apiShapeId: String,
    val sourceHash: String
)

data class UnavailableRemoteArtworkSource(
    val normalizedUrlHash: String,
    val redactedUrlForTrace: String?
) : ArtworkSource {
    init {
        require(normalizedUrlHash.isNotBlank()) {
            "UnavailableRemoteArtworkSource normalizedUrlHash must not be blank"
        }
    }
}
