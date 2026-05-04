package com.nexio.tv.core.artwork

data class ArtworkRoutingPolicy(
    val activePremiumProvider: ArtworkProviderId?
)

data class ArtworkSelectionResult(
    val selectedCandidate: ArtworkCandidate,
    val rejectedCandidates: List<RejectedArtworkCandidate>
)

class ArtworkRouter(
    private val capabilityResolver: ArtworkProviderCapabilityResolver = ArtworkProviderCapabilityResolver()
) {
    fun select(
        candidates: List<ArtworkCandidate>,
        policy: ArtworkRoutingPolicy
    ): ArtworkSelectionResult {
        require(candidates.isNotEmpty()) { "ArtworkRouter requires at least one candidate" }

        val rejectedBeforeSelection = candidates
            .mapNotNull { candidate -> candidate.unsupportedPremiumRejection(policy) }

        val selected = candidates
            .filterNot { it.sourceRole == ArtworkSourceRole.PREMIUM && !it.isActiveSupportedPremium(policy) }
            .minWithOrNull(candidateOrdering(policy))
            ?: throw IllegalArgumentException("ArtworkRouter has no selectable candidate")

        val selectedRank = selected.routingRank(policy)
        val rejectedAfterSelection = candidates
            .filterNot { it === selected }
            .filterNot { candidate ->
                candidate.sourceRole == ArtworkSourceRole.PREMIUM && !candidate.isActiveSupportedPremium(policy)
            }
            .map { candidate ->
                candidate.rejected(candidate.rejectionReasonForSelected(selectedRank))
            }

        return ArtworkSelectionResult(
            selectedCandidate = selected,
            rejectedCandidates = rejectedBeforeSelection + rejectedAfterSelection
        )
    }

    private fun candidateOrdering(policy: ArtworkRoutingPolicy): Comparator<ArtworkCandidate> =
        compareBy<ArtworkCandidate> { it.routingRank(policy).precedence }
            .thenBy { it.priority }
            .thenBy { it.provider?.key.orEmpty() }
            .thenBy { it.sourceRole.name }
            .thenBy { it.canonicalContentId.orEmpty() }

    private fun ArtworkCandidate.routingRank(policy: ArtworkRoutingPolicy): RoutingRank =
        when {
            sourceRole == ArtworkSourceRole.PREMIUM &&
                isActiveSupportedPremium(policy) -> RoutingRank.PREMIUM
            sourceRole == ArtworkSourceRole.PRIMARY -> RoutingRank.PRIMARY
            sourceRole == ArtworkSourceRole.CURRENT_PREVIEW -> RoutingRank.CURRENT_PREVIEW
            sourceRole == ArtworkSourceRole.OTHER_PREVIEW ||
                sourceRole == ArtworkSourceRole.RAIL_PREVIEW ||
                sourceRole == ArtworkSourceRole.ADDON_PREVIEW -> RoutingRank.OTHER_PREVIEW
            sourceRole == ArtworkSourceRole.PLACEHOLDER ||
                provider == ArtworkProviderId.Placeholder ||
                source is ArtworkSource.Placeholder -> RoutingRank.PLACEHOLDER
            else -> RoutingRank.FALLBACK
        }

    private fun ArtworkCandidate.isActiveSupportedPremium(policy: ArtworkRoutingPolicy): Boolean =
        provider != null &&
            provider == policy.activePremiumProvider &&
            provider.evaluatePremiumCandidate(this).supported

    private fun ArtworkCandidate.unsupportedPremiumRejection(policy: ArtworkRoutingPolicy): RejectedArtworkCandidate? {
        if (sourceRole != ArtworkSourceRole.PREMIUM) return null

        val candidateProvider = provider
        if (candidateProvider == null || candidateProvider != policy.activePremiumProvider) {
            return rejected("inactive premium artwork provider")
        }

        val capability = candidateProvider.evaluatePremiumCandidate(this)
        if (capability.supported) return null
        return rejected(capability.reason ?: "unsupported premium artwork provider")
    }

    private fun ArtworkProviderId.evaluatePremiumCandidate(candidate: ArtworkCandidate): ArtworkProviderCapability =
        capabilityResolver.evaluate(
            provider = this,
            imageType = candidate.imageType,
            ids = candidate.providerIds,
            mediaKind = candidate.mediaKind
        )

    private fun ArtworkCandidate.rejectionReasonForSelected(selectedRank: RoutingRank): String =
        when (selectedRank) {
            RoutingRank.PREMIUM -> "premium artwork provider has precedence"
            RoutingRank.PRIMARY -> "primary provider artwork has precedence"
            RoutingRank.CURRENT_PREVIEW -> "current preview artwork has precedence"
            RoutingRank.OTHER_PREVIEW -> "other preview artwork has precedence"
            RoutingRank.FALLBACK -> "fallback artwork has precedence"
            RoutingRank.PLACEHOLDER -> "placeholder artwork has precedence"
        }

    private fun ArtworkCandidate.rejected(reason: String): RejectedArtworkCandidate =
        RejectedArtworkCandidate(
            provider = provider,
            sourceRole = sourceRole,
            reason = reason
        )

    private enum class RoutingRank(val precedence: Int) {
        PREMIUM(0),
        PRIMARY(1),
        CURRENT_PREVIEW(2),
        OTHER_PREVIEW(3),
        FALLBACK(4),
        PLACEHOLDER(5)
    }
}
