package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.WyzieIdHints
import com.nexio.tv.domain.model.WyzieSource
import org.junit.Assert.assertEquals
import org.junit.Test

class WyzieSourceRouterTest {

    private val nonAnime = WyzieIdHints(imdb = "tt0121955")

    @Test
    fun `non-anime movie returns curated movie source list`() {
        val sources = WyzieSourceRouter.sourcesFor(ContentType.MOVIE, nonAnime)
        assertEquals(
            listOf(
                WyzieSource.OPENSUBTITLES,
                WyzieSource.SUBDL,
                WyzieSource.SUBF2M,
                WyzieSource.PODNAPISI,
            ),
            sources,
        )
    }

    @Test
    fun `non-anime series returns curated tv source list with gestdown`() {
        val sources = WyzieSourceRouter.sourcesFor(ContentType.SERIES, nonAnime)
        assertEquals(
            listOf(
                WyzieSource.OPENSUBTITLES,
                WyzieSource.SUBDL,
                WyzieSource.SUBF2M,
                WyzieSource.PODNAPISI,
                WyzieSource.GESTDOWN,
            ),
            sources,
        )
    }

    @Test
    fun `tv content type aliases to series source list`() {
        val sources = WyzieSourceRouter.sourcesFor(ContentType.TV, nonAnime)
        assertEquals(
            WyzieSourceRouter.sourcesFor(ContentType.SERIES, nonAnime),
            sources,
        )
    }

    @Test
    fun `kitsu hint trips anime movie routing`() {
        val sources = WyzieSourceRouter.sourcesFor(
            ContentType.MOVIE,
            WyzieIdHints(kitsu = "42"),
        )
        assertEquals(listOf(WyzieSource.JIMAKU, WyzieSource.AJATTTOOLS), sources)
    }

    @Test
    fun `mal hint trips anime series routing`() {
        val sources = WyzieSourceRouter.sourcesFor(
            ContentType.SERIES,
            WyzieIdHints(mal = "1"),
        )
        assertEquals(
            listOf(
                WyzieSource.ANIMETOSHO,
                WyzieSource.JIMAKU,
                WyzieSource.KITSUNEKKO,
                WyzieSource.AJATTTOOLS,
            ),
            sources,
        )
    }

    @Test
    fun `anilist hint trips anime detection`() {
        assertEquals(
            true,
            WyzieSourceRouter.sourcesFor(ContentType.SERIES, WyzieIdHints(anilist = "5"))
                .first() == WyzieSource.ANIMETOSHO,
        )
    }

    @Test
    fun `anidb hint trips anime detection`() {
        assertEquals(
            true,
            WyzieSourceRouter.sourcesFor(ContentType.MOVIE, WyzieIdHints(anidb = "9"))
                .first() == WyzieSource.JIMAKU,
        )
    }

    @Test
    fun `anime tv aliases to series anime list`() {
        assertEquals(
            WyzieSourceRouter.sourcesFor(ContentType.SERIES, WyzieIdHints(kitsu = "1")),
            WyzieSourceRouter.sourcesFor(ContentType.TV, WyzieIdHints(kitsu = "1")),
        )
    }

    @Test
    fun `unknown content type returns empty`() {
        val sources = WyzieSourceRouter.sourcesFor(ContentType.UNKNOWN, nonAnime)
        assertEquals(emptyList<WyzieSource>(), sources)
    }

    @Test
    fun `channel content type returns empty`() {
        val sources = WyzieSourceRouter.sourcesFor(ContentType.CHANNEL, nonAnime)
        assertEquals(emptyList<WyzieSource>(), sources)
    }

    @Test
    fun `router does not check id presence — that boundary lives in the provider`() {
        // Hints with no usable id still get a source list; the provider is responsible
        // for skipping the network call.
        val sources = WyzieSourceRouter.sourcesFor(ContentType.MOVIE, WyzieIdHints.EMPTY)
        assertEquals(
            listOf(
                WyzieSource.OPENSUBTITLES,
                WyzieSource.SUBDL,
                WyzieSource.SUBF2M,
                WyzieSource.PODNAPISI,
            ),
            sources,
        )
    }
}
