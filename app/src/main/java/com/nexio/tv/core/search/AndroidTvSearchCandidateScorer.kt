package com.nexio.tv.core.search

object AndroidTvSearchCandidateScorer {
    private const val EXACT_SCORE = 1_000
    private const val PREFIX_SCORE = 700
    private const val CONTAINS_SCORE = 350
    private const val LOCAL_SOURCE_SCORE = 90
    private const val YEAR_SCORE = 70
    private const val DURATION_SCORE = 90
    private const val ROUTEABLE_SCORE = 50
    private const val DESCRIPTION_SCORE = 20
    private const val ARTWORK_SCORE = 20
    private const val MIN_CONTAINS_QUERY_LENGTH = 4

    private val whitespacePattern = Regex("""\s+""")
    private val punctuationPattern = Regex("""[^\p{L}\p{N}]+""")

    fun score(
        query: String,
        candidates: List<AndroidTvSearchCandidate>,
        limit: Int
    ): List<AndroidTvSearchScoredCandidate> {
        val normalizedQuery = normalizeTitle(query)
        if (normalizedQuery.isBlank()) return emptyList()

        return candidates
            .mapNotNull { candidate -> scoreCandidate(normalizedQuery, candidate) }
            .distinctBy { "${it.candidate.identityKey}:${it.candidate.source}" }
            .sortedWith(
                compareByDescending<AndroidTvSearchScoredCandidate> { it.score }
                    .thenBy { it.candidate.title.lowercase() }
                    .thenBy { it.candidate.identityKey }
            )
            .dedupeByIdentity()
            .take(limit.coerceAtLeast(1))
    }

    fun isStrongLocal(scored: AndroidTvSearchScoredCandidate): Boolean {
        return scored.candidate.source == AndroidTvSearchCandidateSource.LOCAL_HOME &&
            scored.titleMatch != AndroidTvSearchTitleMatch.CONTAINS
    }

    fun normalizeTitle(value: String): String {
        val normalized = punctuationPattern
            .replace(value.trim().lowercase(), " ")
            .replace(whitespacePattern, " ")
            .trim()
        return normalized
            .removePrefix("the ")
            .removePrefix("a ")
            .removePrefix("an ")
            .trim()
    }

    private fun scoreCandidate(
        normalizedQuery: String,
        candidate: AndroidTvSearchCandidate
    ): AndroidTvSearchScoredCandidate? {
        val normalizedTitle = normalizeTitle(candidate.title)
        if (normalizedTitle.isBlank()) return null

        val titleMatch = when {
            normalizedTitle == normalizedQuery -> AndroidTvSearchTitleMatch.EXACT
            normalizedTitle.startsWith(normalizedQuery) -> AndroidTvSearchTitleMatch.PREFIX
            normalizedQuery.length >= MIN_CONTAINS_QUERY_LENGTH && normalizedTitle.contains(normalizedQuery) ->
                AndroidTvSearchTitleMatch.CONTAINS
            else -> return null
        }

        val hasYear = AndroidTvSearchSuggestionMapper.parseProductionYear(candidate.releaseInfo) != null
        val hasDuration = AndroidTvSearchSuggestionMapper.parseDurationMs(candidate.runtime) != null
        val hasRoute = !candidate.addonBaseUrl.isNullOrBlank()
        val hasDescription = !candidate.description.isNullOrBlank()
        val hasArtwork = !candidate.poster.isNullOrBlank() || !candidate.background.isNullOrBlank()

        var score = when (titleMatch) {
            AndroidTvSearchTitleMatch.EXACT -> EXACT_SCORE
            AndroidTvSearchTitleMatch.PREFIX -> PREFIX_SCORE
            AndroidTvSearchTitleMatch.CONTAINS -> CONTAINS_SCORE
        }
        if (candidate.source == AndroidTvSearchCandidateSource.LOCAL_HOME) score += LOCAL_SOURCE_SCORE
        if (hasYear) score += YEAR_SCORE
        if (hasDuration) score += DURATION_SCORE
        if (hasRoute) score += ROUTEABLE_SCORE
        if (hasDescription) score += DESCRIPTION_SCORE
        if (hasArtwork) score += ARTWORK_SCORE

        return AndroidTvSearchScoredCandidate(
            candidate = candidate,
            titleMatch = titleMatch,
            score = score,
            hasProductionYear = hasYear,
            hasDuration = hasDuration,
            explanation = buildString {
                append("source=").append(candidate.source.name.lowercase())
                append(" title_match=").append(titleMatch.name.lowercase())
                append(" year=").append(hasYear)
                append(" duration=").append(hasDuration)
                append(" routeable=").append(hasRoute)
                append(" artwork=").append(hasArtwork)
            }
        )
    }

    private fun List<AndroidTvSearchScoredCandidate>.dedupeByIdentity(): List<AndroidTvSearchScoredCandidate> {
        val bestByIdentity = linkedMapOf<String, AndroidTvSearchScoredCandidate>()
        forEach { candidate ->
            bestByIdentity.merge(candidate.candidate.identityKey, candidate) { existing, incoming ->
                if (incoming.score > existing.score) incoming else existing
            }
        }
        return bestByIdentity.values.toList()
    }
}
