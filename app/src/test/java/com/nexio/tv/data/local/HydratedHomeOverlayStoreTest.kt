package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HydratedHomeOverlayStoreTest {
    @Test
    fun `upsert persists canonical overlay and aliases multiple item keys`() = runTest {
        val prefs = InMemorySharedPreferences()
        val store = HydratedHomeOverlayStore(mockContext(prefs))
        val overlay = overlay(itemKey = "movie:tmdb:550")

        store.upsert(overlay, aliases = setOf("movie:tmdb:550", "movie:imdb:tt0137523"))

        val recreatedStore = HydratedHomeOverlayStore(mockContext(prefs))
        assertEquals(
            "Fight Club",
            recreatedStore.readByCanonicalIdentity(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "550",
                contentType = ContentType.MOVIE,
                languageTag = "en",
                policyVersion = 1
            )?.fields?.title
        )
        assertEquals(
            setOf("movie:tmdb:550", "movie:imdb:tt0137523"),
            recreatedStore.readForItemKeys(
                itemKeys = setOf("movie:tmdb:550", "movie:imdb:tt0137523"),
                languageTag = "en",
                policyVersion = 1
            ).keys
        )
    }

    @Test
    fun `observeForItemKeys resolves aliases to overlays`() = runTest {
        val store = HydratedHomeOverlayStore(mockContext(InMemorySharedPreferences()))
        val overlay = overlay(itemKey = "movie:tmdb:550")
        val observedFlow = store.observeForItemKeys(
            itemKeys = setOf("movie:imdb:tt0137523"),
            languageTag = "en",
            policyVersion = 1
        )

        assertEquals(emptyMap<String, HydratedHomeOverlay>(), observedFlow.first())
        val nextObserved = async { observedFlow.drop(1).first() }
        runCurrent()
        store.upsert(overlay, aliases = setOf("movie:imdb:tt0137523"))

        assertEquals("Fight Club", nextObserved.await().getValue("movie:imdb:tt0137523").fields.title)
    }

    @Test
    fun `expired overlays are not returned`() = runTest {
        val store = HydratedHomeOverlayStore(mockContext(InMemorySharedPreferences()))
        val expired = overlay(
            itemKey = "movie:tmdb:550",
            updatedAtMs = 1_000L,
            staleAtMs = 2_000L,
            expiresAtMs = 3_000L
        )

        store.upsert(expired, aliases = setOf("movie:tmdb:550"))

        assertNull(
            store.readByCanonicalIdentity(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "550",
                contentType = ContentType.MOVIE,
                languageTag = "en",
                policyVersion = 1,
                nowMs = 4_000L
            )
        )
        assertEquals(
            emptyMap<String, HydratedHomeOverlay>(),
            store.readForItemKeys(
                itemKeys = setOf("movie:tmdb:550"),
                languageTag = "en",
                policyVersion = 1,
                nowMs = 4_000L
            )
        )
    }

    @Test
    fun `removeAliases stops item key observation without deleting canonical overlay`() = runTest {
        val store = HydratedHomeOverlayStore(mockContext(InMemorySharedPreferences()))
        val overlay = overlay(itemKey = "movie:tmdb:550")
        store.upsert(overlay, aliases = setOf("movie:tmdb:550", "movie:imdb:tt0137523"))

        store.removeAliases(
            itemKeys = setOf("movie:imdb:tt0137523"),
            languageTag = "en",
            policyVersion = 1
        )

        val observed = store.observeForItemKeys(
            itemKeys = setOf("movie:imdb:tt0137523"),
            languageTag = "en",
            policyVersion = 1
        ).first()

        assertEquals(emptyMap<String, HydratedHomeOverlay>(), observed)
        assertEquals(
            "Fight Club",
            store.readByCanonicalIdentity(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "550",
                contentType = ContentType.MOVIE,
                languageTag = "en",
                policyVersion = 1
            )?.fields?.title
        )
    }

    @Test
    fun `aliases are scoped by language and policy`() = runTest {
        val store = HydratedHomeOverlayStore(mockContext(InMemorySharedPreferences()))
        store.upsert(
            overlay = overlay(itemKey = "movie:tmdb:550", languageTag = "en", policyVersion = 1),
            aliases = setOf("movie:imdb:tt0137523")
        )

        assertEquals(
            emptyMap<String, HydratedHomeOverlay>(),
            store.readForItemKeys(
                itemKeys = setOf("movie:imdb:tt0137523"),
                languageTag = "nl",
                policyVersion = 1
            )
        )
        assertEquals(
            emptyMap<String, HydratedHomeOverlay>(),
            store.readForItemKeys(
                itemKeys = setOf("movie:imdb:tt0137523"),
                languageTag = "en",
                policyVersion = 2
            )
        )
    }

    private fun overlay(
        itemKey: String,
        languageTag: String = "en",
        policyVersion: Int = 1,
        updatedAtMs: Long = 10_000L,
        staleAtMs: Long = Long.MAX_VALUE,
        expiresAtMs: Long = Long.MAX_VALUE
    ): HydratedHomeOverlay {
        val fields = HomeDisplayMetadata(
            title = "Fight Club",
            poster = "poster.jpg",
            imdbRating = 8.8f
        )
        return HydratedHomeOverlay(
            overlayKey = hydratedHomeOverlayKey(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "550",
                contentType = ContentType.MOVIE,
                languageTag = languageTag,
                policyVersion = policyVersion
            ),
            itemKey = itemKey,
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = "tt0137523",
            contentType = ContentType.MOVIE,
            languageTag = languageTag,
            policyVersion = policyVersion,
            fields = fields,
            fieldTrace = listOf(
                HydratedHomeFieldTrace(
                    field = "TITLE",
                    selectedProvider = "TMDB",
                    sourceRole = "PRIMARY"
                )
            ),
            displayHash = fields.hydratedHomeDisplayHash(),
            updatedAtMs = updatedAtMs,
            staleAtMs = staleAtMs,
            expiresAtMs = expiresAtMs
        )
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return context
    }
}
