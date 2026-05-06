package com.nexio.tv.core.image

import android.content.Context
import coil.request.Options
import com.nexio.tv.data.integration.posters.transport.PosterTransport
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SearchSuggestionPosterModelTest {
    @Test
    fun `remote poster url becomes safe model without exposing url`() {
        val registry = SearchSuggestionPosterRegistry()
        val model = registry.register(
            tconst = "tt0137523",
            rawUrl = "https://image.tmdb.org/t/p/w92/poster.jpg"
        )

        assertNotNull(model)
        assertEquals(
            "https://image.tmdb.org/t/p/w92/poster.jpg",
            registry.resolve(model!!)
        )
        assertFalse(model.toString().contains("https://"))
        assertFalse(model.key.contains("https://"))
    }

    @Test
    fun `invalid poster url is not registered`() {
        val registry = SearchSuggestionPosterRegistry()

        val model = registry.register(
            tconst = "tt0137523",
            rawUrl = "javascript:alert(1)"
        )

        assertEquals(null, model)
    }

    @Test
    fun `registry evicts oldest poster urls when bounded`() {
        val registry = SearchSuggestionPosterRegistry(maxEntries = 2)
        val first = registry.register("tt1", "https://image.tmdb.org/t/p/w92/one.jpg")!!
        val second = registry.register("tt2", "https://image.tmdb.org/t/p/w92/two.jpg")!!
        val third = registry.register("tt3", "https://image.tmdb.org/t/p/w92/three.jpg")!!

        assertNull(registry.resolve(first))
        assertEquals("https://image.tmdb.org/t/p/w92/two.jpg", registry.resolve(second))
        assertEquals("https://image.tmdb.org/t/p/w92/three.jpg", registry.resolve(third))
        assertEquals(2, registry.size)
    }

    @Test
    fun `registry can prune stale posters to current suggestion set`() {
        val registry = SearchSuggestionPosterRegistry(maxEntries = 8)
        val stale = registry.register("tt1", "https://image.tmdb.org/t/p/w92/one.jpg")!!
        val current = registry.register("tt2", "https://image.tmdb.org/t/p/w92/two.jpg")!!

        registry.retainOnly(setOf(current))

        assertNull(registry.resolve(stale))
        assertEquals("https://image.tmdb.org/t/p/w92/two.jpg", registry.resolve(current))
        assertEquals(1, registry.size)
    }

    @Test
    fun `model key strips query strings from identity`() {
        val registry = SearchSuggestionPosterRegistry()
        val first = registry.register(
            tconst = "tt0137523",
            rawUrl = "https://image.tmdb.org/t/p/w92/poster.jpg?token=secret-one"
        )!!
        val second = registry.register(
            tconst = "tt0137523",
            rawUrl = "https://image.tmdb.org/t/p/w92/poster.jpg?token=secret-two"
        )!!

        assertEquals(first.key, second.key)
        assertFalse(first.key.contains("secret"))
    }

    @Test
    fun `keyer returns stable cache key for model`() {
        val registry = SearchSuggestionPosterRegistry()
        val model = registry.register(
            tconst = "tt0137523",
            rawUrl = "https://image.tmdb.org/t/p/w92/poster.jpg?token=secret"
        )!!
        val keyer = SearchSuggestionPosterKeyer()
        val options = Options(context = mockk<Context>(relaxed = true))

        assertEquals(model.key, keyer.key(model, options))
        assertEquals(model.key, keyer.key(model, options))
    }

    @Test
    fun `fetcher rethrows cancellation`() = runTest {
        val registry = SearchSuggestionPosterRegistry()
        val model = registry.register(
            tconst = "tt0137523",
            rawUrl = "https://image.tmdb.org/t/p/w92/poster.jpg"
        )!!
        val transport = mockk<PosterTransport>()
        every { transport.execute(any()) } throws CancellationException("cancelled")
        val fetcher = SearchSuggestionPosterFetcher(
            model = model,
            registry = registry,
            transport = transport
        )

        try {
            fetcher.fetch()
        } catch (error: CancellationException) {
            assertEquals("cancelled", error.message)
            return@runTest
        }

        throw AssertionError("Expected CancellationException")
    }
}
