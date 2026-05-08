package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TrackingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContinueWatchingRecordTest {
    @Test
    fun `identity key includes profile parent and episode key`() {
        val record = ContinueWatchingRecord(
            profileId = 2,
            parentId = "tt1234",
            contentId = "tt1234:s1e3",
            provider = TrackingProvider.TRAKT,
            routingVersion = 4,
            positionMs = 1000L,
            durationMs = 5000L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(season = 1, number = 3),
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.LOCAL,
            updatedAt = 1_700_000_000_000L
        )
        assertEquals("profile:2:continue-watching:tt1234:s1e3", record.identityKey())
    }

    @Test
    fun `identity key falls back to parentId when episode context is null`() {
        val record = ContinueWatchingRecord(
            profileId = 1,
            parentId = "tt9999",
            contentId = "tt9999",
            provider = TrackingProvider.SIMKL,
            routingVersion = 1,
            positionMs = 100L,
            durationMs = 1000L,
            episodeContext = null,
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.SYNTHETIC,
            updatedAt = 1L
        )
        assertEquals("profile:1:continue-watching:tt9999", record.identityKey())
    }

    @Test
    fun `rejects non-positive profileId`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContinueWatchingRecord(
                profileId = 0,
                parentId = "x",
                contentId = "x",
                provider = TrackingProvider.TRAKT,
                routingVersion = 1,
                positionMs = 0L,
                durationMs = 1L,
                episodeContext = null,
                clickTimeDisplayMetadata = null,
                source = ContinueWatchingRecord.Source.LOCAL,
                updatedAt = 1L
            )
        }
    }

    @Test
    fun `record identity key prefers canonical key and exposes resume aliases`() {
        val resume = ResumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = "tvdb:393268",
            videoId = "tvdb:393268:2:1",
            season = 2,
            episode = 1,
            positionMs = 65_066L,
            durationMs = 2_958_656L,
            progressPercent = null,
            lastWatchedMs = 200L
        )
        val record = ContinueWatchingRecord(
            profileId = 1,
            parentId = "series:tvdb:393268",
            contentId = "series:tvdb:393268:s2e1",
            provider = TrackingProvider.TRAKT,
            routingVersion = 1,
            positionMs = 65_066L,
            durationMs = 2_958_656L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(2, 1),
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.LOCAL,
            updatedAt = 200L,
            canonicalKey = ContinueWatchingCanonicalKey(
                mediaKind = MetadataMediaKind.SERIES,
                canonicalParent = ContentIdentity(
                    canonicalProvider = ProviderId.TVDB,
                    canonicalId = "393268",
                    providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
                ),
                season = 2,
                episode = 1,
                profileId = 1
            ),
            resumeIdentities = listOf(resume),
            streamFetchIdentity = StreamFetchIdentity(
                "tvdb:393268",
                "tt9794044:2:1",
                StreamIdScheme.IMDB_EPISODE,
                IdentityConfidence.HIGH,
                listOf("test")
            ),
            identityConfidence = IdentityConfidence.HIGH
        )

        assertEquals("profile:1:series:tvdb:393268:s2e1", record.identityKey())
        assertEquals(setOf(resume.lookupKey()), record.resumeLookupKeys)
        assertEquals(resume.lookupKey(), record.primaryResumeLookupKey)
    }

    @Test
    fun `low confidence legacy record remains valid when identity is unresolved`() {
        val record = ContinueWatchingRecord(
            profileId = 1,
            parentId = "series:raw:abc",
            contentId = "series:raw:abc:s1e1",
            provider = TrackingProvider.TRAKT,
            routingVersion = 1,
            positionMs = 10L,
            durationMs = 100L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(1, 1),
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.LOCAL,
            updatedAt = 1L,
            identityConfidence = IdentityConfidence.LOW,
            identityWarnings = listOf("identity resolution failed")
        )

        assertEquals("profile:1:continue-watching:series:raw:abc:s1e1", record.identityKey())
        assertEquals(listOf("identity resolution failed"), record.identityWarnings)
    }

    @Test
    fun `rejects canonical key for different profile`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ContinueWatchingRecord(
                profileId = 1,
                parentId = "series:tvdb:393268",
                contentId = "series:tvdb:393268:s2e1",
                provider = TrackingProvider.TRAKT,
                routingVersion = 1,
                positionMs = 10L,
                durationMs = 100L,
                episodeContext = ContinueWatchingRecord.EpisodeContext(2, 1),
                clickTimeDisplayMetadata = null,
                source = ContinueWatchingRecord.Source.LOCAL,
                updatedAt = 1L,
                canonicalKey = ContinueWatchingCanonicalKey(
                    mediaKind = MetadataMediaKind.SERIES,
                    canonicalParent = ContentIdentity(
                        canonicalProvider = ProviderId.TVDB,
                        canonicalId = "393268",
                        providerIds = ProviderIds(tvdb = "393268")
                    ),
                    season = 2,
                    episode = 1,
                    profileId = 2
                )
            )
        }

        assertEquals("canonicalKey.profileId must match profileId", error.message)
    }

    @Test
    fun `rejects primary resume lookup key outside derived aliases`() {
        val resume = ResumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = "tvdb:393268",
            videoId = "tvdb:393268:2:1",
            season = 2,
            episode = 1,
            positionMs = 10L,
            durationMs = 100L,
            progressPercent = null,
            lastWatchedMs = 1L
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            ContinueWatchingRecord(
                profileId = 1,
                parentId = "series:tvdb:393268",
                contentId = "series:tvdb:393268:s2e1",
                provider = TrackingProvider.TRAKT,
                routingVersion = 1,
                positionMs = 10L,
                durationMs = 100L,
                episodeContext = ContinueWatchingRecord.EpisodeContext(2, 1),
                clickTimeDisplayMetadata = null,
                source = ContinueWatchingRecord.Source.LOCAL,
                updatedAt = 1L,
                resumeIdentities = listOf(resume),
                primaryResumeLookupKey = "missing"
            )
        }

        assertEquals("primaryResumeLookupKey must reference one of resumeLookupKeys", error.message)
    }

    @Test
    fun `rejects canonical episode key that does not match episode context`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContinueWatchingRecord(
                profileId = 1,
                parentId = "series:tvdb:393268",
                contentId = "series:tvdb:393268:s2e1",
                provider = TrackingProvider.TRAKT,
                routingVersion = 1,
                positionMs = 10L,
                durationMs = 100L,
                episodeContext = ContinueWatchingRecord.EpisodeContext(2, 1),
                clickTimeDisplayMetadata = null,
                source = ContinueWatchingRecord.Source.LOCAL,
                updatedAt = 1L,
                canonicalKey = ContinueWatchingCanonicalKey(
                    mediaKind = MetadataMediaKind.SERIES,
                    canonicalParent = stableTvdbIdentity(),
                    season = 2,
                    episode = 2,
                    profileId = 1
                )
            )
        }
    }

    @Test
    fun `rejects episode canonical key for parent record`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContinueWatchingRecord(
                profileId = 1,
                parentId = "series:tvdb:393268",
                contentId = "series:tvdb:393268",
                provider = TrackingProvider.TRAKT,
                routingVersion = 1,
                positionMs = 10L,
                durationMs = 100L,
                episodeContext = null,
                clickTimeDisplayMetadata = null,
                source = ContinueWatchingRecord.Source.LOCAL,
                updatedAt = 1L,
                canonicalKey = ContinueWatchingCanonicalKey(
                    mediaKind = MetadataMediaKind.SERIES,
                    canonicalParent = stableTvdbIdentity(),
                    season = 2,
                    episode = 1,
                    profileId = 1
                )
            )
        }
    }

    @Test
    fun `rejects canonical key that cannot produce stable key`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContinueWatchingRecord(
                profileId = 1,
                parentId = "series:raw:abc",
                contentId = "series:raw:abc",
                provider = TrackingProvider.TRAKT,
                routingVersion = 1,
                positionMs = 10L,
                durationMs = 100L,
                episodeContext = null,
                clickTimeDisplayMetadata = null,
                source = ContinueWatchingRecord.Source.LOCAL,
                updatedAt = 1L,
                canonicalKey = ContinueWatchingCanonicalKey(
                    mediaKind = MetadataMediaKind.SERIES,
                    canonicalParent = ContentIdentity(
                        canonicalProvider = ProviderId.MDBLIST,
                        canonicalId = "abc",
                        providerIds = ProviderIds()
                    ),
                    season = null,
                    episode = null,
                    profileId = 1
                )
            )
        }
    }

    private fun stableTvdbIdentity(): ContentIdentity =
        ContentIdentity(
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = ProviderIds(tvdb = "393268")
        )
}
