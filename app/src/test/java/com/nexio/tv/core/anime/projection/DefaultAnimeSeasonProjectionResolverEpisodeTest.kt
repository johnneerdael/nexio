package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.anime.KitsuMetadataService
import com.nexio.tv.core.trace.AnimeProjectionTraceEvents
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.domain.model.ProviderId
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultAnimeSeasonProjectionResolverEpisodeTest {

    @Test
    fun `MHA S3E1 projects to TVDB S3E1 with HIGH confidence for trakt scrobble`() = runBlocking {
        val kitsu = mockk<KitsuMetadataService>(relaxed = true)
        val resolver = resolverFor(
            kitsuId = "13881",
            tvdb = "305074",
            kitsuMetadataService = kitsu,
        )
        val work = resolver.resolveWork(AnimeSourceIdentity("13881", null))
        val source = SourceEpisodeCoordinate(sourceKitsuId = "13881", season = 3, episode = 1)

        val projection = resolver.resolveEpisodeProjection(work, source, EpisodeProjectionTarget.TRAKT_SCROBBLE)

        assertNotNull(projection.scrobbleCoordinate)
        assertEquals(ProviderId.TVDB, projection.scrobbleCoordinate?.provider)
        assertEquals("305074", projection.scrobbleCoordinate?.seriesId)
        assertEquals(3, projection.scrobbleCoordinate?.season)
        assertEquals(1, projection.scrobbleCoordinate?.episode)
        assertEquals(CoordinateConfidence.HIGH, projection.confidence)
        assertNull(projection.fallbackReason)
        // targetCoordinate for TRAKT_SCROBBLE == scrobbleCoordinate
        assertEquals(projection.scrobbleCoordinate, projection.targetCoordinate)
    }

    @Test
    fun `One Piece S1E1 is also rejected for scrobble when resource is flat franchise`() = runBlocking {
        val kitsuSvc = mockk<KitsuMetadataService>()
        // Simulate One Piece: all episodes reported under season=1
        coEvery { kitsuSvc.fetchEpisodeEnrichment(any(), any(), any()) } returns
            (1..1050).associate { ep -> (1 to ep) to kitsuEp(season = 1, ep = ep) }
        val resolver = resolverFor(
            kitsuId = "12",
            tvdb = "81797",
            kitsuMetadataService = kitsuSvc,
        )
        val work = resolver.resolveWork(AnimeSourceIdentity("12", null))
        val source = SourceEpisodeCoordinate(sourceKitsuId = "12", season = 1, episode = 1)

        val projection = resolver.resolveEpisodeProjection(work, source, EpisodeProjectionTarget.TRAKT_SCROBBLE)

        assertNull("S1E1 must also be rejected for flat franchise", projection.scrobbleCoordinate)
        assertEquals(CoordinateConfidence.LOW, projection.confidence)
        assertEquals(FallbackReason.LOW_CONFIDENCE_FLAT_KITSU, projection.fallbackReason)
    }

    private fun resolverFor(
        kitsuId: String,
        tvdb: String,
        kitsuMetadataService: KitsuMetadataService,
    ) = DefaultAnimeSeasonProjectionResolver(
        idMappingService = AnimeIdMappingService(
            assetProvider = {
                AnimeIdMapAsset(
                    schemaVersion = 1,
                    recordsByKitsu = mapOf(
                        kitsuId to AnimeIdMapRecord(
                            kitsu = kitsuId, tvdb = tvdb,
                            mediaType = "series", sourceType = "TV"
                        )
                    )
                )
            }
        ),
        kitsuMetadataService = kitsuMetadataService,
        store = InMemoryAnimeEpisodeCoordinateStore(),
        traceEvents = mockk(relaxed = true),
        presentationCache = InMemoryAnimeSeasonPresentationCache(),
    )

    private fun kitsuEp(season: Int, ep: Int) = TvEpisodeMetadata(
        providerEpisodeId = "kitsu:ep$season-$ep",
        seasonNumber = season,
        episodeNumber = ep,
        title = "S${season}E$ep",
        overview = null,
        thumbnail = null,
        airDate = null,
        runtimeMinutes = 24,
    )
}
