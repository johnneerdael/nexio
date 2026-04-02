package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TraktLibrarySnapshotStoreTest {

    @Test
    fun `read restores snapshot and hydrated metadata for current language epoch`() {
        val prefs = InMemorySharedPreferences()
        var epoch = 4
        val context = mockContext(prefs, "trakt_library_snapshot")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } answers { epoch }
        val store = TraktLibrarySnapshotStore(context, metadataStore)

        val snapshot = sampleSnapshot()
        store.write(snapshot)

        assertEquals(snapshot, store.read())

        epoch = 5
        assertEquals(
            snapshot.copy(metadataByContentKey = emptyMap()),
            store.read()
        )
    }

    @Test
    fun `read clears corrupt payloads`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "trakt_library_snapshot")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 2
        val store = TraktLibrarySnapshotStore(context, metadataStore)

        prefs.edit().putString("snapshot", "{bad json").apply()

        assertNull(store.read())
        assertFalse(prefs.contains("snapshot"))
    }

    @Test
    fun `write persists list tabs entries and metadata`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "trakt_library_snapshot")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 7
        val store = TraktLibrarySnapshotStore(context, metadataStore)

        store.write(sampleSnapshot())

        val raw = prefs.getString("snapshot", null).orEmpty()
        assertFalse(raw.isBlank())
        org.junit.Assert.assertTrue(raw.contains("\"listTabs\""))
        org.junit.Assert.assertTrue(raw.contains("\"entriesByList\""))
        org.junit.Assert.assertTrue(raw.contains("\"metadataByContentKey\""))
    }

    private fun sampleSnapshot(): TraktLibrarySnapshotStore.Snapshot {
        val watchlistTab = LibraryListTab(
            key = "watchlist",
            title = "Watchlist",
            type = LibraryListTab.Type.WATCHLIST,
            sortBy = "rank",
            sortHow = "asc"
        )
        val personalTab = LibraryListTab(
            key = "personal:123",
            title = "My List",
            type = LibraryListTab.Type.PERSONAL,
            traktListId = 123L,
            slug = "my-list"
        )
        val watchlistItem = LibraryEntry(
            id = "tt1234567",
            type = "movie",
            name = "Watchlist Movie",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2024",
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf("watchlist"),
            listedAt = 100L,
            traktRank = 1,
            imdbId = "tt1234567",
            traktId = 10
        )
        val personalItem = LibraryEntry(
            id = "tmdb:321",
            type = "series",
            name = "Custom List Show",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2023",
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf("personal:123"),
            listedAt = 90L,
            traktRank = 1,
            tmdbId = 321,
            traktId = 11
        )

        return TraktLibrarySnapshotStore.Snapshot(
            listTabs = listOf(watchlistTab, personalTab),
            entriesByList = linkedMapOf(
                "watchlist" to listOf(watchlistItem),
                "personal:123" to listOf(personalItem)
            ),
            metadataByContentKey = mapOf(
                "movie:tt1234567" to TraktLibrarySnapshotStore.PersistedLibraryMetadata(
                    name = "Hydrated Watchlist Movie",
                    poster = "https://image.test/watchlist/poster.jpg",
                    background = "https://image.test/watchlist/background.jpg",
                    logo = "https://image.test/watchlist/logo.png",
                    description = "Hydrated watchlist description",
                    releaseInfo = "2024",
                    imdbRating = 8.5f,
                    genres = listOf("Drama")
                ),
                "series:tmdb:321" to TraktLibrarySnapshotStore.PersistedLibraryMetadata(
                    name = "Hydrated Custom List Show",
                    poster = "https://image.test/custom/poster.jpg",
                    background = "https://image.test/custom/background.jpg",
                    logo = "https://image.test/custom/logo.png",
                    description = "Hydrated custom list description",
                    releaseInfo = "2023",
                    imdbRating = 8.1f,
                    genres = listOf("Sci-Fi")
                )
            ),
            updatedAtMs = 1234L
        )
    }

    private fun mockContext(prefs: InMemorySharedPreferences, expectedName: String): Context {
        return mockk {
            every { getSharedPreferences(expectedName, Context.MODE_PRIVATE) } returns prefs
        }
    }
}
