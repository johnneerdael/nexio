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
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * TDD anchor — these tests are EXPECTED TO FAIL until
 * ResolvedDisplaySurfaceRepository.publishResolvedItems is wired to consult
 * HomeRailProjectionReducer's non-downgrade logic at the boundary.
 *
 * Regression confirmed on-device 2026-05-11: Modern Home rows revert from
 * RPDB premium posters back to TMDB stock posters after hydration completes.
 */
class ResolvedDisplaySurfaceRepositoryNonDowngradeTest {

    private val profileSession = profileSession(profileId = 1, sessionId = "test-session")
    private val repo = ResolvedDisplaySurfaceRepository(activeProfileSession = { profileSession })

    private val nowMs = 1_700_000_000_000L
    private val itemKey = "movie:tt12345"

    // Build an ArtworkDisplayRef.LegacyString from a raw URL — gives us a stable, URL-bearing ref.
    private fun legacyRef(url: String): ArtworkDisplayRef.LegacyString =
        ArtworkDisplayRef.LegacyString(
            value = url,
            imageType = ArtworkType.POSTER,
            trace = ArtworkTrace.empty()
        )

    private fun posterSlot(
        url: String?,
        rank: DisplaySourceRank,
        provider: String = "TMDB"
    ): ResolvedSlot<ArtworkDisplayRef> =
        ResolvedSlot(
            value = url?.let { legacyRef(it) },
            rank = rank,
            provider = provider,
            role = null,
            updatedAtMs = nowMs,
            expiresAtMs = null,
            trace = emptyList()
        )

    private fun emptySlotString(): ResolvedSlot<String> =
        ResolvedSlot.empty(nowMs)

    private fun emptySlotStringList(): ResolvedSlot<List<String>> =
        ResolvedSlot.empty(nowMs)

    private fun emptySlotArtwork(): ResolvedSlot<ArtworkDisplayRef> =
        ResolvedSlot.empty(nowMs)

    private fun emptySlotRating(): ResolvedSlot<com.nexio.tv.domain.model.TitleRating> =
        ResolvedSlot.empty(nowMs)

    private fun slots(
        url: String?,
        rank: DisplaySourceRank,
        provider: String = "TMDB"
    ): ResolvedDisplayFieldSlots =
        ResolvedDisplayFieldSlots(
            title = emptySlotString(),
            originalTitle = emptySlotString(),
            overview = emptySlotString(),
            genres = emptySlotStringList(),
            releaseInfo = emptySlotString(),
            runtime = emptySlotString(),
            rating = emptySlotRating(),
            poster = posterSlot(url, rank, provider),
            backdrop = emptySlotArtwork(),
            logo = emptySlotArtwork(),
            thumbnail = emptySlotArtwork(),
            posterProviderTag = emptySlotString()
        )

    private fun item(
        url: String?,
        rank: DisplaySourceRank,
        provider: String = "TMDB"
    ): ResolvedDisplayItem {
        val s = slots(url, rank, provider)
        return ResolvedDisplayItem(
            itemKey = itemKey,
            contentId = "12345",
            parentId = "12345",
            itemType = ContentType.MOVIE,
            mediaKind = MetadataMediaKind.MOVIE,
            canonicalProvider = null,
            canonicalId = null,
            imdbId = null,
            stableIds = ProviderIds(),
            display = ResolvedDisplayFields(
                title = null, originalTitle = null, year = null,
                releaseDate = null, overview = null, genres = emptyList(),
                runtimeText = null
            ),
            artwork = ArtworkBundle(
                poster = s.poster.value
            ),
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
    fun `firstPaint over RESOLVED is rejected — RESOLVED slots preserved`() = runTest {
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://rpdb.example/p1.jpg", DisplaySourceRank.RESOLVED, "RPDB")),
            replace = true
        )

        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://tmdb.example/stock.jpg", DisplaySourceRank.FIRST_PAINT, "TMDB")),
            replace = true
        )

        val after = repo.observeHomeSurface(profileSession.profileId).first()
        assertEquals(1, after.size)
        val poster = after[0].slots!!.poster
        assertEquals(DisplaySourceRank.RESOLVED, poster.rank)
        val posterRef = poster.value as ArtworkDisplayRef.LegacyString
        assertEquals("https://rpdb.example/p1.jpg", posterRef.value)
        assertEquals("RPDB", poster.provider)
        val artworkPoster = after[0].artwork.poster as ArtworkDisplayRef.LegacyString
        assertEquals("https://rpdb.example/p1.jpg", artworkPoster.value)
    }

