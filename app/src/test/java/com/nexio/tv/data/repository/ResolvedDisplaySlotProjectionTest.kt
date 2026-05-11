package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolvedDisplaySlotProjectionTest {

    private val nowMs = 1_700_000_000_000L

    private fun emptySlotString(): ResolvedSlot<String> = ResolvedSlot.empty(nowMs)
    private fun emptySlotStringList(): ResolvedSlot<List<String>> = ResolvedSlot.empty(nowMs)
    private fun emptySlotArtwork(): ResolvedSlot<ArtworkDisplayRef> = ResolvedSlot.empty(nowMs)
    private fun emptySlotRating(): ResolvedSlot<TitleRating> = ResolvedSlot.empty(nowMs)

    private fun legacyRef(url: String, imageType: ArtworkType): ArtworkDisplayRef.LegacyString =
        ArtworkDisplayRef.LegacyString(
            value = url,
            imageType = imageType,
            trace = ArtworkTrace.empty()
        )

    private fun artworkSlot(url: String, imageType: ArtworkType): ResolvedSlot<ArtworkDisplayRef> =
        ResolvedSlot(
            value = legacyRef(url, imageType),
            rank = DisplaySourceRank.RESOLVED,
            provider = "TMDB",
            role = null,
            updatedAtMs = nowMs,
            expiresAtMs = null,
            trace = emptyList()
        )

    private fun stringSlot(value: String): ResolvedSlot<String> =
        ResolvedSlot(
            value = value,
            rank = DisplaySourceRank.RESOLVED,
            provider = "TMDB",
            role = null,
            updatedAtMs = nowMs,
            expiresAtMs = null,
            trace = emptyList()
        )

    private fun stringListSlot(value: List<String>): ResolvedSlot<List<String>> =
        ResolvedSlot(
            value = value,
            rank = DisplaySourceRank.RESOLVED,
            provider = "TMDB",
            role = null,
            updatedAtMs = nowMs,
            expiresAtMs = null,
            trace = emptyList()
        )

    private fun emptySlots(): ResolvedDisplayFieldSlots =
        ResolvedDisplayFieldSlots(
            title = emptySlotString(),
            originalTitle = emptySlotString(),
            overview = emptySlotString(),
            genres = emptySlotStringList(),
            releaseInfo = emptySlotString(),
            runtime = emptySlotString(),
            rating = emptySlotRating(),
            poster = emptySlotArtwork(),
            backdrop = emptySlotArtwork(),
            logo = emptySlotArtwork(),
            thumbnail = emptySlotArtwork(),
            posterProviderTag = emptySlotString()
        )

    @Test
    fun `projects artwork bundle from poster, backdrop, logo, thumbnail slots`() {
        val slots = emptySlots().copy(
            poster = artworkSlot("https://example/poster.jpg", ArtworkType.POSTER),
            backdrop = artworkSlot("https://example/backdrop.jpg", ArtworkType.BACKDROP),
            logo = artworkSlot("https://example/logo.png", ArtworkType.LOGO),
            thumbnail = artworkSlot("https://example/thumb.jpg", ArtworkType.THUMBNAIL)
        )

        val bundle = slots.toArtworkBundle()

        val poster = bundle.poster as ArtworkDisplayRef.LegacyString
        assertEquals("https://example/poster.jpg", poster.value)
        assertEquals(ArtworkType.POSTER, poster.imageType)

        val backdrop = bundle.backdrop as ArtworkDisplayRef.LegacyString
        assertEquals("https://example/backdrop.jpg", backdrop.value)
        assertEquals(ArtworkType.BACKDROP, backdrop.imageType)

        val logo = bundle.logo as ArtworkDisplayRef.LegacyString
        assertEquals("https://example/logo.png", logo.value)
        assertEquals(ArtworkType.LOGO, logo.imageType)

        val thumbnail = bundle.thumbnail as ArtworkDisplayRef.LegacyString
        assertEquals("https://example/thumb.jpg", thumbnail.value)
        assertEquals(ArtworkType.THUMBNAIL, thumbnail.imageType)
    }

    @Test
    fun `projects ResolvedDisplayFields from text and list slots`() {
        val slots = emptySlots().copy(
            title = stringSlot("Interstellar"),
            originalTitle = stringSlot("Interstellar"),
            overview = stringSlot("A team of explorers travel through a wormhole."),
            genres = stringListSlot(listOf("Sci-Fi", "Drama")),
            releaseInfo = stringSlot("2024-03-15"),
            runtime = stringSlot("2h 49m")
        )

        val fields = slots.toResolvedDisplayFields(
            fallbackTitle = "Fallback",
            fallbackTomatoesRating = 87.5
        )

        assertEquals("Interstellar", fields.title)
        assertEquals("Interstellar", fields.originalTitle)
        assertEquals(2024, fields.year)
        assertEquals("2024-03-15", fields.releaseDate)
        assertEquals("A team of explorers travel through a wormhole.", fields.overview)
        assertEquals(listOf("Sci-Fi", "Drama"), fields.genres)
        assertEquals("2h 49m", fields.runtimeText)
        assertEquals(87.5, fields.tomatoesRating)
    }

    @Test
    fun `title falls back when slot value is null`() {
        val slots = emptySlots() // title slot is empty (null value)

        val fields = slots.toResolvedDisplayFields(
            fallbackTitle = "Fallback Title",
            fallbackTomatoesRating = null
        )

        assertEquals("Fallback Title", fields.title)
        assertNull(fields.year)
        assertNull(fields.tomatoesRating)
    }

    @Test
    fun `releaseInfo with non-4-char prefix returns null year`() {
        val slots = emptySlots().copy(
            releaseInfo = stringSlot("TBA")
        )

        val fields = slots.toResolvedDisplayFields(
            fallbackTitle = "Some Title",
            fallbackTomatoesRating = null
        )

        assertNull(fields.year)
        assertEquals("TBA", fields.releaseDate)
    }

    @Test
    fun `projects rating from slot when present`() {
        val rating = TitleRating(value = 8.4, source = TitleRatingSource.IMDB)
        val slots = emptySlots().copy(
            rating = ResolvedSlot(
                value = rating,
                rank = DisplaySourceRank.RESOLVED,
                provider = "IMDB",
                role = null,
                updatedAtMs = nowMs,
                expiresAtMs = null,
                trace = emptyList()
            )
        )

        val result = slots.toRating()

        assertEquals(rating, result)
        assertEquals(8.4, result!!.value, 0.0)
        assertEquals(TitleRatingSource.IMDB, result.source)
    }
}
