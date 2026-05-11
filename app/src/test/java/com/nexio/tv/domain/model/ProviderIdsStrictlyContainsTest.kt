package com.nexio.tv.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderIdsStrictlyContainsTest {
    @Test fun `gained-only returns true`() {
        val current = ProviderIds(imdb = "tt1", tmdb = "550")
        val snapshot = ProviderIds(tmdb = "550")
        assertTrue(current.strictlyContains(snapshot))
    }

    @Test fun `identical returns false`() {
        val ids = ProviderIds(imdb = "tt1", tmdb = "550")
        assertFalse(ids.strictlyContains(ids))
    }

    @Test fun `lost-any returns false even if also gained`() {
        val current = ProviderIds(imdb = "tt1", tvdb = "999")
        val snapshot = ProviderIds(imdb = "tt1", tmdb = "550")
        assertFalse(current.strictlyContains(snapshot))
    }

    @Test fun `both empty returns false`() {
        val empty = ProviderIds()
        assertFalse(empty.strictlyContains(empty))
    }

    @Test fun `current empty snapshot non-empty returns false`() {
        val current = ProviderIds()
        val snapshot = ProviderIds(imdb = "tt1")
        assertFalse(current.strictlyContains(snapshot))
    }

    @Test fun `gained kitsu alone returns true`() {
        val current = ProviderIds(kitsu = "abc")
        val snapshot = ProviderIds()
        assertTrue(current.strictlyContains(snapshot))
    }
}
