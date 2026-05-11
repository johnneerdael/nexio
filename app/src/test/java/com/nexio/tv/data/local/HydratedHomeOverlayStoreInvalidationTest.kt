package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HydratedHomeOverlayStoreInvalidationTest {

    private fun newStore(
        prefs: InMemorySharedPreferences = InMemorySharedPreferences()
    ): Pair<HydratedHomeOverlayStore, InMemorySharedPreferences> {
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return HydratedHomeOverlayStore(context) to prefs
    }

    private fun overlay(
        itemKey: String,
        canonicalId: String = "550",
        stableIdsSnapshot: ProviderIds = ProviderIds(),
        state: HomeItemHydrationState = HomeItemHydrationState.CANONICAL_READY
    ): HydratedHomeOverlay {
        val fields = HomeDisplayMetadata(
            title = "Fight Club",
            poster = "poster.jpg"
        )
        return HydratedHomeOverlay(
            overlayKey = "canonical:TMDB:$canonicalId:type:MOVIE:lang:en:policy:1",
            itemKey = itemKey,
            canonicalProvider = ProviderId.TMDB,
            canonicalId = canonicalId,
            imdbId = stableIdsSnapshot.imdb,
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1,
            fields = fields,
            fieldTrace = emptyList(),
            displayHash = fields.hydratedHomeDisplayHash(),
            updatedAtMs = 1L,
            staleAtMs = Long.MAX_VALUE,
            expiresAtMs = Long.MAX_VALUE,
            state = state,
            stableIdsSnapshot = stableIdsSnapshot,
            settingsSignature = "p=default;l=default;b=default;t=default;v=1"
        )
    }

    @Test
    fun `markStaleIfWeakerIds transitions to STALE_READY when current strictly contains snapshot`() = runTest {
        val (store, _) = newStore()
        store.upsert(
            overlay = overlay("movie:tmdb:550", stableIdsSnapshot = ProviderIds(tmdb = "550")),
            aliases = setOf("movie:tmdb:550")
        )

        store.markStaleIfWeakerIds("movie:tmdb:550", ProviderIds(tmdb = "550", imdb = "tt1"))

        val out = store.readForItemKeys(
            itemKeys = setOf("movie:tmdb:550"),
            languageTag = "en",
            policyVersion = 1
        )
        assertEquals(HomeItemHydrationState.STALE_READY, out["movie:tmdb:550"]?.state)
    }

    @Test
    fun `markStaleIfWeakerIds is no-op when current does not strictly contain snapshot`() = runTest {
        val (store, _) = newStore()
        store.upsert(
            overlay = overlay(
                "movie:tmdb:550",
                stableIdsSnapshot = ProviderIds(tmdb = "550", imdb = "tt1")
            ),
            aliases = setOf("movie:tmdb:550")
        )

        // current lost imdb -> not strictly broader
        store.markStaleIfWeakerIds("movie:tmdb:550", ProviderIds(tmdb = "550"))

        val out = store.readForItemKeys(
            itemKeys = setOf("movie:tmdb:550"),
            languageTag = "en",
            policyVersion = 1
        )
        assertEquals(HomeItemHydrationState.CANONICAL_READY, out["movie:tmdb:550"]?.state)
    }

    @Test
    fun `markStaleAll transitions every persisted overlay`() = runTest {
        val (store, _) = newStore()
        store.upsert(
            overlay = overlay(
                itemKey = "movie:tmdb:1",
                canonicalId = "1",
                stableIdsSnapshot = ProviderIds(tmdb = "1")
            ),
            aliases = setOf("movie:tmdb:1")
        )
        store.upsert(
            overlay = overlay(
                itemKey = "movie:tmdb:2",
                canonicalId = "2",
                stableIdsSnapshot = ProviderIds(tmdb = "2")
            ),
            aliases = setOf("movie:tmdb:2")
        )

        store.markStaleAll("settings_change")

        val out = store.readForItemKeys(
            itemKeys = setOf("movie:tmdb:1", "movie:tmdb:2"),
            languageTag = "en",
            policyVersion = 1
        )
        assertEquals(HomeItemHydrationState.STALE_READY, out["movie:tmdb:1"]?.state)
        assertEquals(HomeItemHydrationState.STALE_READY, out["movie:tmdb:2"]?.state)
    }

    @Test
    fun `upsert clears stale state for the upserted alias`() = runTest {
        val (store, _) = newStore()
        store.upsert(
            overlay = overlay("movie:tmdb:550", stableIdsSnapshot = ProviderIds(tmdb = "550")),
            aliases = setOf("movie:tmdb:550")
        )
        store.markStaleIfWeakerIds("movie:tmdb:550", ProviderIds(tmdb = "550", imdb = "tt1"))

        // Re-upsert with the broader stable IDs
        store.upsert(
            overlay = overlay(
                "movie:tmdb:550",
                stableIdsSnapshot = ProviderIds(tmdb = "550", imdb = "tt1")
            ),
            aliases = setOf("movie:tmdb:550")
        )

        val out = store.readForItemKeys(
            itemKeys = setOf("movie:tmdb:550"),
            languageTag = "en",
            policyVersion = 1
        )
        assertEquals(HomeItemHydrationState.CANONICAL_READY, out["movie:tmdb:550"]?.state)
    }
}
