package com.nexio.tv.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridPlayableFileRulesTest {

    @Test
    fun `strict playable video candidate accepts supported video containers`() {
        assertTrue(isStrictPlayableVideoCandidate(filename = "Movie.2024.mkv"))
        assertTrue(isStrictPlayableVideoCandidate(filename = "Movie.2024.m2ts"))
        assertTrue(isStrictPlayableVideoCandidate(filename = "Movie.2024.ts"))
        assertTrue(isStrictPlayableVideoCandidate(filename = "Movie.2024.divx"))
    }

    @Test
    fun `strict playable video candidate rejects archives audio-only and sample payloads`() {
        assertFalse(isStrictPlayableVideoCandidate(filename = "Movie.2024.rar"))
        assertFalse(isStrictPlayableVideoCandidate(filename = "Movie.2024.zip"))
        assertFalse(isStrictPlayableVideoCandidate(filename = "Movie.2024.f4a"))
        assertFalse(isStrictPlayableVideoCandidate(filename = "sample.Movie.2024.mkv"))
        assertFalse(
            isStrictPlayableVideoCandidate(
                filename = "Movie.2024.mkv",
                folder = "Extras/Samples"
            )
        )
    }
}
