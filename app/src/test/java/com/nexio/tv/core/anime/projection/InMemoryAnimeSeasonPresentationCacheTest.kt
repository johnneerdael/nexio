package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryAnimeSeasonPresentationCacheTest {

    private val groupKey = AnimeWorkGroupKey("anime-work:tvdb:305074")
    private val work = AnimeWorkIdentity(
        groupKey = groupKey,
        primaryKitsuId = "11469",
        memberKitsuIds = setOf("11469", "13881"),
        providerIds = ProviderIds(tvdb = "305074"),
        confidence = AnimeGroupingConfidence.HIGH,
        evidence = emptyList(),
    )

    @Test
    fun `get returns null when nothing cached`() {
        val cache = InMemoryAnimeSeasonPresentationCache()
        assertNull(cache.get(groupKey, "13881"))
    }

    @Test
    fun `put and get round-trip by group key and source kitsu id`() {
        val cache = InMemoryAnimeSeasonPresentationCache()
        val presentation = presentation(work, selectedSeason = 3)

        cache.put(groupKey, "13881", presentation)

        assertEquals(presentation, cache.get(groupKey, "13881"))
    }

    @Test
    fun `different source kitsu id does not hit same entry`() {
        val cache = InMemoryAnimeSeasonPresentationCache()
        cache.put(groupKey, "13881", presentation(work, selectedSeason = 3))

        assertNull(cache.get(groupKey, "11469"))
    }

    @Test
    fun `different group key does not hit same entry`() {
        val cache = InMemoryAnimeSeasonPresentationCache()
        cache.put(groupKey, "13881", presentation(work, selectedSeason = 3))
        val otherKey = AnimeWorkGroupKey("anime-work:tvdb:81797")

        assertNull(cache.get(otherKey, "13881"))
    }

    @Test
    fun `invalidate removes all entries for a group key`() {
        val cache = InMemoryAnimeSeasonPresentationCache()
        cache.put(groupKey, "11469", presentation(work, selectedSeason = 1))
        cache.put(groupKey, "13881", presentation(work, selectedSeason = 3))

        cache.invalidate(groupKey)

        assertNull(cache.get(groupKey, "11469"))
        assertNull(cache.get(groupKey, "13881"))
    }

    @Test
    fun `invalidate does not remove entries for other group keys`() {
        val cache = InMemoryAnimeSeasonPresentationCache()
        val otherKey = AnimeWorkGroupKey("anime-work:tvdb:81797")
        val otherWork = work.copy(groupKey = otherKey, providerIds = ProviderIds(tvdb = "81797"))
        cache.put(otherKey, "12", presentation(otherWork, selectedSeason = 1))

        cache.invalidate(groupKey)

        assertEquals(presentation(otherWork, selectedSeason = 1), cache.get(otherKey, "12"))
    }

    private fun presentation(work: AnimeWorkIdentity, selectedSeason: Int) = AnimeSeasonPresentation(
        work = work,
        seasons = listOf(AnimeSeasonTab(seasonNumber = selectedSeason, title = null, episodeCount = 25, episodesKitsuMemberId = "13881", isFlatFallback = false)),
        selectedSeason = selectedSeason,
        source = SeasonPresentationSource.KITSU_SEASON_NUMBERS,
        confidence = CoordinateConfidence.HIGH,
    )
}
