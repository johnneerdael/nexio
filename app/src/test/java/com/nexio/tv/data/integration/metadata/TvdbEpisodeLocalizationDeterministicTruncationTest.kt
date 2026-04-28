package com.nexio.tv.data.integration.metadata

import com.nexio.tv.data.remote.api.TvdbEpisodeRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class TvdbEpisodeLocalizationDeterministicTruncationTest {

    @Test
    fun `idsMissingLocalizedFields sorts before truncate so output is stable across runs`() {
        val englishEpisodes = listOf(
            episode(
                id = 50,
                number = 1,
                name = "E50",
                overview = "X",
                runtime = 40
            ),
            episode(
                id = 10,
                number = 2,
                name = "E10",
                overview = "X",
                runtime = 40
            ),
            episode(
                id = 30,
                number = 3,
                name = "E30",
                overview = "X",
                runtime = 40
            ),
            episode(
                id = 20,
                number = 4,
                name = "E20",
                overview = "X",
                runtime = 40
            ),
            episode(
                id = 40,
                number = 5,
                name = "E40",
                overview = "X",
                runtime = 40
            )
        )
        val localizedEpisodes = emptyList<TvdbEpisodeRecord>()  // all missing → all candidates for fallback
        val policy = LocalizationPolicy.tvdb(
            requestedLanguage = "nld",
            maxPerEpisodeTranslationFallbacksPerRequest = 3
        )

        val first = TvdbEpisodeLocalization.idsMissingLocalizedFields(
            policy = policy,
            englishEpisodes = englishEpisodes,
            localizedSeasonEpisodes = localizedEpisodes
        )
        val second = TvdbEpisodeLocalization.idsMissingLocalizedFields(
            policy = policy,
            englishEpisodes = englishEpisodes,
            localizedSeasonEpisodes = localizedEpisodes
        )

        assertEquals("F-E-05: same input must produce same truncated subset", first, second)
        assertEquals("F-E-05: must sort IDs before truncating", listOf(10, 20, 30), first)
    }

    private fun episode(
        id: Int,
        number: Int,
        name: String,
        overview: String?,
        runtime: Int
    ): TvdbEpisodeRecord =
        TvdbEpisodeRecord(
            id = id,
            seasonNumber = 1,
            number = number,
            name = name,
            overview = overview,
            runtime = runtime,
            aired = "2024-01-0$number",
            image = "https://art.example/$id.jpg"
        )
}
