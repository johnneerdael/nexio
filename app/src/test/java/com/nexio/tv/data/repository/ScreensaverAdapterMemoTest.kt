package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.ui.screensaver.IdleScreensaverDisplayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CLAUDE.md hard rule #5: at every reference-fresh boundary, content -> reference.
 * `ScreensaverAdapterMemo` lives between `IdleScreensaverDisplayItem` projections
 * and Compose-consumed `IdleScreensaverSlide` / `IdleTrailerScreensaverCandidate`
 * `MutableStateFlow`s; without it, the publish path would re-allocate per emission.
 */
class ScreensaverAdapterMemoTest {

    @Test
    fun `internSlide returns same reference when input is reference-stable`() {
        val memo = ScreensaverAdapterMemo()
        val item = displayItem(itemKey = "movie:tmdb:550", contentId = "tmdb:550", title = "Fight Club")

        val first = memo.internSlide(item)
        val second = memo.internSlide(item)

        assertNotNull(first)
        assertSame(first, second)
    }

    @Test
    fun `internTrailerCandidate returns same reference when input is reference-stable`() {
        val memo = ScreensaverAdapterMemo()
        val item = displayItem(itemKey = "movie:tmdb:550", contentId = "tmdb:550", title = "Fight Club")

        val first = memo.internTrailerCandidate(item)
        val second = memo.internTrailerCandidate(item)

        assertNotNull(first)
        assertSame(first, second)
    }

    @Test
    fun `internSlide returns same reference when input is content-equal but reference-fresh`() {
        // Mirrors the publish path: IdleScreensaverDisplayItem.from(item) allocates
        // a new instance each emission, but content is unchanged. The memo MUST hit
        // by content equality (CLAUDE.md rule #5 — content -> reference).
        val memo = ScreensaverAdapterMemo()
        val first = memo.internSlide(
            displayItem(itemKey = "movie:tmdb:550", contentId = "tmdb:550", title = "Fight Club")
        )
        val second = memo.internSlide(
            displayItem(itemKey = "movie:tmdb:550", contentId = "tmdb:550", title = "Fight Club")
        )
        assertNotNull(first)
        assertSame(first, second)
    }

    @Test
    fun `internSlide rebuilds when input identity changes`() {
        val memo = ScreensaverAdapterMemo()
        val a = displayItem(itemKey = "movie:tmdb:550", contentId = "tmdb:550", title = "Fight Club")
        val b = displayItem(itemKey = "movie:tmdb:550", contentId = "tmdb:550", title = "Fight Club Director's Cut")

        val first = memo.internSlide(a)
        val second = memo.internSlide(b)

        assertNotSame(first, second)
        assertEquals("Fight Club", first?.title)
        assertEquals("Fight Club Director's Cut", second?.title)
    }

    @Test
    fun `internSlide returns null when item is not displayable and does not cache`() {
        val memo = ScreensaverAdapterMemo()
        // backgroundRef = null -> not displayable
        val unfit = displayItem(
            itemKey = "movie:tmdb:1",
            contentId = "tmdb:1",
            title = "Fine title",
            background = null
        )

        val result = memo.internSlide(unfit)
        assertNull(result)

        // Subsequent call still returns null without leaking a cached value
        val again = memo.internSlide(unfit)
        assertNull(again)
    }

    @Test
    fun `retainOnly evicts entries whose itemKey is not in active set`() {
        val memo = ScreensaverAdapterMemo()
        val a = displayItem(itemKey = "movie:tmdb:1", contentId = "tmdb:1", title = "A")
        val b = displayItem(itemKey = "movie:tmdb:2", contentId = "tmdb:2", title = "B")

        val firstA = memo.internSlide(a)
        val firstB = memo.internSlide(b)
        assertNotNull(firstA)
        assertNotNull(firstB)

        // Active set drops B
        memo.retainOnly(setOf("movie:tmdb:1"))

        // A still cached: same reference
        assertSame(firstA, memo.internSlide(a))
        // B was evicted: new instance returned
        val rebuiltB = memo.internSlide(b)
        assertNotSame(firstB, rebuiltB)
    }

    @Test
    fun `internSlidesList reuses prior list when elements are reference-identical`() {
        val memo = ScreensaverAdapterMemo()
        val a = displayItem(itemKey = "movie:tmdb:1", contentId = "tmdb:1", title = "A")
        val b = displayItem(itemKey = "movie:tmdb:2", contentId = "tmdb:2", title = "B")

        val sa = memo.internSlide(a)!!
        val sb = memo.internSlide(b)!!

        val list1 = memo.internSlidesList(listOf(sa, sb))
        val list2 = memo.internSlidesList(listOf(sa, sb))
        assertSame(list1, list2)
    }

    @Test
    fun `internSlidesList returns new list when an element changes`() {
        val memo = ScreensaverAdapterMemo()
        val a = displayItem(itemKey = "movie:tmdb:1", contentId = "tmdb:1", title = "A")
        val b1 = displayItem(itemKey = "movie:tmdb:2", contentId = "tmdb:2", title = "B")
        val b2 = displayItem(itemKey = "movie:tmdb:2", contentId = "tmdb:2", title = "B*")

        val sa = memo.internSlide(a)!!
        val sb1 = memo.internSlide(b1)!!
        val firstList = memo.internSlidesList(listOf(sa, sb1))

        val sb2 = memo.internSlide(b2)!!
        val secondList = memo.internSlidesList(listOf(sa, sb2))

        assertTrue("list reference must change when element identity changes", firstList !== secondList)
    }

    @Test
    fun `internTrailerCandidatesList reuses prior list when elements are reference-identical`() {
        val memo = ScreensaverAdapterMemo()
        val a = displayItem(itemKey = "movie:tmdb:1", contentId = "tmdb:1", title = "A")
        val ta = memo.internTrailerCandidate(a)!!

        val list1 = memo.internTrailerCandidatesList(listOf(ta))
        val list2 = memo.internTrailerCandidatesList(listOf(ta))
        assertSame(list1, list2)
    }

    private fun displayItem(
        itemKey: String,
        contentId: String,
        title: String,
        background: ArtworkDisplayRef? = artworkRef(contentId, ArtworkType.BACKDROP),
        rating: TitleRating? = TitleRating(8.0, TitleRatingSource.IMDB)
    ): IdleScreensaverDisplayItem = IdleScreensaverDisplayItem(
        itemKey = itemKey,
        contentId = contentId,
        itemType = "movie",
        title = title,
        overview = "Overview",
        genres = listOf("Drama"),
        releaseInfo = "1999",
        runtime = "139 min",
        year = 1999,
        backgroundRef = background,
        logoRef = null,
        rating = rating,
        trailer = TrailerDisplayState()
    )

    private fun artworkRef(assetKey: String, imageType: ArtworkType): ArtworkDisplayRef =
        ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("decision-$assetKey"),
            assetKey = ArtworkAssetKey(assetKey),
            imageType = imageType,
            selectedProvider = null,
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty()
        )
}