    @Test
    fun `RESOLVED over FIRST_PAINT promotes to RESOLVED slots`() = runTest {
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://tmdb.example/stock.jpg", DisplaySourceRank.FIRST_PAINT, "TMDB")),
            replace = true
        )
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://rpdb.example/p1.jpg", DisplaySourceRank.RESOLVED, "RPDB")),
            replace = true
        )

        val after = repo.observeHomeSurface(profileSession.profileId).first()
        assertEquals(DisplaySourceRank.RESOLVED, after[0].slots!!.poster.rank)
        val artworkPoster = after[0].artwork.poster as ArtworkDisplayRef.LegacyString
        assertEquals("https://rpdb.example/p1.jpg", artworkPoster.value)
    }

    @Test
    fun `replace=false additive path also enforces non-downgrade per itemKey`() = runTest {
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://rpdb.example/p1.jpg", DisplaySourceRank.RESOLVED, "RPDB")),
            replace = true
        )
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://tmdb.example/stock.jpg", DisplaySourceRank.FIRST_PAINT, "TMDB")),
            replace = false
        )

        val after = repo.observeHomeSurface(profileSession.profileId).first()
        assertEquals(1, after.size)
        assertEquals(DisplaySourceRank.RESOLVED, after[0].slots!!.poster.rank)
        assertEquals("RPDB", after[0].slots!!.poster.provider)
    }

    @Test
    fun `single-overload publish (no profileSession) enforces non-downgrade`() = runTest {
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            items = listOf(item("https://rpdb.example/p1.jpg", DisplaySourceRank.RESOLVED, "RPDB"))
        )
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            items = listOf(item("https://tmdb.example/stock.jpg", DisplaySourceRank.FIRST_PAINT, "TMDB"))
        )

        val after = repo.observeHomeSurface(profileSession.profileId).first()
        assertEquals(DisplaySourceRank.RESOLVED, after[0].slots!!.poster.rank)
    }

    @Test
    fun `existing slots null falls through to incoming as-is`() = runTest {
        val legacyItem = item("https://legacy.example/p.jpg", DisplaySourceRank.FIRST_PAINT)
            .copy(slots = null)
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(legacyItem),
            replace = true
        )
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://new.example/p.jpg", DisplaySourceRank.RESOLVED, "RPDB")),
            replace = true
        )

        val after = repo.observeHomeSurface(profileSession.profileId).first()
        val artworkPoster = after[0].artwork.poster as ArtworkDisplayRef.LegacyString
        assertEquals("https://new.example/p.jpg", artworkPoster.value)
    }

    @Test
    fun `incoming slots null cannot enforce — takes incoming verbatim`() = runTest {
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(item("https://rpdb.example/p1.jpg", DisplaySourceRank.RESOLVED, "RPDB")),
            replace = true
        )
        val legacyItem = item("https://legacy.example/p.jpg", DisplaySourceRank.FIRST_PAINT)
            .copy(slots = null)
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(legacyItem),
            replace = true
        )

        val after = repo.observeHomeSurface(profileSession.profileId).first()
        val artworkPoster = after[0].artwork.poster as ArtworkDisplayRef.LegacyString
        assertEquals("https://legacy.example/p.jpg", artworkPoster.value)
    }

    @Test
    fun `reference stability — identical-content republish returns existing instance`() = runTest {
        val resolved = item("https://rpdb.example/p1.jpg", DisplaySourceRank.RESOLVED, "RPDB")
        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(resolved),
            replace = true
        )
        val firstSurface = repo.observeHomeSurface(profileSession.profileId).first()
        val firstItem = firstSurface[0]

        repo.publishResolvedItems(
            surfaceKey = ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
            profileSession = profileSession,
            items = listOf(resolved.copy()),
            replace = true
        )
        val secondSurface = repo.observeHomeSurface(profileSession.profileId).first()
        assertSame(
            "Identical-content republish must preserve reference for downstream `===` short-circuit",
            firstItem,
            secondSurface[0]
        )
    }

    private fun profileSession(profileId: Int, sessionId: String) = ActiveProfileSession(
        profileId = profileId,
        sessionId = sessionId,
        sessionOrdinal = profileId.toLong(),
        startedAtMs = 1_000L + profileId
    )
}
