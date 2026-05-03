package com.nexio.tv.data.integration.metadata

import com.nexio.tv.data.remote.api.TvdbEpisodeRecord
import com.nexio.tv.data.remote.api.TvdbTranslationRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class TvdbEpisodeLocalizationTest {
    @Test
    fun `localized season episodes overlay english base and fall back to per episode tvdb translations`() {
        val english = listOf(
            episode(
                id = 101,
                number = 1,
                name = "English title 1",
                overview = "English overview 1",
                runtime = 62
            ),
            episode(
                id = 102,
                number = 2,
                name = "English title 2",
                overview = "English overview 2",
                runtime = 55
            )
        )
        val localizedSeason = listOf(
            episode(
                id = 101,
                number = 1,
                name = "Nederlandse titel 1",
                overview = null,
                runtime = 99
            ),
            episode(
                id = 102,
                number = 2,
                name = "Nederlandse titel 2",
                overview = "Nederlandse tekst 2",
                runtime = 77
            )
        )
        val perEpisode = mapOf(
            101 to TvdbTranslationRecord(
                name = "Per episode titel 1",
                overview = "Per episode tekst 1"
            )
        )

        val mapped = TvdbEpisodeLocalization.mergeEnglishBase(
            englishEpisodes = english,
            localizedSeasonEpisodes = localizedSeason,
            perEpisodeTranslations = perEpisode
        )

        val first = mapped.getValue(1 to 1)
        assertEquals("Per episode titel 1", first.title)
        assertEquals("Per episode tekst 1", first.overview)
        assertEquals(62, first.runtimeMinutes)

        val second = mapped.getValue(1 to 2)
        assertEquals("Nederlandse titel 2", second.title)
        assertEquals("Nederlandse tekst 2", second.overview)
        assertEquals(55, second.runtimeMinutes)
    }

    @Test
    fun `per episode translation wins over season batch when both are present`() {
        val mapped = TvdbEpisodeLocalization.mergeEnglishBase(
            policy = LocalizationPolicy.tvdb(requestedLanguage = "nl"),
            englishEpisodes = listOf(
                episode(
                    id = 301,
                    number = 1,
                    name = "English title",
                    overview = "English overview",
                    runtime = 40
                )
            ),
            localizedSeasonEpisodes = listOf(
                episode(
                    id = 301,
                    number = 1,
                    name = "Season batch title",
                    overview = "Season batch overview",
                    runtime = 99
                )
            ),
            perEpisodeTranslations = mapOf(
                301 to TvdbTranslationRecord(
                    name = "Per episode title",
                    overview = "Per episode overview"
                )
            )
        )

        val episode = mapped.getValue(1 to 1)
        assertEquals("Per episode title", episode.title)
        assertEquals("Per episode overview", episode.overview)
        assertEquals(40, episode.runtimeMinutes)
    }

    @Test
    fun `placeholders are missing and fall back to english tvdb text`() {
        val mapped = TvdbEpisodeLocalization.mergeEnglishBase(
            policy = LocalizationPolicy.tvdb(requestedLanguage = "nl"),
            englishEpisodes = listOf(
                episode(
                    id = 401,
                    number = 1,
                    name = "English title",
                    overview = "English overview",
                    runtime = 42
                )
            ),
            localizedSeasonEpisodes = listOf(
                episode(
                    id = 401,
                    number = 1,
                    name = "N/A",
                    overview = "No description available.",
                    runtime = 88
                )
            ),
            perEpisodeTranslations = emptyMap()
        )

        val episode = mapped.getValue(1 to 1)
        assertEquals("English title", episode.title)
        assertEquals("English overview", episode.overview)
        assertEquals(42, episode.runtimeMinutes)
    }

    @Test
    fun `episode translation fallback ids are capped by localization policy`() {
        val english = (1..10).map { id ->
            episode(
                id = id,
                number = id,
                name = "English title $id",
                overview = "English overview $id",
                runtime = 40
            )
        }

        val missing = TvdbEpisodeLocalization.idsMissingLocalizedFields(
            policy = LocalizationPolicy.tvdb(
                requestedLanguage = "nl",
                maxPerEpisodeTranslationFallbacksPerRequest = 3
            ),
            englishEpisodes = english,
            localizedSeasonEpisodes = emptyList()
        )

        assertEquals(listOf(1, 2, 3), missing)
    }

    @Test
    fun `localized episode bundle records selected language fallback and rejected candidates`() {
        val bundle = TvdbEpisodeLocalization.mergeEnglishBaseBundle(
            policy = LocalizationPolicy.tvdb("nl"),
            englishEpisodes = listOf(
                episode(
                    id = 501,
                    number = 1,
                    name = "English title",
                    overview = "English overview",
                    runtime = 42
                )
            ),
            localizedSeasonEpisodes = listOf(
                episode(
                    id = 501,
                    number = 1,
                    name = "Nederlandse titel",
                    overview = "N/A",
                    runtime = 99
                )
            ),
            perEpisodeTranslations = emptyMap()
        )

        val localized = bundle.episodes.getValue(1 to 1)
        assertEquals("Nederlandse titel", localized.metadata.title)
        assertEquals("English overview", localized.metadata.overview)
        assertEquals("nld", localized.fieldSources.getValue("title").selectedLanguage.providerCode)
        assertEquals("eng", localized.fieldSources.getValue("overview").selectedLanguage.providerCode)
        assertEquals(FallbackRole.LANGUAGE_FALLBACK, localized.fieldSources.getValue("overview").fallbackRole)
        assertEquals(1, bundle.perEpisodeTranslationFallbacksAttempted)
        assertEquals(8, bundle.maxPerEpisodeTranslationFallbacksAllowed)
    }

    @Test
    fun `missing localized episode falls back to english metadata`() {
        val mapped = TvdbEpisodeLocalization.mergeEnglishBase(
            englishEpisodes = listOf(
                episode(
                    id = 201,
                    number = 1,
                    name = "English title",
                    overview = "English overview",
                    runtime = 42
                )
            ),
            localizedSeasonEpisodes = emptyList(),
            perEpisodeTranslations = emptyMap()
        )

        val episode = mapped.getValue(1 to 1)
        assertEquals("English title", episode.title)
        assertEquals("English overview", episode.overview)
        assertEquals(42, episode.runtimeMinutes)
    }

    @Test
    fun `episode thumbnail falls back to tvdb thumbnail when image is absent`() {
        val mapped = TvdbEpisodeLocalization.mergeEnglishBase(
            englishEpisodes = listOf(
                episode(
                    id = 601,
                    number = 1,
                    name = "English title",
                    overview = "English overview",
                    runtime = 42,
                    image = null,
                    thumbnail = "/banners/v4/episode/601/screencap/thumb.jpg"
                )
            ),
            localizedSeasonEpisodes = emptyList(),
            perEpisodeTranslations = emptyMap()
        )

        assertEquals(
            "https://artworks.thetvdb.com/banners/v4/episode/601/screencap/thumb.jpg",
            mapped.getValue(1 to 1).thumbnail
        )
    }

    @Test
    fun `episode thumbnail normalizes relative tvdb image path`() {
        val mapped = TvdbEpisodeLocalization.mergeEnglishBase(
            englishEpisodes = listOf(
                episode(
                    id = 602,
                    number = 1,
                    name = "English title",
                    overview = "English overview",
                    runtime = 42,
                    image = "/banners/v4/episode/602/screencap/test.jpg"
                )
            ),
            localizedSeasonEpisodes = emptyList(),
            perEpisodeTranslations = emptyMap()
        )

        assertEquals(
            "https://artworks.thetvdb.com/banners/v4/episode/602/screencap/test.jpg",
            mapped.getValue(1 to 1).thumbnail
        )
    }

    @Test
    fun `episode thumbnail uses TVDB episode screencap image type`() {
        val mapped = TvdbEpisodeLocalization.mergeEnglishBase(
            englishEpisodes = listOf(
                episode(
                    id = 7140390,
                    number = 1,
                    name = "English title",
                    overview = "English overview",
                    runtime = 42,
                    image = "/banners/episodes/355567/7140390.jpg",
                    imageType = 11
                )
            ),
            localizedSeasonEpisodes = emptyList(),
            perEpisodeTranslations = emptyMap()
        )

        assertEquals(
            "https://artworks.thetvdb.com/banners/episodes/355567/7140390.jpg",
            mapped.getValue(1 to 1).thumbnail
        )
    }

    @Test
    fun `episode thumbnail rejects series banner artwork`() {
        val mapped = TvdbEpisodeLocalization.mergeEnglishBase(
            englishEpisodes = listOf(
                episode(
                    id = 7140390,
                    number = 1,
                    name = "English title",
                    overview = "English overview",
                    runtime = 42,
                    image = "https://artworks.thetvdb.com/banners/series/355567/banners/5f208242a5940.jpg",
                    imageType = 1,
                    thumbnail = null
                )
            ),
            localizedSeasonEpisodes = emptyList(),
            perEpisodeTranslations = emptyMap()
        )

        assertEquals(null, mapped.getValue(1 to 1).thumbnail)
    }

    @Test
    fun `episode thumbnail accepts episode path when image type is absent`() {
        val mapped = TvdbEpisodeLocalization.mergeEnglishBase(
            englishEpisodes = listOf(
                episode(
                    id = 603,
                    number = 1,
                    name = "English title",
                    overview = "English overview",
                    runtime = 42,
                    image = "/banners/v4/episode/603/screencap/test.jpg",
                    imageType = null
                )
            ),
            localizedSeasonEpisodes = emptyList(),
            perEpisodeTranslations = emptyMap()
        )

        assertEquals(
            "https://artworks.thetvdb.com/banners/v4/episode/603/screencap/test.jpg",
            mapped.getValue(1 to 1).thumbnail
        )
    }

    @Test
    fun `episode thumbnail rejects unsafe primary image and unsafe fallback thumbnail`() {
        val mapped = TvdbEpisodeLocalization.mergeEnglishBase(
            englishEpisodes = listOf(
                episode(
                    id = 604,
                    number = 1,
                    name = "English title",
                    overview = "English overview",
                    runtime = 42,
                    image = "https://artworks.thetvdb.com/banners/series/355567/banners/5f208242a5940.jpg",
                    imageType = 1,
                    thumbnail = "https://artworks.thetvdb.com/banners/series/355567/posters/5f208242a5940.jpg"
                )
            ),
            localizedSeasonEpisodes = emptyList(),
            perEpisodeTranslations = emptyMap()
        )

        assertEquals(null, mapped.getValue(1 to 1).thumbnail)
    }

    private fun episode(
        id: Int,
        number: Int,
        name: String,
        overview: String?,
        runtime: Int,
        image: String? = "https://art.example/$id.jpg",
        imageType: Int? = null,
        thumbnail: String? = null
    ): TvdbEpisodeRecord =
        TvdbEpisodeRecord(
            id = id,
            seasonNumber = 1,
            number = number,
            name = name,
            overview = overview,
            runtime = runtime,
            aired = "2024-01-0$number",
            image = image,
            imageType = imageType,
            thumbnail = thumbnail
        )
}
