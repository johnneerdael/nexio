package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingIdentityModelsTest {
    @Test
    fun `canonical key includes profile canonical parent and episode coordinate`() {
        val key = ContinueWatchingCanonicalKey(
            mediaKind = MetadataMediaKind.SERIES,
            canonicalParent = ContentIdentity(
                canonicalProvider = ProviderId.TVDB,
                canonicalId = "393268",
                providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
            ),
            season = 2,
            episode = 1,
            profileId = 1
        )

        assertEquals("profile:1:series:tvdb:393268:s2e1", key.stableKey())
    }

    @Test
    fun `resume lookup key preserves raw resume identity`() {
        val resume = ResumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = "tvdb:393268",
            videoId = "tvdb:393268:2:1",
            season = 2,
            episode = 1,
            positionMs = 65_066L,
            durationMs = 2_958_656L,
            progressPercent = null,
            lastWatchedMs = 1_778_171_360_859L
        )

        assertEquals("tvdb:393268|tvdb:393268:2:1|2|1", resume.lookupKey())
        assertTrue(resume.isEpisode)
    }

    @Test
    fun `resume lookup key does not collide when raw ids contain separator`() {
        val first = validResumeIdentity(contentId = "a|b", videoId = "c", season = null, episode = null)
        val second = validResumeIdentity(contentId = "a", videoId = "b|c", season = null, episode = null)

        assertNotEquals(first.lookupKey(), second.lookupKey())
    }

    @Test
    fun `canonical key rejects partial episode coordinate`() {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = ProviderIds(tvdb = "393268")
        )

        assertThrows(IllegalArgumentException::class.java) {
            ContinueWatchingCanonicalKey(
                mediaKind = MetadataMediaKind.SERIES,
                canonicalParent = identity,
                season = 2,
                episode = null,
                profileId = 1
            )
        }
    }

    @Test
    fun `canonical key rejects non positive episode coordinate`() {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = ProviderIds(tvdb = "393268")
        )

        assertThrows(IllegalArgumentException::class.java) {
            ContinueWatchingCanonicalKey(
                mediaKind = MetadataMediaKind.SERIES,
                canonicalParent = identity,
                season = 0,
                episode = 1,
                profileId = 1
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContinueWatchingCanonicalKey(
                mediaKind = MetadataMediaKind.SERIES,
                canonicalParent = identity,
                season = 1,
                episode = -1,
                profileId = 1
            )
        }
    }

    @Test
    fun `canonical key rejects non global parent without provider ids`() {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.ADDON,
            canonicalId = "42",
            providerIds = ProviderIds()
        )

        val key = ContinueWatchingCanonicalKey(
            mediaKind = MetadataMediaKind.SERIES,
            canonicalParent = identity,
            season = null,
            episode = null,
            profileId = 1
        )

        assertThrows(IllegalArgumentException::class.java) {
            key.stableKey()
        }
    }

    @Test
    fun `canonical key accepts anime sidecar provider ids`() {
        val key = ContinueWatchingCanonicalKey(
            mediaKind = MetadataMediaKind.SERIES,
            canonicalParent = ContentIdentity(
                canonicalProvider = ProviderId.ADDON,
                canonicalId = "42",
                providerIds = ProviderIds(mal = "5114")
            ),
            season = 1,
            episode = 1,
            profileId = 1
        )

        assertEquals("profile:1:series:mal:5114:s1e1", key.stableKey())
    }

    @Test
    fun `resume identity rejects invalid raw progress values`() {
        assertThrows(IllegalArgumentException::class.java) {
            validResumeIdentity(contentId = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validResumeIdentity(videoId = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validResumeIdentity(positionMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validResumeIdentity(durationMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validResumeIdentity(season = 2, episode = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validResumeIdentity(season = 0, episode = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validResumeIdentity(season = 1, episode = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validResumeIdentity(progressPercent = 101f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validResumeIdentity(lastWatchedMs = -1L)
        }
    }

    @Test
    fun `tracking identity is null when no tracking ids exist`() {
        val progress = WatchProgress(
            contentId = "tvdb:393268",
            contentType = "series",
            name = "Citadel",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tvdb:393268:2:1",
            season = 2,
            episode = 1,
            episodeTitle = "Episode 1",
            position = 65_066L,
            duration = 2_958_656L,
            lastWatched = 1_778_171_360_859L
        )

        assertNull(progress.toTrackingIdentity())
    }

    @Test
    fun `tracking identity preserves tracking ids when present`() {
        val progress = validWatchProgress(
            traktPlaybackId = 42L,
            traktMovieId = 100,
            traktShowId = 200,
            traktEpisodeId = 300
        )

        val tracking = progress.toTrackingIdentity()

        assertNotNull(tracking)
        assertEquals(42L, tracking!!.traktPlaybackId)
        assertEquals(100, tracking.traktMovieId)
        assertEquals(200, tracking.traktShowId)
        assertEquals(300, tracking.traktEpisodeId)
        assertEquals("tt9794044", tracking.providerIds.imdb)
        assertEquals("200", tracking.providerIds.trakt)
    }

    @Test
    fun `tracking identity uses trakt movie id when show id is absent`() {
        val progress = validWatchProgress(
            traktMovieId = 100,
            traktShowId = null
        )

        val tracking = progress.toTrackingIdentity()

        assertNotNull(tracking)
        assertEquals("100", tracking!!.providerIds.trakt)
    }

    @Test
    fun `tracking identity preserves prefixed provider id from content id`() {
        val cases = listOf(
            "imdb:tt9794044" to ProviderIds(imdb = "tt9794044"),
            "tt9794044" to ProviderIds(imdb = "tt9794044"),
            "tmdb:123" to ProviderIds(tmdb = "123"),
            "tvdb:393268" to ProviderIds(tvdb = "393268"),
            "kitsu:456" to ProviderIds(kitsu = "456"),
            "trakt:789" to ProviderIds(trakt = "789"),
            "simkl:321" to ProviderIds(simkl = "321"),
            "mal:5114" to ProviderIds(mal = "5114"),
            "anilist:9253" to ProviderIds(anilist = "9253"),
            "anidb:6107" to ProviderIds(anidb = "6107")
        )

        cases.forEach { (contentId, expected) ->
            val tracking = validWatchProgress(
                contentId = contentId,
                traktPlaybackId = 42L
            ).toTrackingIdentity()

            assertNotNull(tracking)
            assertEquals("contentId=$contentId imdb", expected.imdb, tracking!!.providerIds.imdb)
            assertEquals("contentId=$contentId tmdb", expected.tmdb, tracking.providerIds.tmdb)
            assertEquals("contentId=$contentId tvdb", expected.tvdb, tracking.providerIds.tvdb)
            assertEquals("contentId=$contentId kitsu", expected.kitsu, tracking.providerIds.kitsu)
            assertEquals("contentId=$contentId trakt", expected.trakt, tracking.providerIds.trakt)
            assertEquals("contentId=$contentId simkl", expected.simkl, tracking.providerIds.simkl)
            assertEquals("contentId=$contentId mal", expected.mal, tracking.providerIds.mal)
            assertEquals("contentId=$contentId anilist", expected.anilist, tracking.providerIds.anilist)
            assertEquals("contentId=$contentId anidb", expected.anidb, tracking.providerIds.anidb)
        }
    }

    @Test
    fun `stream fetch identity validates plan shape inputs`() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamFetchIdentity(
                contentId = " ",
                videoId = "tt9794044:2:1",
                idScheme = StreamIdScheme.IMDB_EPISODE,
                confidence = IdentityConfidence.HIGH,
                trace = listOf("test")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamFetchIdentity(
                contentId = "tt9794044",
                videoId = "",
                idScheme = StreamIdScheme.IMDB_EPISODE,
                confidence = IdentityConfidence.HIGH,
                trace = listOf("test")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamFetchIdentity(
                contentId = "tt9794044",
                videoId = "tt9794044:2:1",
                idScheme = StreamIdScheme.IMDB_EPISODE,
                confidence = IdentityConfidence.HIGH,
                trace = listOf(" ")
            )
        }
    }

    @Test
    fun `raw continue watching input uses progress and language tag`() {
        val progress = validWatchProgress()
        val input = RawContinueWatchingInput(
            profileId = 1,
            progress = progress,
            languageTag = "en-US"
        )

        assertEquals(progress, input.progress)
        assertEquals("en-US", input.languageTag)
    }

    private fun validResumeIdentity(
        contentId: String = "tvdb:393268",
        videoId: String = "tvdb:393268:2:1",
        season: Int? = 2,
        episode: Int? = 1,
        positionMs: Long = 65_066L,
        durationMs: Long? = 2_958_656L,
        progressPercent: Float? = null,
        lastWatchedMs: Long = 1_778_171_360_859L
    ): ResumeIdentity =
        ResumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = contentId,
            videoId = videoId,
            season = season,
            episode = episode,
            positionMs = positionMs,
            durationMs = durationMs,
            progressPercent = progressPercent,
            lastWatchedMs = lastWatchedMs
        )

    private fun validWatchProgress(
        contentId: String = "tt9794044",
        traktPlaybackId: Long? = null,
        traktMovieId: Int? = null,
        traktShowId: Int? = null,
        traktEpisodeId: Int? = null
    ): WatchProgress =
        WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = "Citadel",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tvdb:393268:2:1",
            season = 2,
            episode = 1,
            episodeTitle = "Episode 1",
            position = 65_066L,
            duration = 2_958_656L,
            lastWatched = 1_778_171_360_859L,
            traktPlaybackId = traktPlaybackId,
            traktMovieId = traktMovieId,
            traktShowId = traktShowId,
            traktEpisodeId = traktEpisodeId
        )
}
