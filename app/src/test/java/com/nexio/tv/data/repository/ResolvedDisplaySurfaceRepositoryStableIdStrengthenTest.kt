package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.TrailerDisplayState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Post-first-paint, every hydrated rail item must carry IMDB alongside its
 * native provider id (TMDB / TVDB / KITSU). The cross-id enricher writes
 * resolved IDs into the Room cache and the catalog pipeline re-publishes the
 * item with strengthened stableIds, but the repository's non-downgrade merge
 * historically dropped strengthened IDs whenever the display slots were
 * already identical — the symptom was manual stream selection sending
 * `tmdb:...` to Stremio addons (which require IMDB), yielding "no streams".
 */
class ResolvedDisplaySurfaceRepositoryStableIdStrengthenTest {

    private val profileSession = ActiveProfileSession(
        profileId = 1,
        sessionId = "test-session",
        sessionOrdinal = 1L,
        startedAtMs = 1_001L
    )
    private val repo = ResolvedDisplaySurfaceRepository(activeProfileSession = { profileSession })

    private val nowMs = 1_700_000_000_000L
    private val itemKey = "movie:tmdb:687163"

    private fun legacyRef(url: String): ArtworkDisplayRef.LegacyString =
        ArtworkDisplayRef.LegacyString(
            value = url,
            imageType = ArtworkType.POSTER,
            trace = ArtworkTrace.empty()
        )

    private fun posterSlot(url: String, rank: DisplaySourceRank): ResolvedSlot<ArtworkDisplayRef> =
        ResolvedSlot(
            value = legacyRef(url),
            rank = rank,
            provider = "TMDB",
            role = null,
            updatedAtMs = nowMs,
            expiresAtMs = null,
            trace = emptyList()
        )

    private fun slots(url: String, rank: DisplaySourceRank): ResolvedDisplayFieldSlots =
        ResolvedDisplayFieldSlots(
            title = ResolvedSlot.empty(nowMs),
            originalTitle = ResolvedSlot.empty(nowMs),
            overview = ResolvedSlot.empty(nowMs),
            genres = ResolvedSlot.empty(nowMs),
            releaseInfo = ResolvedSlot.empty(nowMs),
            runtime = ResolvedSlot.empty(nowMs),
            rating = ResolvedSlot.empty(nowMs),
            poster = posterSlot(url, rank),
            backdrop = ResolvedSlot.empty(nowMs),
            logo = ResolvedSlot.empty(nowMs),
            thumbnail = ResolvedSlot.empty(nowMs),
            posterProviderTag = ResolvedSlot.empty(nowMs)
        )

    private fun item(stableIds: ProviderIds, rank: DisplaySourceRank): ResolvedDisplayItem {
        val s = slots("https://tmdb.example/p.jpg", rank)
        return ResolvedDisplayItem(
            itemKey = itemKey,
            contentId = "tmdb:687163",
            parentId = "tmdb:687163",
            itemType = ContentType.MOVIE,
            mediaKind = MetadataMediaKind.MOVIE,
            canonicalProvider = "TMDB",
            canonicalId = "687163",
            imdbId = stableIds.imdb,
            stableIds = stableIds,
            display = ResolvedDisplayFields(
                title = null, originalTitle = null, year = null,
                releaseDate = null, overview = null, genres = emptyList(),
                runtimeText = null
            ),
            artwork = ArtworkBundle(poster = s.poster.value),
            rating = null,
            trailer = TrailerDisplayState(),
            hydrationState = if (rank == DisplaySourceRank.RESOLVED)
                HydrationState.CANONICAL_READY else HydrationState.PREVIEW_ONLY,
            sourceTrace = emptyList(),
            updatedAtMs = nowMs,
            slots = s
        )
    }

    @Test
    fun `strengthened IMDB id surfaces even when display slots are identical`() = runTest {
        // 1) Cold first-paint: TMDB rail item, no IMDB resolved yet.
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(
                item(stableIds = ProviderIds(tmdb = "687163"), rank = DisplaySourceRank.RESOLVED)
            ),
            replace = true
        )

        // 2) Cross-id enricher resolves IMDB; pipeline republishes the SAME slots
        //    but with strengthened stableIds.
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(
                item(
                    stableIds = ProviderIds(tmdb = "687163", imdb = "tt12345678"),
                    rank = DisplaySourceRank.RESOLVED
                )
            ),
            replace = true
        )

        val after = repo.observeHomeSurface(profileSession.profileId).first()
        assertEquals(1, after.size)
        assertEquals(
            "Strengthened IMDB id must surface so addon stream-fetch can use it.",
            "tt12345678",
            after[0].stableIds.imdb
        )
        assertEquals("687163", after[0].stableIds.tmdb)
    }

    @Test
    fun `existing non-null IDs are never lost when incoming has null fields`() = runTest {
        // Initial: full ID set already published.
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(
                item(
                    stableIds = ProviderIds(tmdb = "687163", imdb = "tt12345678", tvdb = "555"),
                    rank = DisplaySourceRank.RESOLVED
                )
            ),
            replace = true
        )

        // A later producer emits with only the canonical id populated.
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(
                item(
                    stableIds = ProviderIds(tmdb = "687163"),
                    rank = DisplaySourceRank.RESOLVED
                )
            ),
            replace = true
        )

        val after = repo.observeHomeSurface(profileSession.profileId).first()
        assertEquals(1, after.size)
        assertNotNull(
            "Existing IMDB id must be preserved when incoming carries null.",
            after[0].stableIds.imdb
        )
        assertEquals("tt12345678", after[0].stableIds.imdb)
        assertEquals("555", after[0].stableIds.tvdb)
    }
}
