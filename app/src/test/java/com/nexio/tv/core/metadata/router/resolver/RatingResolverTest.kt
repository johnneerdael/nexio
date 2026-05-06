package com.nexio.tv.core.metadata.router.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RatingResolverTest {
    @Test
    fun `title resolver applies configured source precedence`() {
        val resolved = RatingResolver.resolveTitleRating(
            listOf(
                RatingCandidate(
                    value = 7.1,
                    sourceRole = SourceRole.PREVIEW_FALLBACK,
                    sourceProvider = "TMDB",
                    confidence = Confidence.LOW
                ),
                RatingCandidate(
                    value = 8.2,
                    sourceRole = SourceRole.PRIMARY_PROVIDER,
                    sourceProvider = "TMDB",
                    confidence = Confidence.MEDIUM
                ),
                RatingCandidate(
                    value = 8.6,
                    sourceRole = SourceRole.OMDB,
                    sourceProvider = "OMDB",
                    confidence = Confidence.HIGH
                ),
                RatingCandidate(
                    value = 8.8,
                    sourceRole = SourceRole.MDBLIST,
                    sourceProvider = "MDBLIST",
                    confidence = Confidence.HIGH
                ),
                RatingCandidate(
                    value = 9.2,
                    sourceRole = SourceRole.CUSTOM_IMDB,
                    sourceProvider = "IMDB",
                    confidence = Confidence.HIGH
                )
            )
        )

        assertEquals(9.2, resolved?.value ?: 0.0, 0.0)
        assertEquals(SourceRole.CUSTOM_IMDB, resolved?.sourceRole)
        assertEquals("IMDB", resolved?.sourceProvider)
    }

    @Test
    fun `title resolver falls back through mdblist omdb primary provider and preview`() {
        assertEquals(
            SourceRole.MDBLIST,
            RatingResolver.resolveTitleRating(
                listOf(
                    titleCandidate(SourceRole.PREVIEW_FALLBACK, 7.1),
                    titleCandidate(SourceRole.PRIMARY_PROVIDER, 8.2),
                    titleCandidate(SourceRole.OMDB, 8.6),
                    titleCandidate(SourceRole.MDBLIST, 8.8)
                )
            )?.sourceRole
        )
        assertEquals(
            SourceRole.OMDB,
            RatingResolver.resolveTitleRating(
                listOf(
                    titleCandidate(SourceRole.PREVIEW_FALLBACK, 7.1),
                    titleCandidate(SourceRole.PRIMARY_PROVIDER, 8.2),
                    titleCandidate(SourceRole.OMDB, 8.6)
                )
            )?.sourceRole
        )
        assertEquals(
            SourceRole.PRIMARY_PROVIDER,
            RatingResolver.resolveTitleRating(
                listOf(
                    titleCandidate(SourceRole.PREVIEW_FALLBACK, 7.1),
                    titleCandidate(SourceRole.PRIMARY_PROVIDER, 8.2)
                )
            )?.sourceRole
        )
        assertEquals(
            SourceRole.PREVIEW_FALLBACK,
            RatingResolver.resolveTitleRating(listOf(titleCandidate(SourceRole.PREVIEW_FALLBACK, 7.1)))?.sourceRole
        )
    }

    @Test
    fun `episode resolver chooses per episode by custom omdb provider preview precedence`() {
        val resolved = RatingResolver.resolveEpisodeRatings(
            listOf(
                episodeCandidate(1, 1, SourceRole.PRIMARY_PROVIDER, "TMDB", 7.1),
                episodeCandidate(1, 1, SourceRole.OMDB, "OMDB", 8.1),
                episodeCandidate(1, 2, SourceRole.PRIMARY_PROVIDER, "TMDB", 7.2),
                episodeCandidate(1, 2, SourceRole.PREVIEW_FALLBACK, "TMDB", 6.9),
                episodeCandidate(1, 3, SourceRole.PREVIEW_FALLBACK, "TMDB", 6.8),
                episodeCandidate(1, 4, SourceRole.CUSTOM_IMDB, "IMDB", 9.1),
                episodeCandidate(1, 4, SourceRole.OMDB, "OMDB", 8.4)
            )
        )

        assertEquals(SourceRole.OMDB, resolved[1 to 1]?.sourceRole)
        assertEquals(8.1, resolved[1 to 1]?.value ?: 0.0, 0.0)
        assertEquals(SourceRole.PRIMARY_PROVIDER, resolved[1 to 2]?.sourceRole)
        assertEquals(SourceRole.PREVIEW_FALLBACK, resolved[1 to 3]?.sourceRole)
        assertEquals(SourceRole.CUSTOM_IMDB, resolved[1 to 4]?.sourceRole)
    }

    @Test
    fun `resolver ignores non positive and unknown scoped values`() {
        assertNull(
            RatingResolver.resolveTitleRating(
                listOf(titleCandidate(SourceRole.CUSTOM_IMDB, 0.0))
            )
        )
        assertEquals(
            emptyMap<Pair<Int, Int>, RatingResolution>(),
            RatingResolver.resolveEpisodeRatings(
                listOf(episodeCandidate(1, 1, SourceRole.OMDB, "OMDB", 0.0))
            )
        )
    }

    private fun titleCandidate(sourceRole: SourceRole, value: Double): RatingCandidate =
        RatingCandidate(
            value = value,
            sourceRole = sourceRole,
            sourceProvider = sourceRole.name,
            confidence = Confidence.HIGH
        )

    private fun episodeCandidate(
        season: Int,
        episode: Int,
        sourceRole: SourceRole,
        sourceProvider: String,
        value: Double
    ): EpisodeRatingCandidate =
        EpisodeRatingCandidate(
            seasonNumber = season,
            episodeNumber = episode,
            value = value,
            sourceRole = sourceRole,
            sourceProvider = sourceProvider,
            confidence = Confidence.HIGH
        )
}
