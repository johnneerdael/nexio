package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TrailerDisplayState
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedDisplayProjectionCacheTest {

    private fun resolved(
        itemKey: String,
        updatedAtMs: Long,
        title: String? = "Title",
    ): ResolvedDisplayItem = ResolvedDisplayItem(
        itemKey = itemKey,
        contentId = itemKey,
        parentId = itemKey,
        itemType = ContentType.MOVIE,
        mediaKind = MetadataMediaKind.MOVIE,
        canonicalProvider = null,
        canonicalId = null,
        imdbId = null,
        stableIds = ProviderIds(),
        display = ResolvedDisplayFields(title, null, null, null, null, emptyList(), null),
        artwork = ArtworkBundle(),
        rating = null,
        trailer = TrailerDisplayState(),
        hydrationState = HydrationState.PREVIEW_ONLY,
        sourceTrace = emptyList(),
        updatedAtMs = updatedAtMs
    )

    @Test
    fun `projectItem returns same instance when itemKey and updatedAtMs are unchanged`() {
        val cache = ResolvedDisplayProjectionCache()
        val item = resolved("movie:tmdb:1", updatedAtMs = 100L)

        val first = cache.projectItem(item)
        val second = cache.projectItem(item)

        assertSame(first, second)
    }

    @Test
    fun `projectItem returns new instance when updatedAtMs advances`() {
        val cache = ResolvedDisplayProjectionCache()
        val before = resolved("movie:tmdb:1", updatedAtMs = 100L, title = "Old")
        val after = resolved("movie:tmdb:1", updatedAtMs = 200L, title = "New")

        val first = cache.projectItem(before)
        val second = cache.projectItem(after)

        assertNotSame(first, second)
        assertTrue(second.title == "New")
    }

    @Test
    fun `retainOnly evicts entries whose itemKey is no longer active`() {
        val cache = ResolvedDisplayProjectionCache()
        val a = resolved("movie:tmdb:a", updatedAtMs = 1L)
        val b = resolved("movie:tmdb:b", updatedAtMs = 1L)

        cache.projectItem(a)
        cache.projectItem(b)

        // Only "a" remains active; "b" should be evicted.
        cache.retainOnly(setOf("movie:tmdb:a"))

        val aAgain = cache.projectItem(a)
        val bAgain = cache.projectItem(b)

        // a is still cached → same instance back.
        assertSame(cache.projectItem(a), aAgain)
        // b was evicted → projectItem produces a fresh instance, then caches that one.
        assertSame(cache.projectItem(b), bAgain)
    }

    @Test
    fun `retainOnly bound retains b after re-projection`() {
        val cache = ResolvedDisplayProjectionCache()
        val a = resolved("movie:tmdb:a", updatedAtMs = 1L)

        val first = cache.projectItem(a)
        cache.retainOnly(emptySet())
        val second = cache.projectItem(a)

        assertNotSame(first, second)
    }

    @Test
    fun `projectRail returns same instance when items list is unchanged`() {
        val cache = ResolvedDisplayProjectionCache()
        val a = cache.projectItem(resolved("movie:tmdb:a", updatedAtMs = 1L))
        val b = cache.projectItem(resolved("movie:tmdb:b", updatedAtMs = 1L))

        val first = cache.projectRail("catalog-1", "Trending", listOf(a, b))
        val second = cache.projectRail("catalog-1", "Trending", listOf(a, b))

        assertSame(first, second)
    }

    @Test
    fun `projectRail returns new instance when an item changes`() {
        val cache = ResolvedDisplayProjectionCache()
        val a1 = cache.projectItem(resolved("movie:tmdb:a", updatedAtMs = 1L, title = "Old"))
        val b = cache.projectItem(resolved("movie:tmdb:b", updatedAtMs = 1L))

        val before = cache.projectRail("catalog-1", "Trending", listOf(a1, b))

        val a2 = cache.projectItem(resolved("movie:tmdb:a", updatedAtMs = 2L, title = "New"))
        val after = cache.projectRail("catalog-1", "Trending", listOf(a2, b))

        assertNotSame(before, after)
    }

    @Test
    fun `retainOnlyRails evicts unused rails`() {
        val cache = ResolvedDisplayProjectionCache()
        val a = cache.projectItem(resolved("movie:tmdb:a", updatedAtMs = 1L))

        val first = cache.projectRail("catalog-keep", "Keep", listOf(a))
        cache.projectRail("catalog-evict", "Evict", listOf(a))

        cache.retainOnlyRails(setOf("catalog-keep"))

        // catalog-keep is still cached.
        val firstAgain = cache.projectRail("catalog-keep", "Keep", listOf(a))
        assertSame(first, firstAgain)

        // catalog-evict was evicted → recreating yields a fresh instance.
        val evictFirst = cache.projectRail("catalog-evict", "Evict", listOf(a))
        val evictSecond = cache.projectRail("catalog-evict", "Evict", listOf(a))
        assertSame(evictFirst, evictSecond) // newly cached
    }
}
