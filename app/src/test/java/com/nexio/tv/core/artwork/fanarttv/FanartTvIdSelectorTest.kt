package com.nexio.tv.core.artwork.fanarttv

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FanartTvIdSelectorTest {
    private val selector = FanartTvIdSelector()

    @Test fun `movie with tmdb`() = assertEquals(
        FanartTvCallId(FanartTvCallId.Type.MOVIE, "550"),
        selector.select(MetadataMediaKind.MOVIE, ProviderIds(tmdb = "550"))
    )
    @Test fun `movie without tmdb`() =
        assertNull(selector.select(MetadataMediaKind.MOVIE, ProviderIds(imdb = "tt0137523")))
    @Test fun `series with tvdb`() = assertEquals(
        FanartTvCallId(FanartTvCallId.Type.TV, "81189"),
        selector.select(MetadataMediaKind.SERIES, ProviderIds(tvdb = "81189"))
    )
    @Test fun `series without tvdb`() =
        assertNull(selector.select(MetadataMediaKind.SERIES, ProviderIds(tmdb = "1396")))
    @Test fun `anime always null`() =
        assertNull(selector.select(MetadataMediaKind.ANIME, ProviderIds(tmdb = "1", tvdb = "2")))
    @Test fun `unknown always null`() =
        assertNull(selector.select(MetadataMediaKind.UNKNOWN, ProviderIds(tmdb = "1", tvdb = "2")))
}
