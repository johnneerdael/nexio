package com.nexio.tv.core.metadata.router.resolver

enum class RatingScope {
    TITLE,
    EPISODE
}

enum class SourceRole {
    CUSTOM_IMDB,
    MDBLIST,
    OMDB,
    PRIMARY_PROVIDER,
    PREVIEW_FALLBACK
}

enum class Confidence {
    HIGH,
    MEDIUM,
    LOW
}

data class RatingCandidate(
    val value: Double,
    val sourceRole: SourceRole,
    val sourceProvider: String,
    val confidence: Confidence,
    val scope: RatingScope = RatingScope.TITLE
)

data class EpisodeRatingCandidate(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val value: Double,
    val sourceRole: SourceRole,
    val sourceProvider: String,
    val confidence: Confidence,
    val scope: RatingScope = RatingScope.EPISODE
)

data class RatingResolution(
    val value: Double,
    val sourceRole: SourceRole,
    val sourceProvider: String,
    val confidence: Confidence
)

object RatingResolver {
    private val titlePrecedence = listOf(
        SourceRole.CUSTOM_IMDB,
        SourceRole.MDBLIST,
        SourceRole.OMDB,
        SourceRole.PRIMARY_PROVIDER,
        SourceRole.PREVIEW_FALLBACK
    )

    private val episodePrecedence = listOf(
        SourceRole.CUSTOM_IMDB,
        SourceRole.OMDB,
        SourceRole.PRIMARY_PROVIDER,
        SourceRole.PREVIEW_FALLBACK
    )

    fun resolveTitleRating(candidates: List<RatingCandidate>): RatingResolution? =
        candidates
            .asSequence()
            .filter { it.scope == RatingScope.TITLE }
            .filter { it.value > 0.0 }
            .sortedBy { candidate ->
                titlePrecedence.indexOf(candidate.sourceRole).takeIf { it >= 0 } ?: Int.MAX_VALUE
            }
            .firstOrNull { it.sourceRole in titlePrecedence }
            ?.toResolution()

    fun resolveEpisodeRatings(
        candidates: List<EpisodeRatingCandidate>
    ): Map<Pair<Int, Int>, RatingResolution> =
        candidates
            .asSequence()
            .filter { it.scope == RatingScope.EPISODE }
            .filter { it.value > 0.0 }
            .filter { it.seasonNumber > 0 && it.episodeNumber > 0 }
            .filter { it.sourceRole in episodePrecedence }
            .groupBy { it.seasonNumber to it.episodeNumber }
            .mapValues { (_, episodeCandidates) ->
                episodeCandidates.minBy { candidate ->
                    episodePrecedence.indexOf(candidate.sourceRole)
                }.toResolution()
            }

    private fun RatingCandidate.toResolution(): RatingResolution =
        RatingResolution(
            value = value,
            sourceRole = sourceRole,
            sourceProvider = sourceProvider,
            confidence = confidence
        )

    private fun EpisodeRatingCandidate.toResolution(): RatingResolution =
        RatingResolution(
            value = value,
            sourceRole = sourceRole,
            sourceProvider = sourceProvider,
            confidence = confidence
        )
}
