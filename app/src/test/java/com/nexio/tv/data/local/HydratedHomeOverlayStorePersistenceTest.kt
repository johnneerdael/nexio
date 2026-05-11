package com.nexio.tv.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
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
class HydratedHomeOverlayStorePersistenceTest {

    private fun newStore(
        prefs: InMemorySharedPreferences = InMemorySharedPreferences()
    ): Pair<HydratedHomeOverlayStore, InMemorySharedPreferences> {
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return HydratedHomeOverlayStore(context) to prefs
    }

    private fun overlay(
        stableIdsSnapshot: ProviderIds,
        settingsSignature: String
    ): HydratedHomeOverlay {
        val fields = HomeDisplayMetadata(title = "Fight Club", poster = "rpdb://550.jpg")
        return HydratedHomeOverlay(
            overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
            itemKey = "movie:tmdb:550",
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
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
            state = HomeItemHydrationState.CANONICAL_READY,
            stableIdsSnapshot = stableIdsSnapshot,
            settingsSignature = settingsSignature
        )
    }

    @Test
    fun `v2 round-trip preserves stableIdsSnapshot and settingsSignature`() = runTest {
        val (store, prefs) = newStore()
        val v2 = overlay(
            stableIdsSnapshot = ProviderIds(tmdb = "550", imdb = "tt0137523"),
            settingsSignature = "p=rpdb;l=default;b=default;t=default;v=1"
        )
        store.upsert(v2, aliases = setOf("movie:tmdb:550"))

        // Reinstantiate the store over the same prefs — equivalent to a cold-start re-read.
        val ctx2 = mockk<Context>()
        every { ctx2.getSharedPreferences(any(), any()) } returns prefs
        val reloaded = HydratedHomeOverlayStore(ctx2)

        val out = reloaded.readByCanonicalIdentity(
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1
        )!!

        assertEquals(ProviderIds(tmdb = "550", imdb = "tt0137523"), out.stableIdsSnapshot)
        assertEquals("p=rpdb;l=default;b=default;t=default;v=1", out.settingsSignature)
        assertEquals("Fight Club", out.fields.title)
    }

    @Test
    fun `v1 record without new fields loads with empty defaults`() = runTest {
        val prefs = InMemorySharedPreferences()
        // Hand-craft a v1-shape JSON payload: serialize a current overlay, then strip the
        // two new keys to simulate the on-disk shape that existed before Task 6 added
        // stableIdsSnapshot / settingsSignature to the model. schemaVersion stays at 1 —
        // the store's stored schema version did not bump; only the value's field set
        // expanded, and Gson defaults missing fields on the data class side.
        val gson = Gson()
        val v1Style = overlay(
            stableIdsSnapshot = ProviderIds(tmdb = "550"),
            settingsSignature = "p=default;l=default;b=default;t=default;v=1"
        )
        val valueTree = gson.toJsonTree(v1Style).asJsonObject
        valueTree.remove("stableIdsSnapshot")
        valueTree.remove("settingsSignature")
        val payload = JsonObject().apply {
            add("value", valueTree)
            addProperty("schemaVersion", 1)
        }
        prefs.edit()
            .putString("overlay::${v1Style.overlayKey}", gson.toJson(payload))
            .putString(
                "alias::en::policy:1::movie:tmdb:550",
                v1Style.overlayKey
            )
            .apply()

        val (store, _) = newStore(prefs)
        val out = store.readByCanonicalIdentity(
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1
        )!!

        assertEquals(ProviderIds(), out.stableIdsSnapshot)
        assertEquals("", out.settingsSignature)
        // Rest of the overlay still loads correctly.
        assertEquals("Fight Club", out.fields.title)
    }
}
