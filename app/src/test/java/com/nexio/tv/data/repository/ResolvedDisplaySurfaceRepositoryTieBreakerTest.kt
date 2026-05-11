package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.integration.IntegrationProvider
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
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TDD anchor — Task 12 of the Artwork Routing & Invalidation plan.
 *
 * Closes Bug A: posters popping between RPDB (preferred) and the addon stock
 * poster (fallback) when both arrive at the same `DisplaySourceRank.RESOLVED`.
 *
 * Tie-break contract: on rank tie, the slot whose provider matches
 * `incoming.preferredArtworkProviders[slotType]` wins. If neither/both match,
 * fall back to "incoming wins" (status quo).
 */
class ResolvedDisplaySurfaceRepositoryTieBreakerTest {

    private val testSession = ActiveProfileSession(
        profileId = 1,
        sessionId = "test-session",
        sessionOrdinal = 1L,
        startedAtMs = 1_000L
    )
    private val repo = ResolvedDisplaySurfaceRepository(activeProfileSession = { testSession })
    private val rpdb = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB)
    private val addon = ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON)

    private val nowMs = 1L

    private fun posterSlot(value: String, provider: ArtworkProviderId): ResolvedSlot<ArtworkDisplayRef> =
        ResolvedSlot(
            value = ArtworkDisplayRef.LegacyString(
                value = value,
                imageType = ArtworkType.POSTER,
                trace = ArtworkTrace.empty()
            ) as ArtworkDisplayRef,
            rank = DisplaySourceRank.RESOLVED,
            provider = provider.key,
            role = "HYDRATION_RESOLVED",
            updatedAtMs = nowMs,
            expiresAtMs = null,
            trace = emptyList()
        )

    private fun itemWithPoster(slot: ResolvedSlot<ArtworkDisplayRef>): ResolvedDisplayItem {
        val slots = ResolvedDisplayFieldSlots(
            title = ResolvedSlot.empty(nowMs),
            originalTitle = ResolvedSlot.empty(nowMs),
            overview = ResolvedSlot.empty(nowMs),
            genres = ResolvedSlot.empty(nowMs),
            releaseInfo = ResolvedSlot.empty(nowMs),
            runtime = ResolvedSlot.empty(nowMs),
            rating = ResolvedSlot.empty(nowMs),
            poster = slot,
            backdrop = ResolvedSlot.empty(nowMs),
            logo = ResolvedSlot.empty(nowMs),
            thumbnail = ResolvedSlot.empty(nowMs),
            posterProviderTag = ResolvedSlot.empty(nowMs)
        )
        return ResolvedDisplayItem(
            itemKey = "movie:tmdb:550",
            contentId = "550",
            parentId = "550",
            itemType = ContentType.MOVIE,
            mediaKind = MetadataMediaKind.MOVIE,
            canonicalProvider = "TMDB",
            canonicalId = "550",
            imdbId = null,
            stableIds = ProviderIds(tmdb = "550"),
            display = ResolvedDisplayFields(
                title = "Test",
                originalTitle = null,
                year = null,
                releaseDate = null,
                overview = null,
                genres = emptyList(),
                runtimeText = null
            ),
            artwork = ArtworkBundle(poster = slot.value),
            rating = null,
            trailer = TrailerDisplayState(),
            hydrationState = HydrationState.CANONICAL_READY,
            sourceTrace = emptyList(),
            updatedAtMs = nowMs,
            slots = slots
        )
    }

    @Test
    fun `tie regression rejected — existing RPDB beats incoming addon`() {
        val existing = itemWithPoster(posterSlot("rpdb://", rpdb))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))
        repo.publishResolvedItems(testSession, listOf(existing))
        val incoming = itemWithPoster(posterSlot("addon://stock", addon))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))

        repo.publishResolvedItems(testSession, listOf(incoming))

        val surface = repo.snapshotNow(profileId = 1).single()
        val posterValue = (surface.slots?.poster?.value as? ArtworkDisplayRef.LegacyString)?.value
        assertEquals("rpdb://", posterValue)
    }

    @Test
    fun `tie upgrade — incoming RPDB beats existing addon`() {
        val existing = itemWithPoster(posterSlot("addon://stock", addon))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))
        repo.publishResolvedItems(testSession, listOf(existing))
        val incoming = itemWithPoster(posterSlot("rpdb://", rpdb))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))

        repo.publishResolvedItems(testSession, listOf(incoming))

        val surface = repo.snapshotNow(profileId = 1).single()
        val posterValue = (surface.slots?.poster?.value as? ArtworkDisplayRef.LegacyString)?.value
        assertEquals("rpdb://", posterValue)
    }

    @Test
    fun `tie both preferred — incoming wins`() {
        val existing = itemWithPoster(posterSlot("rpdb://A", rpdb))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))
        repo.publishResolvedItems(testSession, listOf(existing))
        val incoming = itemWithPoster(posterSlot("rpdb://B", rpdb))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))

        repo.publishResolvedItems(testSession, listOf(incoming))

        val surface = repo.snapshotNow(profileId = 1).single()
        val posterValue = (surface.slots?.poster?.value as? ArtworkDisplayRef.LegacyString)?.value
        assertEquals("rpdb://B", posterValue)
    }

    @Test
    fun `tie neither preferred — existing wins`() {
        val existing = itemWithPoster(posterSlot("addon://A", addon))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))
        repo.publishResolvedItems(testSession, listOf(existing))
        val incoming = itemWithPoster(posterSlot("addon://B", addon))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))

        repo.publishResolvedItems(testSession, listOf(incoming))

        val surface = repo.snapshotNow(profileId = 1).single()
        val posterValue = (surface.slots?.poster?.value as? ArtworkDisplayRef.LegacyString)?.value
        assertEquals("addon://A", posterValue)
    }
}
