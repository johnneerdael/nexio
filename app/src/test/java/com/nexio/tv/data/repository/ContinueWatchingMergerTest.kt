package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TrackingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingMergerTest {
    @Test
    fun `merges tvdb local and imdb trakt rows into one record with both aliases`() {
        val localResume = resumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = "tvdb:393268",
            videoId = "tvdb:393268:2:1",
            positionMs = 65_066L,
            lastWatchedMs = 100L
        )
        val remoteResume = resumeIdentity(
            source = ContinueWatchingSource.TRAKT_PLAYBACK,
            contentId = "tt9794044",
            videoId = "tt9794044:2:1",
            positionMs = 12_000L,
            lastWatchedMs = 200L
        )

        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = localResume,
                    positionMs = 65_066L,
                    updatedAt = 100L,
                    streamFetchIdentity = streamFetchIdentity(
                        contentId = "tvdb:393268",
                        videoId = "tt9794044:2:1",
                        confidence = IdentityConfidence.HIGH
                    )
                ),
                record(
                    resumeIdentity = remoteResume,
                    positionMs = 12_000L,
                    updatedAt = 200L,
                    streamFetchIdentity = streamFetchIdentity(
                        contentId = "tt9794044",
                        videoId = "tt9794044:2:1",
                        confidence = IdentityConfidence.MEDIUM
                    )
                )
            )
        )

        assertEquals(1, merged.size)

        val record = merged.single()
        assertEquals("profile:1:series:tvdb:393268:s2e1", record.identityKey())
        assertEquals(
            setOf(localResume.lookupKey(), remoteResume.lookupKey()),
            record.resumeLookupKeys
        )
        assertEquals("tt9794044:2:1", record.streamFetchIdentity?.videoId)
    }

    @Test
    fun `merged record keeps progress winner as primary resume alias when remote zero progress is newer`() {
        val localResume = resumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = "tvdb:393268",
            videoId = "tvdb:393268:2:1",
            positionMs = 65_066L,
            lastWatchedMs = 100L
        )
        val remoteResume = resumeIdentity(
            source = ContinueWatchingSource.TRAKT_PLAYBACK,
            contentId = "tt9794044",
            videoId = "tt9794044:2:1",
            positionMs = 0L,
            lastWatchedMs = 200L
        )

        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = localResume,
                    positionMs = 65_066L,
                    updatedAt = 100L
                ),
                record(
                    resumeIdentity = remoteResume,
                    positionMs = 0L,
                    updatedAt = 200L
                )
            )
        ).single()

        assertEquals(65_066L, merged.positionMs)
        assertEquals(localResume.lookupKey(), merged.primaryResumeLookupKey)
        assertTrue(merged.primaryResumeLookupKey in merged.resumeLookupKeys)
    }

    @Test
    fun `newer remote percent only playback wins over older local stale progress`() {
        val localResume = resumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = "tvdb:393268",
            videoId = "tvdb:393268:2:1",
            positionMs = 65_066L,
            progressPercent = null,
            lastWatchedMs = 100L
        )
        val remoteResume = resumeIdentity(
            source = ContinueWatchingSource.TRAKT_PLAYBACK,
            contentId = "tt9794044",
            videoId = "tt9794044:2:1",
            positionMs = 0L,
            durationMs = 0L,
            progressPercent = 37.5f,
            lastWatchedMs = 200L
        )

        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = localResume,
                    positionMs = 65_066L,
                    updatedAt = 100L
                ),
                record(
                    resumeIdentity = remoteResume,
                    positionMs = 0L,
                    updatedAt = 200L
                )
            )
        ).single()

        assertEquals(0L, merged.positionMs)
        assertEquals(remoteResume.lookupKey(), merged.primaryResumeLookupKey)
        assertTrue(merged.primaryResumeLookupKey in merged.resumeLookupKeys)
    }

    @Test
    fun `merged record keeps strongest stream identity confidence`() {
        val lowConfidenceStream = streamFetchIdentity(
            contentId = "tt9794044",
            videoId = "tt9794044:2:1",
            confidence = IdentityConfidence.LOW
        )
        val highConfidenceStream = streamFetchIdentity(
            contentId = "tvdb:393268",
            videoId = "tt9794044:2:1",
            confidence = IdentityConfidence.HIGH
        )

        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = resumeIdentity(source = ContinueWatchingSource.TRAKT_PLAYBACK),
                    positionMs = 10L,
                    updatedAt = 200L,
                    streamFetchIdentity = lowConfidenceStream,
                    identityConfidence = IdentityConfidence.LOW
                ),
                record(
                    resumeIdentity = resumeIdentity(source = ContinueWatchingSource.LOCAL),
                    positionMs = 20L,
                    updatedAt = 100L,
                    streamFetchIdentity = highConfidenceStream,
                    identityConfidence = IdentityConfidence.HIGH
                )
            )
        ).single()

        assertEquals(highConfidenceStream, merged.streamFetchIdentity)
        assertEquals(IdentityConfidence.HIGH, merged.identityConfidence)
    }

    @Test
    fun `merged record combines warnings and preserves tracking identity`() {
        val tracking = TrackingIdentity(
            traktShowId = 123,
            traktEpisodeId = 456,
            traktPlaybackId = 789L,
            providerIds = ProviderIds(imdb = "tt9794044", trakt = "123")
        )

        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = resumeIdentity(source = ContinueWatchingSource.LOCAL),
                    positionMs = 10L,
                    updatedAt = 100L,
                    identityWarnings = listOf("missing remote metadata", "shared warning")
                ),
                record(
                    resumeIdentity = resumeIdentity(source = ContinueWatchingSource.TRAKT_PLAYBACK),
                    positionMs = 20L,
                    updatedAt = 200L,
                    trackingIdentity = tracking,
                    identityWarnings = listOf("shared warning", "used trakt fallback")
                )
            )
        ).single()

        assertEquals(tracking, merged.trackingIdentity)
        assertEquals(
            listOf("shared warning", "used trakt fallback", "missing remote metadata"),
            merged.identityWarnings
        )
    }

    @Test
    fun `cross-id merge collapses records sharing IMDB across different parentIds`() {
        val tmdbResume = resumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = "tmdb:1396",
            videoId = "tmdb:1396:5:14",
            positionMs = 5_000L,
            lastWatchedMs = 100L,
        )
        val imdbResume = resumeIdentity(
            source = ContinueWatchingSource.TRAKT_PLAYBACK,
            contentId = "tt0903747",
            videoId = "tt0903747:5:14",
            positionMs = 10_000L,
            lastWatchedMs = 200L,
        )

        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = tmdbResume,
                    positionMs = 5_000L,
                    updatedAt = 100L,
                    parentId = "series:tmdb:1396",
                    contentIdKey = "series:tmdb:1396:s5e14",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(
                        imdb = "tt0903747", tmdb = "1396", season = 5, episode = 14,
                    ),
                ),
                record(
                    resumeIdentity = imdbResume,
                    positionMs = 10_000L,
                    updatedAt = 200L,
                    parentId = "series:imdb:tt0903747",
                    contentIdKey = "series:imdb:tt0903747:s5e14",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(
                        imdb = "tt0903747", season = 5, episode = 14,
                    ),
                ),
            )
        )

        assertEquals(1, merged.size)
        assertEquals(
            setOf(tmdbResume.lookupKey(), imdbResume.lookupKey()),
            merged.single().resumeLookupKeys
        )
    }

    @Test
    fun `cross-id merge handles tmdb only overlap when imdb absent on one side`() {
        val onlyTmdb = resumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = "tmdb:1396",
            videoId = "tmdb:1396:5:14",
            positionMs = 5_000L,
            lastWatchedMs = 100L,
        )
        val tmdbAndImdb = resumeIdentity(
            source = ContinueWatchingSource.TRAKT_PLAYBACK,
            contentId = "tt0903747",
            videoId = "tt0903747:5:14",
            positionMs = 10_000L,
            lastWatchedMs = 200L,
        )

        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = onlyTmdb,
                    positionMs = 5_000L,
                    updatedAt = 100L,
                    parentId = "x",
                    contentIdKey = "x:s5e14",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(tmdb = "1396", season = 5, episode = 14),
                ),
                record(
                    resumeIdentity = tmdbAndImdb,
                    positionMs = 10_000L,
                    updatedAt = 200L,
                    parentId = "y",
                    contentIdKey = "y:s5e14",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(
                        imdb = "tt0903747", tmdb = "1396", season = 5, episode = 14,
                    ),
                ),
            )
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun `cross-provider conflict picks fresher candidate when trivial delta`() {
        val trakt = resumeIdentity(
            source = ContinueWatchingSource.TRAKT_PLAYBACK,
            contentId = "tt0903747",
            videoId = "tt0903747:5:14",
            positionMs = 50_000L,
            durationMs = 100_000L,
            lastWatchedMs = 1000L,
        )
        val simkl = resumeIdentity(
            source = ContinueWatchingSource.LOCAL, // ContinueWatchingSource enum has no SIMKL_*; use any source for resume id
            contentId = "tt0903747",
            videoId = "tt0903747:5:14",
            positionMs = 55_000L,
            durationMs = 100_000L,
            lastWatchedMs = 2000L,
        )

        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = trakt,
                    positionMs = 50_000L,
                    updatedAt = 1000L,
                    parentId = "x",
                    contentIdKey = "x:s5e14",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt0903747", season = 5, episode = 14),
                ).copy(provider = TrackingProvider.TRAKT, durationMs = 100_000L),
                record(
                    resumeIdentity = simkl,
                    positionMs = 55_000L,
                    updatedAt = 2000L,
                    parentId = "y",
                    contentIdKey = "y:s5e14",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt0903747", season = 5, episode = 14),
                ).copy(provider = TrackingProvider.SIMKL, durationMs = 100_000L),
            )
        )

        assertEquals(1, merged.size)
        // Trivial 5-second delta -> newer (SIMKL) wins.
        assertEquals(TrackingProvider.SIMKL, merged.single().provider)
    }

    @Test
    fun `cross-provider conflict keeps leader on meaningful delta despite older timestamp`() {
        val leaderResume = resumeIdentity(
            source = ContinueWatchingSource.TRAKT_PLAYBACK,
            contentId = "tt1",
            videoId = "tt1:1:1",
            positionMs = 80_000L,
            durationMs = 100_000L,
            lastWatchedMs = 1000L,
        )
        val trailerResume = resumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = "tt1",
            videoId = "tt1:1:1",
            positionMs = 10_000L,
            durationMs = 100_000L,
            lastWatchedMs = 2000L,
        )

        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = leaderResume,
                    positionMs = 80_000L,
                    updatedAt = 1000L,
                    parentId = "p1",
                    contentIdKey = "p1:s1e1",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt1", season = 1, episode = 1),
                ).copy(provider = TrackingProvider.TRAKT, durationMs = 100_000L),
                record(
                    resumeIdentity = trailerResume,
                    positionMs = 10_000L,
                    updatedAt = 2000L,
                    parentId = "p2",
                    contentIdKey = "p2:s1e1",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt1", season = 1, episode = 1),
                ).copy(provider = TrackingProvider.SIMKL, durationMs = 100_000L),
            )
        )

        // 70s lead is well above the 30s threshold; leader (TRAKT) wins.
        assertEquals(1, merged.size)
        assertEquals(TrackingProvider.TRAKT, merged.single().provider)
    }

    @Test
    fun `episode records only merge when season and episode also match`() {
        val s5e14 = resumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = "tt0903747",
            videoId = "tt0903747:5:14",
            positionMs = 5_000L,
            lastWatchedMs = 100L,
        )
        val s5e15 = resumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = "tt0903747",
            videoId = "tt0903747:5:15",
            positionMs = 6_000L,
            lastWatchedMs = 200L,
        )

        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = s5e14,
                    positionMs = 5_000L,
                    updatedAt = 100L,
                    parentId = "series:imdb:tt0903747",
                    contentIdKey = "series:imdb:tt0903747:s5e14",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt0903747", season = 5, episode = 14),
                ),
                record(
                    resumeIdentity = s5e15,
                    positionMs = 6_000L,
                    updatedAt = 200L,
                    parentId = "series:imdb:tt0903747",
                    contentIdKey = "series:imdb:tt0903747:s5e15",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt0903747", season = 5, episode = 15),
                ),
            )
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `near-complete cross-provider records are dropped from merged output`() {
        val nearTrakt = resumeIdentity(
            source = ContinueWatchingSource.TRAKT_PLAYBACK,
            contentId = "tt-near", videoId = "tt-near:1:1",
            positionMs = 95_000L, durationMs = 100_000L, lastWatchedMs = 1000L,
        )
        val nearSimkl = resumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = "tt-near", videoId = "tt-near:1:1",
            positionMs = 96_000L, durationMs = 100_000L, lastWatchedMs = 2000L,
        )
        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = nearTrakt,
                    positionMs = 95_000L,
                    updatedAt = 1000L,
                    parentId = "x",
                    contentIdKey = "x:s1e1",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt-near", season = 1, episode = 1),
                ).copy(provider = TrackingProvider.TRAKT, durationMs = 100_000L),
                record(
                    resumeIdentity = nearSimkl,
                    positionMs = 96_000L,
                    updatedAt = 2000L,
                    parentId = "y",
                    contentIdKey = "y:s1e1",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt-near", season = 1, episode = 1),
                ).copy(provider = TrackingProvider.SIMKL, durationMs = 100_000L),
            )
        )
        assertEquals(0, merged.size)
    }

    @Test
    fun `near-complete same-provider records are also dropped`() {
        val a = resumeIdentity(
            source = ContinueWatchingSource.TRAKT_PLAYBACK,
            contentId = "tt-same", videoId = "tt-same:1:1",
            positionMs = 95_500L, durationMs = 100_000L, lastWatchedMs = 1000L,
        )
        val b = resumeIdentity(
            source = ContinueWatchingSource.TRAKT_HISTORY,
            contentId = "tt-same", videoId = "tt-same:1:1",
            positionMs = 100_000L, durationMs = 100_000L, lastWatchedMs = 2000L,
        )
        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = a,
                    positionMs = 95_500L,
                    updatedAt = 1000L,
                    parentId = "x",
                    contentIdKey = "x:s1e1",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt-same", season = 1, episode = 1),
                ).copy(provider = TrackingProvider.TRAKT, durationMs = 100_000L),
                record(
                    resumeIdentity = b,
                    positionMs = 100_000L,
                    updatedAt = 2000L,
                    parentId = "x",
                    contentIdKey = "x:s1e1",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt-same", season = 1, episode = 1),
                ).copy(provider = TrackingProvider.TRAKT, durationMs = 100_000L),
            )
        )
        assertEquals(0, merged.size)
    }

    @Test
    fun `records at the 95 percent boundary still drop`() {
        val onBoundary = resumeIdentity(
            source = ContinueWatchingSource.TRAKT_PLAYBACK,
            contentId = "tt-edge", videoId = "tt-edge:1:1",
            positionMs = 95_000L, durationMs = 100_000L, lastWatchedMs = 1000L,
        )
        val merged = ContinueWatchingMerger.merge(
            listOf(
                record(
                    resumeIdentity = onBoundary,
                    positionMs = 95_000L,
                    updatedAt = 1000L,
                    parentId = "x",
                    contentIdKey = "x:s1e1",
                    canonicalKey = null,
                    idBundle = ContinueWatchingIdBundle(imdb = "tt-edge", season = 1, episode = 1),
                ).copy(provider = TrackingProvider.TRAKT, durationMs = 100_000L),
            )
        )
        assertEquals(0, merged.size)
    }

    private fun record(
        resumeIdentity: ResumeIdentity,
        positionMs: Long,
        updatedAt: Long,
        streamFetchIdentity: StreamFetchIdentity? = null,
        trackingIdentity: TrackingIdentity? = null,
        identityConfidence: IdentityConfidence = IdentityConfidence.HIGH,
        identityWarnings: List<String> = emptyList(),
        parentId: String = "series:tvdb:393268",
        contentIdKey: String = "series:tvdb:393268:s2e1",
        canonicalKey: ContinueWatchingCanonicalKey? = canonicalKey(),
        idBundle: ContinueWatchingIdBundle = ContinueWatchingIdBundle(),
    ): ContinueWatchingRecord =
        ContinueWatchingRecord(
            profileId = 1,
            parentId = parentId,
            contentId = contentIdKey,
            provider = TrackingProvider.TRAKT,
            routingVersion = 1,
            positionMs = positionMs,
            durationMs = resumeIdentity.durationMs ?: 2_958_656L,
            episodeContext = resumeIdentity.season?.let { season ->
                resumeIdentity.episode?.let { number ->
                    ContinueWatchingRecord.EpisodeContext(season, number)
                }
            },
            clickTimeDisplayMetadata = null,
            source = when (resumeIdentity.source) {
                ContinueWatchingSource.LOCAL -> ContinueWatchingRecord.Source.LOCAL
                ContinueWatchingSource.SYNTHETIC -> ContinueWatchingRecord.Source.SYNTHETIC
                ContinueWatchingSource.TRAKT_PLAYBACK,
                ContinueWatchingSource.TRAKT_HISTORY,
                ContinueWatchingSource.TRAKT_SHOW_PROGRESS -> ContinueWatchingRecord.Source.REMOTE
            },
            updatedAt = updatedAt,
            canonicalKey = canonicalKey,
            displayIdentity = displayIdentity(),
            streamFetchIdentity = streamFetchIdentity,
            trackingIdentity = trackingIdentity,
            resumeIdentities = listOf(resumeIdentity),
            primaryResumeLookupKey = resumeIdentity.lookupKey(),
            identityConfidence = identityConfidence,
            identityWarnings = identityWarnings,
            idBundle = idBundle,
        )

    private fun canonicalKey(): ContinueWatchingCanonicalKey =
        ContinueWatchingCanonicalKey(
            mediaKind = MetadataMediaKind.SERIES,
            canonicalParent = displayIdentity(),
            season = 2,
            episode = 1,
            profileId = 1
        )

    private fun displayIdentity(): ContentIdentity =
        ContentIdentity(
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
        )

    private fun resumeIdentity(
        source: ContinueWatchingSource,
        contentId: String = "tvdb:393268",
        videoId: String = "tvdb:393268:2:1",
        positionMs: Long = 65_066L,
        durationMs: Long? = 2_958_656L,
        progressPercent: Float? = null,
        lastWatchedMs: Long = 100L
    ): ResumeIdentity =
        ResumeIdentity(
            source = source,
            contentId = contentId,
            videoId = videoId,
            season = 2,
            episode = 1,
            positionMs = positionMs,
            durationMs = durationMs,
            progressPercent = progressPercent,
            lastWatchedMs = lastWatchedMs
        )

    private fun streamFetchIdentity(
        contentId: String,
        videoId: String,
        confidence: IdentityConfidence
    ): StreamFetchIdentity =
        StreamFetchIdentity(
            contentId = contentId,
            videoId = videoId,
            idScheme = StreamIdScheme.IMDB_EPISODE,
            confidence = confidence,
            trace = listOf("test")
        )
}
