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
import com.nexio.tv.core.artwork.fanarttv.FanartTvCandidateGenerator
import com.nexio.tv.core.artwork.toPersistedCandidate
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.domain.model.ArtworkProviderSettings
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataArtworkDecisionResolver @Inject constructor(
    private val artworkRouter: ArtworkRouter,
    private val artworkDecisionCache: ArtworkDecisionCache,
    private val remoteSourceStore: ArtworkRemoteSourceStore,
    private val settingsSource: ArtworkProviderSettingsSource,
    private val fanartGenerator: FanartTvCandidateGenerator
) {
    suspend fun resolveFields(
        candidates: List<ArtworkCandidate>
    ): Map<ResolvedField, FieldValue> {
        if (candidates.isEmpty()) return emptyMap()

        val settings = settingsSource.settings.first()
        val withFanart = augmentWithFanart(candidates, settings)
        val policy = ArtworkRoutingPolicy(settings = settings)
        return withFanart
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

    private suspend fun augmentWithFanart(
        candidates: List<ArtworkCandidate>,
        settings: ArtworkProviderSettings
    ): List<ArtworkCandidate> {
        // CLAUDE.md rule #4: don't iterate a Map via forEach inside a suspend fun —
        // Map.forEach's EntryIterator gets pinned in the continuation across
        // fanartGenerator.generate(...)'s suspension. Materialize the entries as
        // an indexed list and iterate with an indexed for loop.
        val ownerEntries = candidates.groupBy { it.ownerKey }.entries.toList()
        val additions = mutableListOf<ArtworkCandidate>()
        for (i in ownerEntries.indices) {
            val entry = ownerEntries[i]
            val ownerKey = entry.key
            val perOwnerCandidates = entry.value
            val sample = perOwnerCandidates.first()
            val requestedTypes = perOwnerCandidates
                .map { it.imageType }
                .filter { it != ArtworkType.THUMBNAIL }
                .toSet()
            if (requestedTypes.isEmpty()) continue
            additions += fanartGenerator.generate(
                ownerKey = ownerKey,
                canonicalContentId = sample.canonicalContentId,
                mediaKind = sample.mediaKind,
                providerIds = sample.providerIds,
                requestedTypes = requestedTypes,
                settings = settings
            )
        }
        return candidates + additions
    }

    private fun ArtworkCandidate.assetKeyForRuntimeRef(policyVersion: Int) =
        when (val candidateSource = source) {
            is ArtworkSource.ProviderTemplate ->
                null
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
