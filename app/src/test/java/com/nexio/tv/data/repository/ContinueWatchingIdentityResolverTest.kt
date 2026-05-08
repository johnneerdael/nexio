package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.CanonicalStableIds
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.SidecarStableIds
import com.nexio.tv.core.metadata.router.SourceStableIds
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContinueWatchingIdentityResolverTest {
    private val metadataRouterFacade = mockk<MetadataRouterFacade>()
    private val resolver = ContinueWatchingIdentityResolver(
        metadataRouterFacade = metadataRouterFacade,
        streamFetchIdentityResolver = StreamFetchIdentityResolver()
    )

    @Test
    fun `identity depth uses cached stable-id bundle aliases without detail-core provider hydration`() = runTest {
        val requestSlot = slot<MetadataRequest>()
        coEvery {
            metadataRouterFacade.resolveStableIdBundle(
                capture(requestSlot),
                StableIdResolutionTrigger.CONTINUE_WATCHING,
                any()
            )
        } returns citadelBundle()

        val local = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = citadelProgress(
                    contentId = "tvdb:393268",
                    videoId = "tvdb:393268:2:1"
                ),
                languageTag = "en-US"
            )
        )
        val remote = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = citadelProgress(
                    contentId = "tt9794044",
                    videoId = "tt9794044:2:1",
                    source = WatchProgress.SOURCE_TRAKT_PLAYBACK
                ),
                languageTag = "en-US"
            )
        )

        assertEquals("profile:1:series:tvdb:393268:s2e1", local.canonicalKey?.stableKey())
        assertEquals(local.canonicalKey?.stableKey(), remote.canonicalKey?.stableKey())
        assertEquals(local.identityKey(), remote.identityKey())
        assertEquals("tt9794044:2:1", local.streamFetchIdentity?.videoId)
        assertEquals("tt9794044:2:1", remote.streamFetchIdentity?.videoId)
        assertEquals("tt9794044", local.displayIdentity?.providerIds?.imdb)
        assertEquals("393268", local.displayIdentity?.providerIds?.tvdb)
        assertEquals(ContinueWatchingRecord.Source.REMOTE, remote.source)
        assertEquals(ContinueWatchingSource.TRAKT_PLAYBACK, remote.resumeIdentities.single().source)
        assertEquals(MetadataDepth.IDENTITY, requestSlot.captured.depth)
        assertEquals("tt9794044", requestSlot.captured.contentId)
        assertEquals(ContentType.SERIES, requestSlot.captured.contentType)
    }

    @Test
    fun `identity resolution failure preserves a low-confidence legacy row`() = runTest {
        coEvery {
            metadataRouterFacade.resolveStableIdBundle(any(), any(), any())
        } throws IllegalStateException("identity cache unavailable")

        val record = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = citadelProgress(),
                languageTag = "en-US"
            )
        )

        assertEquals(IdentityConfidence.LOW, record.identityConfidence)
        assertTrue(record.parentId.startsWith("series:raw:"))
        assertEquals("${record.parentId}:s2e1", record.contentId)
        assertEquals("tt9794044", record.resumeIdentities.single().contentId)
        assertTrue(record.identityWarnings.single().contains("IllegalStateException"))
        assertTrue(record.identityWarnings.single().contains("identity cache unavailable"))
    }

    @Test
    fun `identity resolution failure with specials-like coordinates preserves a low-confidence legacy row`() = runTest {
        coEvery {
            metadataRouterFacade.resolveStableIdBundle(any(), any(), any())
        } throws IllegalStateException("identity cache unavailable")

        val record = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = citadelProgress(season = 0, episode = 1),
                languageTag = "en-US"
            )
        )

        assertEquals(IdentityConfidence.LOW, record.identityConfidence)
        assertTrue(record.parentId.startsWith("series:raw:"))
        assertEquals(record.parentId, record.contentId)
        assertNull(record.episodeContext)
        assertEquals("tt9794044", record.resumeIdentities.single().contentId)
        assertEquals("tt9794044:2:1", record.resumeIdentities.single().videoId)
        assertNull(record.resumeIdentities.single().season)
        assertNull(record.resumeIdentities.single().episode)
        assertTrue(record.identityWarnings.single().contains("identity cache unavailable"))
    }

    @Test
    fun `identity resolution failure with partial episode coordinates preserves a low-confidence legacy row`() = runTest {
        coEvery {
            metadataRouterFacade.resolveStableIdBundle(any(), any(), any())
        } throws IllegalStateException("identity cache unavailable")

        val record = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = citadelProgress(season = 2, episode = null),
                languageTag = "en-US"
            )
        )

        assertEquals(IdentityConfidence.LOW, record.identityConfidence)
        assertTrue(record.parentId.startsWith("series:raw:"))
        assertEquals(record.parentId, record.contentId)
        assertNull(record.episodeContext)
        assertEquals("tt9794044", record.resumeIdentities.single().contentId)
        assertEquals("tt9794044:2:1", record.resumeIdentities.single().videoId)
        assertNull(record.resumeIdentities.single().season)
        assertNull(record.resumeIdentities.single().episode)
        assertTrue(record.identityWarnings.single().contains("identity cache unavailable"))
    }

    @Test
    fun `identity resolution cancellation propagates instead of falling back`() = runTest {
        coEvery {
            metadataRouterFacade.resolveStableIdBundle(any(), any(), any())
        } throws CancellationException("cancelled by parent")

        try {
            resolver.resolveOrFallback(
                RawContinueWatchingInput(
                    profileId = 1,
                    progress = citadelProgress(),
                    languageTag = "en-US"
                )
            )
            fail("Expected CancellationException to propagate")
        } catch (error: CancellationException) {
            assertEquals("cancelled by parent", error.message)
        }
    }

    @Test
    fun `raw imdb episode video id is observed when content id lacks imdb`() = runTest {
        val requestSlot = slot<MetadataRequest>()
        coEvery {
            metadataRouterFacade.resolveStableIdBundle(
                capture(requestSlot),
                StableIdResolutionTrigger.CONTINUE_WATCHING,
                any()
            )
        } returns citadelBundle()

        resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = citadelProgress(
                    contentId = "tvdb:393268",
                    videoId = "tt9794044:2:1"
                ),
                languageTag = "en-US"
            )
        )

        assertEquals("tt9794044", requestSlot.captured.sourceContext.previewStableIds.imdb)
    }

    @Test
    fun `legacy low-confidence record preserves remote source`() = runTest {
        coEvery {
            metadataRouterFacade.resolveStableIdBundle(any(), any(), any())
        } throws IllegalArgumentException("trakt progress identity failed")

        val record = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = citadelProgress(source = WatchProgress.SOURCE_TRAKT_PLAYBACK),
                languageTag = "en-US"
            )
        )

        assertEquals(ContinueWatchingRecord.Source.REMOTE, record.source)
        assertEquals(ContinueWatchingSource.TRAKT_PLAYBACK, record.resumeIdentities.single().source)
        assertEquals(IdentityConfidence.LOW, record.identityConfidence)
    }

    @Test
    fun `identity depth without supported stream ID preserves row with warning`() = runTest {
        coEvery {
            metadataRouterFacade.resolveStableIdBundle(any(), any(), any())
        } returns citadelBundle(sidecars = SidecarStableIds())

        val record = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = citadelProgress(
                    contentId = "tvdb:393268",
                    videoId = "tvdb:393268:2:1"
                ),
                languageTag = "en-US"
            )
        )

        assertEquals(IdentityConfidence.MEDIUM, record.identityConfidence)
        assertNull(record.streamFetchIdentity)
        assertEquals(listOf("stream fetch identity unresolved"), record.identityWarnings)
        assertEquals("tvdb:393268", record.resumeIdentities.single().contentId)
    }

    @Test
    fun `movie identity depth without supported stream ID preserves row with warning`() = runTest {
        coEvery {
            metadataRouterFacade.resolveStableIdBundle(any(), any(), any())
        } returns movieBundle(sidecars = SidecarStableIds())

        val record = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = citadelProgress(
                    contentId = "tmdb:123",
                    contentType = "movie",
                    videoId = "tmdb:123",
                    season = null,
                    episode = null
                ),
                languageTag = "en-US"
            )
        )

        assertEquals(IdentityConfidence.MEDIUM, record.identityConfidence)
        assertNull(record.streamFetchIdentity)
        assertEquals(listOf("stream fetch identity unresolved"), record.identityWarnings)
    }

    @Test
    fun `kitsu anime id resolves continue watching record as anime`() = runTest {
        val requestSlot = slot<MetadataRequest>()
        coEvery {
            metadataRouterFacade.resolveStableIdBundle(
                capture(requestSlot),
                StableIdResolutionTrigger.CONTINUE_WATCHING,
                any()
            )
        } returns animeBundle(
            canonical = CanonicalStableIds(kitsuAnimeId = "42"),
            observedIds = ProviderIds(kitsu = "42"),
            sidecars = SidecarStableIds(imdbId = "tt12343534")
        )

        val record = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = citadelProgress(
                    contentId = "kitsu:anime:42",
                    videoId = "kitsu:anime:42:1:1",
                    contentType = "series",
                    season = 1,
                    episode = 1
                ),
                languageTag = "en-US"
            )
        )

        assertEquals(MetadataMediaKind.ANIME, record.canonicalKey?.mediaKind)
        assertEquals("profile:1:anime:kitsu:42:s1e1", record.canonicalKey?.stableKey())
        assertEquals("anime:kitsu:42", record.parentId)
        assertEquals("anime:kitsu:42:s1e1", record.contentId)
        assertEquals("42", requestSlot.captured.sourceContext.previewStableIds.kitsu)
        assertTrue(record.streamFetchIdentity!!.trace.any { it.contains("mediaKind=ANIME") })
    }

    @Test
    fun `resolved kitsu anime id overrides non-anime raw provider for canonical keys`() = runTest {
        coEvery {
            metadataRouterFacade.resolveStableIdBundle(any(), any(), any())
        } returns animeBundle(
            canonical = CanonicalStableIds(tvdbSeriesId = "393268", kitsuAnimeId = "42"),
            observedIds = ProviderIds(tvdb = "393268"),
            sidecars = SidecarStableIds(imdbId = "tt12343534")
        )

        val record = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = citadelProgress(
                    contentId = "tvdb:393268",
                    videoId = "tvdb:393268:1:1",
                    contentType = "series",
                    season = 1,
                    episode = 1
                ),
                languageTag = "en-US"
            )
        )

        assertEquals(MetadataMediaKind.ANIME, record.canonicalKey?.mediaKind)
        assertEquals("profile:1:anime:kitsu:42:s1e1", record.canonicalKey?.stableKey())
        assertEquals("anime:kitsu:42", record.parentId)
        assertEquals("anime:kitsu:42:s1e1", record.contentId)
        assertTrue(record.streamFetchIdentity!!.trace.any { it.contains("mediaKind=ANIME") })
    }

    @Test
    fun `typed provider content id is normalized before metadata request`() = runTest {
        val cases = listOf(
            RequestNormalizationCase(
                contentId = "tmdb:tv:1399",
                expectedRequestContentId = "tmdb:1399",
                expectedPreviewStableIds = ProviderIds(tmdb = "1399")
            ),
            RequestNormalizationCase(
                contentId = "tvdb:series:393268",
                expectedRequestContentId = "tvdb:393268",
                expectedPreviewStableIds = ProviderIds(tvdb = "393268")
            ),
            RequestNormalizationCase(
                contentId = "mal:anime:5114",
                expectedRequestContentId = "mal:5114",
                expectedPreviewStableIds = ProviderIds(mal = "5114")
            ),
            RequestNormalizationCase(
                contentId = "anilist:anime:21",
                expectedRequestContentId = "anilist:21",
                expectedPreviewStableIds = ProviderIds(anilist = "21")
            ),
            RequestNormalizationCase(
                contentId = "anidb:anime:23",
                expectedRequestContentId = "anidb:23",
                expectedPreviewStableIds = ProviderIds(anidb = "23")
            )
        )

        cases.forEach { case ->
            val requestSlot = slot<MetadataRequest>()
            coEvery {
                metadataRouterFacade.resolveStableIdBundle(
                    capture(requestSlot),
                    StableIdResolutionTrigger.CONTINUE_WATCHING,
                    any()
                )
            } returns citadelBundle()

            resolver.resolveOrFallback(
                RawContinueWatchingInput(
                    profileId = 1,
                    progress = citadelProgress(
                        contentId = case.contentId,
                        videoId = "${case.contentId}:1:1",
                        contentType = "series",
                        season = 1,
                        episode = 1
                    ),
                    languageTag = "en-US"
                )
            )

            assertEquals(
                "contentId=${case.contentId}",
                case.expectedRequestContentId,
                requestSlot.captured.contentId
            )
            assertEquals(
                "contentId=${case.contentId} preview tmdb",
                case.expectedPreviewStableIds.tmdb,
                requestSlot.captured.sourceContext.previewStableIds.tmdb
            )
            assertEquals(
                "contentId=${case.contentId} preview tvdb",
                case.expectedPreviewStableIds.tvdb,
                requestSlot.captured.sourceContext.previewStableIds.tvdb
            )
            assertEquals(
                "contentId=${case.contentId} preview mal",
                case.expectedPreviewStableIds.mal,
                requestSlot.captured.sourceContext.previewStableIds.mal
            )
            assertEquals(
                "contentId=${case.contentId} preview anilist",
                case.expectedPreviewStableIds.anilist,
                requestSlot.captured.sourceContext.previewStableIds.anilist
            )
            assertEquals(
                "contentId=${case.contentId} preview anidb",
                case.expectedPreviewStableIds.anidb,
                requestSlot.captured.sourceContext.previewStableIds.anidb
            )
        }
    }

    @Test
    fun `typed anime sidecar ids resolve continue watching record as anime`() = runTest {
        val cases = listOf(
            AnimeProviderCase(
                contentId = "mal:anime:5114",
                observedIds = ProviderIds(mal = "5114"),
                sidecars = SidecarStableIds(malId = "5114"),
                expectedStableKey = "profile:1:anime:mal:5114:s1e1"
            ),
            AnimeProviderCase(
                contentId = "anilist:anime:9253",
                observedIds = ProviderIds(anilist = "9253"),
                sidecars = SidecarStableIds(anilistId = "9253"),
                expectedStableKey = "profile:1:anime:anilist:9253:s1e1"
            ),
            AnimeProviderCase(
                contentId = "anidb:anime:6107",
                observedIds = ProviderIds(anidb = "6107"),
                sidecars = SidecarStableIds(anidbId = "6107"),
                expectedStableKey = "profile:1:anime:anidb:6107:s1e1"
            )
        )

        cases.forEach { case ->
            coEvery {
                metadataRouterFacade.resolveStableIdBundle(any(), any(), any())
            } returns animeBundle(
                observedIds = case.observedIds,
                sidecars = case.sidecars
            )

            val record = resolver.resolveOrFallback(
                RawContinueWatchingInput(
                    profileId = 1,
                    progress = citadelProgress(
                        contentId = case.contentId,
                        videoId = "${case.contentId}:1:1",
                        contentType = "series",
                        season = 1,
                        episode = 1
                    ),
                    languageTag = "en-US"
                )
            )

            assertEquals("contentId=${case.contentId}", MetadataMediaKind.ANIME, record.canonicalKey?.mediaKind)
            assertEquals("contentId=${case.contentId}", case.expectedStableKey, record.canonicalKey?.stableKey())
        }
    }

    private fun citadelBundle(
        sidecars: SidecarStableIds = SidecarStableIds(imdbId = "tt9794044")
    ): StableIdBundle =
        StableIdBundle(
            itemKey = "series:tvdb:393268",
            itemType = ContentType.SERIES,
            canonical = CanonicalStableIds(tvdbSeriesId = "393268"),
            sidecars = sidecars,
            source = SourceStableIds(
                sourceProvider = ProviderId.TVDB,
                sourceItemId = "393268",
                railId = null,
                observedIds = ProviderIds(tvdb = "393268")
            ),
            evidence = emptyList(),
            resolvedAtMs = 1_700_000_000_000L
        )

    private fun movieBundle(
        sidecars: SidecarStableIds = SidecarStableIds(imdbId = "tt1234567")
    ): StableIdBundle =
        StableIdBundle(
            itemKey = "movie:tmdb:123",
            itemType = ContentType.MOVIE,
            canonical = CanonicalStableIds(tmdbMovieId = "123"),
            sidecars = sidecars,
            source = SourceStableIds(
                sourceProvider = ProviderId.TMDB,
                sourceItemId = "123",
                railId = null,
                observedIds = ProviderIds(tmdb = "123")
            ),
            evidence = emptyList(),
            resolvedAtMs = 1_700_000_000_000L
        )

    private fun animeBundle(
        canonical: CanonicalStableIds = CanonicalStableIds(),
        observedIds: ProviderIds,
        sidecars: SidecarStableIds
    ): StableIdBundle =
        StableIdBundle(
            itemKey = "anime:identity",
            itemType = ContentType.SERIES,
            canonical = canonical,
            sidecars = sidecars,
            source = SourceStableIds(
                sourceProvider = ProviderId.KITSU,
                sourceItemId = "42",
                railId = null,
                observedIds = observedIds
            ),
            evidence = emptyList(),
            resolvedAtMs = 1_700_000_000_000L
        )

    private data class AnimeProviderCase(
        val contentId: String,
        val observedIds: ProviderIds,
        val sidecars: SidecarStableIds,
        val expectedStableKey: String
    )

    private data class RequestNormalizationCase(
        val contentId: String,
        val expectedRequestContentId: String,
        val expectedPreviewStableIds: ProviderIds
    )

    private fun citadelProgress(
        contentId: String = "tt9794044",
        videoId: String = "tt9794044:2:1",
        source: String = WatchProgress.SOURCE_LOCAL,
        contentType: String = "series",
        season: Int? = 2,
        episode: Int? = 1
    ): WatchProgress =
        WatchProgress(
            contentId = contentId,
            contentType = contentType,
            name = "Citadel",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = "Episode 1",
            position = 65_066L,
            duration = 2_958_656L,
            lastWatched = 1_778_171_360_859L,
            source = source,
            traktPlaybackId = if (source == WatchProgress.SOURCE_TRAKT_PLAYBACK) 42L else null
        )
}
