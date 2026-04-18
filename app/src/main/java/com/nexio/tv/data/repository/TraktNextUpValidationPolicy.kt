package com.nexio.tv.data.repository

internal data class TraktNextUpValidationCandidate(
    val contentId: String,
    val activityAtMs: Long,
    val visibleRank: Int,
    val weakDerivation: Boolean,
    val mutationAffected: Boolean,
    val staleValidation: Boolean = false
)

internal data class TraktNextUpValidationCacheEntry(
    val updatedAtMs: Long,
    val ttlMs: Long
) {
    fun isFresh(nowMs: Long): Boolean {
        return nowMs - updatedAtMs in 0..ttlMs
    }
}

internal sealed interface TraktNextUpValidationResult {
    data class CurrentAiredNextEpisode(
        val entry: TraktProgressService.NextUpEntry
    ) : TraktNextUpValidationResult

    data object NoCurrentAiredNextEpisode : TraktNextUpValidationResult
    data object Failed : TraktNextUpValidationResult
}

internal object TraktNextUpValidationPolicy {
    fun selectCandidates(
        candidates: List<TraktNextUpValidationCandidate>,
        nowMs: Long,
        cache: Map<String, TraktNextUpValidationCacheEntry>,
        visibleCandidateLimit: Int,
        validationBudget: Int
    ): List<TraktNextUpValidationCandidate> {
        if (validationBudget <= 0) return emptyList()

        return candidates
            .asSequence()
            .filter { candidate ->
                candidate.isHighPriority(visibleCandidateLimit) &&
                    (
                        candidate.mutationAffected ||
                            candidate.staleValidation ||
                            cache[candidate.contentId]?.isFresh(nowMs) != true
                        )
            }
            .sortedWith(
                compareBy<TraktNextUpValidationCandidate> { it.visibleRank.takeIf { rank -> rank > 0 } ?: Int.MAX_VALUE }
                    .thenByDescending { it.activityAtMs }
                    .thenBy { it.contentId }
            )
            .take(validationBudget)
            .toList()
    }

    fun resolvePublishableCandidate(
        localCandidate: TraktProgressService.NextUpEntry,
        validationResult: TraktNextUpValidationResult,
        nowMs: Long,
        weakDerivation: Boolean = false
    ): TraktProgressService.NextUpEntry? {
        return when (validationResult) {
            is TraktNextUpValidationResult.CurrentAiredNextEpisode -> validationResult.entry
            TraktNextUpValidationResult.NoCurrentAiredNextEpisode -> null
            TraktNextUpValidationResult.Failed -> {
                if (weakDerivation) return null
                localCandidate.takeIf {
                    AirDateGate.isAired(
                        firstAiredMs = it.firstAiredMs,
                        tmdbAirDate = it.firstAired,
                        nowMs = nowMs
                    )
                }
            }
        }
    }

    private fun TraktNextUpValidationCandidate.isHighPriority(visibleCandidateLimit: Int): Boolean {
        return visibleRank in 1..visibleCandidateLimit ||
            weakDerivation ||
            mutationAffected ||
            staleValidation
    }
}
