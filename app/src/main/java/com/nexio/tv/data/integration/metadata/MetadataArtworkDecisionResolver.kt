package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.artwork.ArtworkCacheKeys
import com.nexio.tv.core.artwork.ArtworkCandidate
import com.nexio.tv.core.artwork.ArtworkDecision
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDecisionPolicy
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import com.nexio.tv.core.artwork.ArtworkRemoteSourceStore
import com.nexio.tv.core.artwork.ArtworkRoutingPolicy
import com.nexio.tv.core.artwork.ArtworkRouter
import com.nexio.tv.core.artwork.ArtworkSource
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.toPersistedCandidate
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.SourceRole
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataArtworkDecisionResolver @Inject constructor(
    private val artworkRouter: ArtworkRouter,
    private val artworkDecisionCache: ArtworkDecisionCache,
    private val remoteSourceStore: ArtworkRemoteSourceStore,
    private val settingsSource: ArtworkProviderSettingsSource
) {
    suspend fun resolveFields(
        candidates: List<ArtworkCandidate>
    ): Map<ResolvedField, FieldValue> {
        if (candidates.isEmpty()) return emptyMap()

        val settings = settingsSource.settings.first()
        val policy = ArtworkRoutingPolicy(settings = settings)
        return candidates
            .groupBy { candidate -> candidate.imageType }
            .mapNotNull { (imageType, imageCandidates) ->
                val resolvedField = imageType.toResolvedField() ?: return@mapNotNull null
                val selection = artworkRouter.select(
                    candidates = imageCandidates,
                    policy = policy
                )
                val selected = selection.selectedCandidateOrNull ?: return@mapNotNull null
                val settingsHash = ArtworkDecisionPolicy.settingsHash(settings, imageType)
                val credentialHash = ArtworkDecisionPolicy.credentialHash(settings, imageType)
                val decisionKey = ArtworkCacheKeys.decisionKey(
                    ownerKey = selected.ownerKey,
                    imageType = imageType,
                    provider = selected.provider,
                    premiumEnabled = ArtworkDecisionPolicy.premiumEnabled(settings, imageType),
                    settingsHash = settingsHash,
                    credentialHash = credentialHash,
                    policyVersion = policy.policyVersion
                )
                val persistedSelected = selected.toPersistedCandidate(
                    policyVersion = policy.policyVersion,
                    remoteSourceStore = remoteSourceStore
                )
                val now = System.currentTimeMillis()
                artworkDecisionCache.put(
                    ArtworkDecision(
                        decisionKey = decisionKey,
                        ownerKey = selected.ownerKey,
                        canonicalContentId = selected.canonicalContentId,
                        imageType = imageType,
                        selectedCandidate = persistedSelected,
                        rejectedCandidates = selection.rejectedCandidates,
                        policyVersion = policy.policyVersion,
                        imageLanguage = selected.imageLanguage,
                        settingsHash = settingsHash,
                        credentialHash = credentialHash,
                        createdAtMs = now,
                        expiresAtMs = now + ArtworkDecisionPolicy.DECISION_TTL_MS,
                        staleUntilMs = now + ArtworkDecisionPolicy.DECISION_STALE_TTL_MS
                    )
                )

                resolvedField to FieldValue(
                    value = ArtworkDisplayRef.RuntimeAsset(
                        decisionKey = decisionKey,
                        assetKey = selected.assetKeyForRuntimeRef(policy.policyVersion),
                        imageType = imageType,
                        selectedProvider = selected.provider,
                        sourceRole = selected.sourceRole,
                        trace = ArtworkTrace(
                            selectedProvider = selected.provider?.key,
                            sourceRole = selected.sourceRole.name,
                            reason = selected.trace.reason,
                            rejectedCandidates = selection.rejectedCandidates
                        ),
                        displayHints = ArtworkDisplayHints()
                    ),
                    owner = FieldOwner.ARTWORK,
                    sourceRole = SourceRole.ARTWORK
                )
            }
            .toMap()
    }

    private fun ArtworkCandidate.assetKeyForRuntimeRef(policyVersion: Int) =
        when (val candidateSource = source) {
            is ArtworkSource.ProviderTemplate ->
                toPersistedCandidate(
                    policyVersion = policyVersion,
                    remoteSourceStore = remoteSourceStore
                ).providerTemplate
                    ?.let(ArtworkCacheKeys::assetKeyForProviderTemplate)
            is ArtworkSource.RemoteUrl ->
                provider?.let { selectedProvider ->
                    ArtworkCacheKeys.assetKeyForRemoteUrl(
                        provider = selectedProvider,
                        imageType = imageType,
                        normalizedUrlHash = candidateSource.normalizedUrlHash,
                        variant = null,
                        policyVersion = policyVersion
                    )
                }
            is ArtworkSource.LocalAsset -> candidateSource.assetKey
            else -> null
        }

    private fun ArtworkType.toResolvedField(): ResolvedField? =
        when (this) {
            ArtworkType.POSTER -> ResolvedField.POSTER
            ArtworkType.BACKDROP -> ResolvedField.BACKDROP
            ArtworkType.LOGO -> ResolvedField.LOGO
            ArtworkType.THUMBNAIL -> null
        }
}
