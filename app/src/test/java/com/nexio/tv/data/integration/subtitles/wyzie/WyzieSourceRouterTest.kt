package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.WyzieIdHints
import com.nexio.tv.domain.model.WyzieSource
import org.junit.Assert.assertEquals
import org.junit.Test

class WyzieSourceRouterTest {

    private val unified = listOf(
        WyzieSource.OPENSUBTITLES,
        WyzieSource.SUBDL,
        WyzieSource.TVSUBTITLES,
    )

    private val nonAnime = WyzieIdHints(imdb = "tt0121955")

    @Test
    fun `movie returns unified source list`() {
        assertEquals(unified, WyzieSourceRouter.sourcesFor(ContentType.MOVIE, nonAnime))
    }

    @Test
    fun `series returns unified source list`() {
        assertEquals(unified, WyzieSourceRouter.sourcesFor(ContentType.SERIES, nonAnime))
    }

    @Test
    fun `tv content type returns unified source list`() {
        assertEquals(unified, WyzieSourceRouter.sourcesFor(ContentType.TV, nonAnime))
    }

    @Test
    fun `kitsu hint does not switch source list`() {
        assertEquals(
            unified,
            WyzieSourceRouter.sourcesFor(ContentType.MOVIE, WyzieIdHints(kitsu = "42")),
        )
        assertEquals(
            unified,
            WyzieSourceRouter.sourcesFor(ContentType.SERIES, WyzieIdHints(kitsu = "42")),
        )
    }

    @Test
    fun `mal hint does not switch source list`() {
        assertEquals(
            unified,
            WyzieSourceRouter.sourcesFor(ContentType.SERIES, WyzieIdHints(mal = "1")),
        )
    }

    @Test
    fun `anilist hint does not switch source list`() {
        assertEquals(
            unified,
            WyzieSourceRouter.sourcesFor(ContentType.SERIES, WyzieIdHints(anilist = "5")),
        )
    }

    @Test
    fun `anidb hint does not switch source list`() {
        assertEquals(
            unified,
            WyzieSourceRouter.sourcesFor(ContentType.MOVIE, WyzieIdHints(anidb = "9")),
        )
    }

    @Test
    fun `unknown content type returns empty`() {
        assertEquals(
            emptyList<WyzieSource>(),
            WyzieSourceRouter.sourcesFor(ContentType.UNKNOWN, nonAnime),
        )
    }

    @Test
    fun `channel content type returns empty`() {
        assertEquals(
            emptyList<WyzieSource>(),
            WyzieSourceRouter.sourcesFor(ContentType.CHANNEL, nonAnime),
        )
    }

    @Test
    fun `person content type returns empty`() {
        assertEquals(
            emptyList<WyzieSource>(),
            WyzieSourceRouter.sourcesFor(ContentType.PERSON, nonAnime),
        )
    }

    @Test
    fun `router does not check id presence — that boundary lives in the provider`() {
        assertEquals(
            unified,
            WyzieSourceRouter.sourcesFor(ContentType.MOVIE, WyzieIdHints.EMPTY),
        )
    }
}
