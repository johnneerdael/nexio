package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkExternalIdSelectorTest {

    private val selector = ArtworkExternalIdSelector()

    @Test
    fun `top posters anime poster prefers kitsu before imdb`() {
        val ids = selector.selectIds(
            provider = IntegrationProvider.TOP_POSTERS,
            imageType = ArtworkType.POSTER,
            mediaKind = MetadataMediaKind.ANIME,
            providerIds = ProviderIds(kitsu = "7442", imdb = "tt0388629")
        )

        assertEquals(
            listOf(
                ArtworkProviderExternalId("kitsu", "7442"),
                ArtworkProviderExternalId("imdb", "tt0388629")
            ),
            ids
        )
    }

    @Test
    fun `top posters anime poster can use mal anilist and anidb when kitsu missing`() {
        val ids = selector.selectIds(
            provider = IntegrationProvider.TOP_POSTERS,
            imageType = ArtworkType.POSTER,
            mediaKind = MetadataMediaKind.ANIME,
            providerIds = ProviderIds(
                mal = "21",
                anilist = "1",
                anidb = "23",
                imdb = "tt0388629"
            )
        )

        assertEquals(
            listOf(
                ArtworkProviderExternalId("mal", "21"),
                ArtworkProviderExternalId("anilist", "1"),
                ArtworkProviderExternalId("anidb", "23"),
                ArtworkProviderExternalId("imdb", "tt0388629")
            ),
            ids
        )
    }

    @Test
    fun `rpdb poster never returns kitsu and uses imdb if present`() {
        val ids = selector.selectIds(
            provider = IntegrationProvider.RPDB,
            imageType = ArtworkType.POSTER,
            mediaKind = MetadataMediaKind.ANIME,
            providerIds = ProviderIds(kitsu = "7442", imdb = "tt0388629")
        )

        assertEquals(listOf(ArtworkProviderExternalId("imdb", "tt0388629")), ids)
    }

    @Test
    fun `top posters thumbnail requires episode context`() {
        val missingContext = selector.selectIds(
            provider = IntegrationProvider.TOP_POSTERS,
            imageType = ArtworkType.THUMBNAIL,
            mediaKind = MetadataMediaKind.SERIES,
            providerIds = ProviderIds(tvdb = "121361")
        )
        val invalidContext = selector.selectIds(
            provider = IntegrationProvider.TOP_POSTERS,
            imageType = ArtworkType.THUMBNAIL,
            mediaKind = MetadataMediaKind.SERIES,
            providerIds = ProviderIds(tvdb = "121361"),
            episodeContext = EpisodeArtworkContext(season = 0, episode = 1)
        )

        assertTrue(missingContext.isEmpty())
        assertTrue(invalidContext.isEmpty())
    }

    @Test
    fun `top posters tv thumbnail formats tvdb and tmdb series ids in order`() {
        val ids = selector.selectIds(
            provider = IntegrationProvider.TOP_POSTERS,
            imageType = ArtworkType.THUMBNAIL,
            mediaKind = MetadataMediaKind.SERIES,
            providerIds = ProviderIds(tvdb = "121361", tmdb = "1399"),
            episodeContext = EpisodeArtworkContext(season = 1, episode = 2)
        )

        assertEquals(
            listOf(
                ArtworkProviderExternalId("tvdb", "121361"),
                ArtworkProviderExternalId("tmdb", "series-1399")
            ),
            ids.take(2)
        )
    }

    @Test
    fun `top posters movie poster formats tmdb movie id`() {
        val ids = selector.selectIds(
            provider = IntegrationProvider.TOP_POSTERS,
            imageType = ArtworkType.POSTER,
            mediaKind = MetadataMediaKind.MOVIE,
            providerIds = ProviderIds(tmdb = "550")
        )

        assertEquals(listOf(ArtworkProviderExternalId("tmdb", "movie-550")), ids)
    }

    @Test
    fun `selector trims ids and ignores blanks`() {
        val ids = selector.selectIds(
            provider = IntegrationProvider.TOP_POSTERS,
            imageType = ArtworkType.POSTER,
            mediaKind = MetadataMediaKind.MOVIE,
            providerIds = ProviderIds(
                tvdb = "  ",
                tmdb = " 550 ",
                imdb = " tt0137523 ",
                trakt = "\n123\n"
            )
        )

        assertEquals(
            listOf(
                ArtworkProviderExternalId("tmdb", "movie-550"),
                ArtworkProviderExternalId("imdb", "tt0137523"),
                ArtworkProviderExternalId("trakt", "123")
            ),
            ids
        )
    }

    @Test
    fun `rpdb thumbnail returns empty even with valid episode context`() {
        val ids = selector.selectIds(
            provider = IntegrationProvider.RPDB,
            imageType = ArtworkType.THUMBNAIL,
            mediaKind = MetadataMediaKind.SERIES,
            providerIds = ProviderIds(imdb = "tt0944947", tmdb = "1399", tvdb = "121361"),
            episodeContext = EpisodeArtworkContext(season = 1, episode = 1)
        )

        assertTrue(ids.isEmpty())
    }

    @Test
    fun `selector does not double prefix tmdb or tvdb ids`() {
        val topPostersMovie = selector.selectIds(
            provider = IntegrationProvider.TOP_POSTERS,
            imageType = ArtworkType.POSTER,
            mediaKind = MetadataMediaKind.MOVIE,
            providerIds = ProviderIds(tmdb = "movie-550")
        )
        val rpdbSeries = selector.selectIds(
            provider = IntegrationProvider.RPDB,
            imageType = ArtworkType.POSTER,
            mediaKind = MetadataMediaKind.SERIES,
            providerIds = ProviderIds(tvdb = "121361")
        )

        assertEquals(listOf(ArtworkProviderExternalId("tmdb", "movie-550")), topPostersMovie)
        assertEquals(listOf(ArtworkProviderExternalId("tvdb", "series-121361")), rpdbSeries)
    }

    @Test
    fun `unsupported provider or image combo returns empty`() {
        val unsupportedProvider = selector.selectIds(
            provider = IntegrationProvider.TMDB,
            imageType = ArtworkType.POSTER,
            mediaKind = MetadataMediaKind.MOVIE,
            providerIds = ProviderIds(tmdb = "550")
        )
        val unsupportedImageType = selector.selectIds(
            provider = IntegrationProvider.TOP_POSTERS,
            imageType = ArtworkType.BACKDROP,
            mediaKind = MetadataMediaKind.MOVIE,
            providerIds = ProviderIds(tmdb = "550")
        )

        assertTrue(unsupportedProvider.isEmpty())
        assertTrue(unsupportedImageType.isEmpty())
    }

    @Test
    fun `rpdb formats tmdb and tvdb with required media prefixes and ignores anime only ids`() {
        val ids = selector.selectIds(
            provider = IntegrationProvider.RPDB,
            imageType = ArtworkType.POSTER,
            mediaKind = MetadataMediaKind.SERIES,
            providerIds = ProviderIds(
                tmdb = "1399",
                tvdb = "121361",
                kitsu = "7442",
                mal = "21",
                anilist = "1",
                anidb = "23"
            )
        )

        assertEquals(
            listOf(
                ArtworkProviderExternalId("tmdb", "series-1399"),
                ArtworkProviderExternalId("tvdb", "series-121361")
            ),
            ids
        )
    }
}
