package com.nexio.tv.data.repository

import com.nexio.tv.ui.screensaver.IdleScreensaverDisplayItem
import com.nexio.tv.ui.screensaver.IdleScreensaverSlide
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverCandidate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Memoizes adapter projections from [IdleScreensaverDisplayItem] to the legacy
 * [IdleScreensaverSlide] and [IdleTrailerScreensaverCandidate] shapes.
 *
 * Plan B Task 15 (adapter-bridge) wires `IdleScreensaverRepository` to publish
 * three flows from a single upstream emission. Without interning, the adapter
 * functions would allocate fresh `IdleScreensaverSlide` /
 * `IdleTrailerScreensaverCandidate` instances (and per-item
 * `listOf(background)` for `fallbackArtwork`) on every emission, even when the
 * upstream `IdleScreensaverDisplayItem` is reference-stable. Compose's
 * `===`-based skip in the screensaver overlays would then re-invalidate on
 * every tick — see CLAUDE.md hard rule #5 ("Memoization at every
 * reference-fresh boundary").
 *
 * The cache is keyed by `itemKey`. `IdleScreensaverDisplayItem` is a
 * value-equal data class freshly constructed by `IdleScreensaverDisplayItem.from`
 * on every publish (its inputs come from `ResolvedDisplayItem` which itself is
 * memoized upstream, so content equality is stable across emissions when nothing
 * actually changed). We therefore use `==` (content equality) for hit detection
 * — same pattern as [CatalogRowMemo].
 *
 * Cache size is bounded per call: entries whose `itemKey` does not appear in
 * the current input are evicted (mirrors [CatalogRowMemo] / the
 * `retainAll(active)` pattern). Outer-list interning (see
 * [internSlidesList] / [internTrailerCandidatesList]) reuses the prior
 * emission's list reference when every element is reference-identical, so
 * downstream consumers' `===` cache-hit guards stay green when no item has
 * changed.
 *
 * Thread-safe via @Synchronized; the publish path serializes its writes via
 * [IdleScreensaverRepository]'s mutex anyway, so contention is negligible.
 */
@Singleton
class ScreensaverAdapterMemo @Inject constructor() {
    private val slideCache = mutableMapOf<String, Pair<IdleScreensaverDisplayItem, IdleScreensaverSlide>>()
    private val trailerCache = mutableMapOf<String, Pair<IdleScreensaverDisplayItem, IdleTrailerScreensaverCandidate>>()
    private var lastSlidesList: List<IdleScreensaverSlide>? = null
    private var lastTrailerCandidatesList: List<IdleTrailerScreensaverCandidate>? = null

    @Synchronized
    fun internSlide(item: IdleScreensaverDisplayItem): IdleScreensaverSlide? {
        val cached = slideCache[item.itemKey]
        if (cached != null && cached.first == item) return cached.second
        val fresh = item.toIdleScreensaverSlide() ?: return null
        slideCache[item.itemKey] = item to fresh
        return fresh
    }

    @Synchronized
    fun internTrailerCandidate(item: IdleScreensaverDisplayItem): IdleTrailerScreensaverCandidate? {
        val cached = trailerCache[item.itemKey]
        if (cached != null && cached.first == item) return cached.second
        val fresh = item.toIdleTrailerScreensaverCandidate() ?: return null
        trailerCache[item.itemKey] = item to fresh
        return fresh
    }

    @Synchronized
    fun retainOnly(activeItemKeys: Set<String>) {
        slideCache.keys.retainAll(activeItemKeys)
        trailerCache.keys.retainAll(activeItemKeys)
    }

    /**
     * Reuses the prior emission's list reference when every element is
     * reference-identical and the size is unchanged. Mirrors
     * `ResolvedDisplayProjectionCache.internRailsList`.
     */
    @Synchronized
    fun internSlidesList(slides: List<IdleScreensaverSlide>): List<IdleScreensaverSlide> {
        val prior = lastSlidesList
        if (prior != null && prior.size == slides.size) {
            var allSame = true
            for (i in slides.indices) {
                if (prior[i] !== slides[i]) {
                    allSame = false
                    break
                }
            }
            if (allSame) return prior
        }
        lastSlidesList = slides
        return slides
    }

    @Synchronized
    fun internTrailerCandidatesList(
        candidates: List<IdleTrailerScreensaverCandidate>
    ): List<IdleTrailerScreensaverCandidate> {
        val prior = lastTrailerCandidatesList
        if (prior != null && prior.size == candidates.size) {
            var allSame = true
            for (i in candidates.indices) {
                if (prior[i] !== candidates[i]) {
                    allSame = false
                    break
                }
            }
            if (allSame) return prior
        }
        lastTrailerCandidatesList = candidates
        return candidates
    }
}
