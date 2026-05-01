package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StableIdBundleModelsTest {
    @Test
    fun `provider ids become source stable ids without promoting tracking providers`() {
        val ids = ProviderIds(
            imdb = "tt0903747",
            tmdb = "1396",
            tvdb = "81189",
            trakt = "1",
            simkl = "49108"
        )

        val source = ids.toSourceStableIds(
            sourceProvider = ProviderId.TRAKT,
            sourceItemId = "trakt:show:1",
            railId = "trakt:popular"
        )

        assertEquals(ProviderId.TRAKT, source.sourceProvider)
        assertEquals("trakt:show:1", source.sourceItemId)
        assertEquals("trakt:popular", source.railId)
        assertEquals(ids, source.observedIds)
        assertTrue(source.hasTrackingSourceFacts)
        assertFalse(source.promotesTrackingProviders)
    }

    @Test
    fun `status is canonical and rating ready when canonical id and imdb are present`() {
        val bundle = StableIdBundle(
            itemKey = "series:trakt:show:1",
            itemType = ContentType.SERIES,
            canonical = CanonicalStableIds(tvdbSeriesId = "81189"),
            sidecars = SidecarStableIds(imdbId = "tt0903747"),
            source = ProviderIds(tvdb = "81189", imdb = "tt0903747").toSourceStableIds(
                sourceProvider = ProviderId.TRAKT,
                sourceItemId = "trakt:show:1",
                railId = "trakt:popular"
            ),
            evidence = listOf(StableIdEvidence("knownIds.tvdb", "TVDB", false)),
            resolvedAtMs = 10L
        )

        assertEquals(StableIdBundleStatus.CANONICAL_AND_RATING_READY, bundle.status)
        assertEquals("81189", bundle.canonical.providerNativeIdFor(MetadataPrimaryProvider.TVDB))
        assertNull(bundle.canonical.providerNativeIdFor(MetadataPrimaryProvider.IMDB))
        assertNull(bundle.canonical.providerNativeIdFor(MetadataPrimaryProvider.TRAKT))
        assertNull(bundle.canonical.providerNativeIdFor(MetadataPrimaryProvider.SIMKL))
        assertNull(bundle.canonical.providerNativeIdFor(MetadataPrimaryProvider.RPDB))
        assertNull(bundle.canonical.providerNativeIdFor(MetadataPrimaryProvider.TOP_POSTERS))
        assertEquals("tt0903747", bundle.sidecars.imdbId)
    }

    @Test
    fun `blank canonical and sidecar ids are treated as absent`() {
        val bundle = StableIdBundle(
            itemKey = "series:trakt:show:1",
            itemType = ContentType.SERIES,
            canonical = CanonicalStableIds(tvdbSeriesId = ""),
            sidecars = SidecarStableIds(imdbId = " "),
            source = ProviderIds(tvdb = "", imdb = " ").toSourceStableIds(
                sourceProvider = ProviderId.TRAKT,
                sourceItemId = "trakt:show:1",
                railId = "trakt:popular"
            ),
            evidence = emptyList(),
            resolvedAtMs = 10L
        )

        assertEquals(StableIdBundleStatus.UNRESOLVED, bundle.status)
        assertNull(bundle.canonical.providerNativeIdFor(MetadataPrimaryProvider.TVDB))
    }

    @Test
    fun `kitsu anime canonical id is not an imdb rating requirement`() {
        val bundle = StableIdBundle(
            itemKey = "anime:kitsu:7442",
            itemType = ContentType.SERIES,
            canonical = CanonicalStableIds(kitsuAnimeId = "7442"),
            sidecars = SidecarStableIds(),
            source = ProviderIds(kitsu = "7442").toSourceStableIds(
                sourceProvider = ProviderId.KITSU,
                sourceItemId = "kitsu:7442",
                railId = "kitsu:trending"
            ),
            evidence = listOf(StableIdEvidence("knownIds.kitsu", "KITSU", false)),
            resolvedAtMs = 10L
        )

        assertEquals(StableIdBundleStatus.CANONICAL_READY_RATING_UNRESOLVED, bundle.status)
        assertEquals("7442", bundle.canonical.providerNativeIdFor(MetadataPrimaryProvider.KITSU))
        assertNull(bundle.sidecars.imdbId)
    }
}
