package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.ProviderId
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryAnimeEpisodeCoordinateStoreTest {

    @Test
    fun `put and get round-trip by group key, source, and target`() {
        val store = InMemoryAnimeEpisodeCoordinateStore()
        val groupKey = AnimeWorkGroupKey("anime-work:tvdb:305074")
        val source = SourceEpisodeCoordinate(sourceKitsuId = "13881", season = 3, episode = 1)
        val projection = projection(source, ProviderId.TVDB, "305074", 3, 1)

        store.put(groupKey, source, EpisodeProjectionTarget.TRAKT_SCROBBLE, projection)

        assertEquals(projection, store.get(groupKey, source, EpisodeProjectionTarget.TRAKT_SCROBBLE))
        assertNull("different target must not hit", store.get(groupKey, source, EpisodeProjectionTarget.PREMIUM_THUMBNAIL))
    }

    @Test
    fun `invalidate removes all entries for a group key`() {
        val store = InMemoryAnimeEpisodeCoordinateStore()
        val groupKey = AnimeWorkGroupKey("anime-work:tvdb:305074")
        val source = SourceEpisodeCoordinate(sourceKitsuId = "13881", season = 3, episode = 1)
        val projection = projection(source, ProviderId.TVDB, "305074", 3, 1)

        store.put(groupKey, source, EpisodeProjectionTarget.TRAKT_SCROBBLE, projection)
        store.invalidate(groupKey)

        assertNull(store.get(groupKey, source, EpisodeProjectionTarget.TRAKT_SCROBBLE))
    }

    private fun projection(
        source: SourceEpisodeCoordinate,
        targetProvider: ProviderId,
        seriesId: String,
        season: Int,
        episode: Int,
    ) = AnimeEpisodeProjection(
        sourceKitsuId = source.sourceKitsuId,
        sourceKitsuCoordinate = EpisodeCoordinate(ProviderId.KITSU, source.sourceKitsuId, source.season, source.episode),
        displayCoordinate = EpisodeCoordinate(targetProvider, seriesId, season, episode),
        targetCoordinate = EpisodeCoordinate(targetProvider, seriesId, season, episode),
        scrobbleCoordinate = EpisodeCoordinate(targetProvider, seriesId, season, episode),
        premiumArtworkCoordinate = null,
        tvdbCoordinate = if (targetProvider == ProviderId.TVDB) EpisodeCoordinate(targetProvider, seriesId, season, episode) else null,
        tmdbCoordinate = null,
        confidence = CoordinateConfidence.HIGH,
        fallbackReason = null,
        evidence = emptyList(),
    )
}
