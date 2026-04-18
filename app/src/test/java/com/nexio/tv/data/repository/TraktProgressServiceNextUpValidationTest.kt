package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class TraktProgressServiceNextUpValidationTest {

    @Test
    fun `selects visible candidates within validation budget`() {
        val candidates = (1..8).map { index ->
            TraktNextUpValidationCandidate(
                contentId = "show-$index",
                activityAtMs = 10_000L - index,
                visibleRank = index,
                weakDerivation = index % 2 == 0,
                mutationAffected = false
            )
        }

        val selected = TraktNextUpValidationPolicy.selectCandidates(
            candidates = candidates,
            nowMs = 20_000L,
            cache = emptyMap(),
            visibleCandidateLimit = 20,
            validationBudget = 3
        )

        assertEquals(
            listOf("show-1", "show-2", "show-3"),
            selected.map { it.contentId }
        )
    }

    @Test
    fun `fresh validation cache skips candidate unless mutation affected`() {
        val candidates = listOf(
            TraktNextUpValidationCandidate(
                contentId = "cached-show",
                activityAtMs = 10_000L,
                visibleRank = 1,
                weakDerivation = true,
                mutationAffected = false
            ),
            TraktNextUpValidationCandidate(
                contentId = "mutated-show",
                activityAtMs = 9_000L,
                visibleRank = 2,
                weakDerivation = true,
                mutationAffected = true
            )
        )
        val freshCache = TraktNextUpValidationCacheEntry(
            updatedAtMs = 19_000L,
            ttlMs = 10_000L
        )

        val selected = TraktNextUpValidationPolicy.selectCandidates(
            candidates = candidates,
            nowMs = 20_000L,
            cache = mapOf(
                "cached-show" to freshCache,
                "mutated-show" to freshCache
            ),
            visibleCandidateLimit = 20,
            validationBudget = 5
        )

        assertEquals(listOf("mutated-show"), selected.map { it.contentId })
    }

    @Test
    fun `stale validation bypasses fresh cache after remote activity changes`() {
        val candidate = TraktNextUpValidationCandidate(
            contentId = "stale-show",
            activityAtMs = 10_000L,
            visibleRank = 1,
            weakDerivation = false,
            mutationAffected = false,
            staleValidation = true
        )
        val freshCache = TraktNextUpValidationCacheEntry(
            updatedAtMs = 19_000L,
            ttlMs = 10_000L
        )

        val selected = TraktNextUpValidationPolicy.selectCandidates(
            candidates = listOf(candidate),
            nowMs = 20_000L,
            cache = mapOf("stale-show" to freshCache),
            visibleCandidateLimit = 20,
            validationBudget = 5
        )

        assertEquals(listOf("stale-show"), selected.map { it.contentId })
    }

    @Test
    fun `validation fallback does not publish unaired local continue watching candidate`() {
        val localFuture = TraktProgressService.NextUpEntry(
            contentId = "future-show",
            name = "Future Show",
            season = 2,
            episode = 1,
            episodeTitle = "Premiere",
            videoId = "future-show:2:1",
            firstAired = null,
            firstAiredMs = 30_000L,
            activityAtMs = 10_000L
        )

        val result = TraktNextUpValidationPolicy.resolvePublishableCandidate(
            localCandidate = localFuture,
            validationResult = TraktNextUpValidationResult.NoCurrentAiredNextEpisode,
            nowMs = 20_000L
        )

        assertEquals(null, result)
    }

    @Test
    fun `validation no current aired next episode suppresses unknown date fallback`() {
        val localUnknownDate = TraktProgressService.NextUpEntry(
            contentId = "unknown-show",
            name = "Unknown Show",
            season = 1,
            episode = 99,
            episodeTitle = null,
            videoId = "unknown-show:1:99",
            firstAired = null,
            firstAiredMs = 0L,
            activityAtMs = 10_000L
        )

        val result = TraktNextUpValidationPolicy.resolvePublishableCandidate(
            localCandidate = localUnknownDate,
            validationResult = TraktNextUpValidationResult.NoCurrentAiredNextEpisode,
            nowMs = 20_000L
        )

        assertEquals(null, result)
    }

    @Test
    fun `validation failure keeps aired local candidate`() {
        val localAired = TraktProgressService.NextUpEntry(
            contentId = "aired-show",
            name = "Aired Show",
            season = 1,
            episode = 2,
            episodeTitle = null,
            videoId = "aired-show:1:2",
            firstAired = null,
            firstAiredMs = 10_000L,
            activityAtMs = 9_000L
        )

        val result = TraktNextUpValidationPolicy.resolvePublishableCandidate(
            localCandidate = localAired,
            validationResult = TraktNextUpValidationResult.Failed,
            nowMs = 20_000L
        )

        assertEquals(localAired, result)
    }

    @Test
    fun `validation failure suppresses weakly derived unknown episode candidate`() {
        val weakLocalCandidate = TraktProgressService.NextUpEntry(
            contentId = "weak-show",
            name = "Weak Show",
            season = 2,
            episode = 9,
            episodeTitle = null,
            videoId = "weak-show:2:9",
            firstAired = null,
            firstAiredMs = 0L,
            activityAtMs = 9_000L
        )

        val result = TraktNextUpValidationPolicy.resolvePublishableCandidate(
            localCandidate = weakLocalCandidate,
            validationResult = TraktNextUpValidationResult.Failed,
            nowMs = 20_000L,
            weakDerivation = true
        )

        assertEquals(null, result)
    }
}
