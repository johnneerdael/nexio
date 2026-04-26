package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.core.sync.profilePrefsName
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktLibrarySnapshotStoreTest {

    @Test
    fun `read restores snapshot and hydrated metadata without epoch invalidation`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "trakt_library_snapshot")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val store = TraktLibrarySnapshotStore(context, metadataStore)

        val snapshot = sampleSnapshot()
        store.write(snapshot)

        assertEquals(snapshot, store.read())

        assertEquals(snapshot, store.read())
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
        assertTrue(raw.contains("\"listTabs\""))
        assertTrue(raw.contains("\"entriesByList\""))
        assertTrue(raw.contains("\"metadataByContentKey\""))
    }

    @Test
    fun `read restores valid cached entries even when one entry is malformed`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "trakt_library_snapshot")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 1
        val store = TraktLibrarySnapshotStore(context, metadataStore)

        prefs.edit().putString(
            "snapshot",
            """
            {
              "schemaVersion": 1,
              "languageEpoch": 1,
              "listTabs": [
                {
                  "key": "watchlist",
                  "title": "Watchlist",
                  "type": "WATCHLIST",
                  "sortBy": "rank",
                  "sortHow": "asc"
                }
              ],
              "entriesByList": {
                "watchlist": [
                  {
                    "id": "tt1234567",
                    "type": "movie",
                    "name": "Valid Movie",
                    "posterShape": "POSTER",
                    "genres": [],
                    "listKeys": ["watchlist"],
                    "listedAt": 100,
                    "traktId": 10
                  },
                  "totally-invalid-entry"
                ]
              },
              "metadataByContentKey": {
                "movie:tt1234567": {
                  "name": "Hydrated Valid Movie",
                  "poster": "https://image.test/poster.jpg",
                  "genres": ["Drama"]
                }
              },
              "updatedAtMs": 1234
            }
            """.trimIndent()
        ).apply()

        val restored = store.read()

        assertTrue(restored != null)
        assertEquals(listOf("watchlist"), restored!!.listTabs.map { it.key })
        assertEquals(listOf("tt1234567"), restored.entriesByList["watchlist"].orEmpty().map { it.id })
        assertEquals(1234L, restored.updatedAtMs)
    }

    @Test
    fun `prefsName resolves to bare name for profile 1`() {
        assertEquals(
            "trakt_library_snapshot",
            profilePrefsName(TraktLibrarySnapshotStore.BASE_PREFS_NAME, 1)
        )
    }

    @Test
    fun `prefsName resolves to suffixed name for profile 2`() {
        assertEquals(
            "trakt_library_snapshot_p2",
            profilePrefsName(TraktLibrarySnapshotStore.BASE_PREFS_NAME, 2)
        )
    }

    @Test
    fun `prefsName resolves to suffixed name for profile 4`() {
        assertEquals(
            "trakt_library_snapshot_p4",
            profilePrefsName(TraktLibrarySnapshotStore.BASE_PREFS_NAME, 4)
        )
    }

    @Test
    fun `BASE_PREFS_NAME is trakt_library_snapshot`() {
        assertEquals("trakt_library_snapshot", TraktLibrarySnapshotStore.BASE_PREFS_NAME)
    }

    @Test
    fun `builder emits a profile scoped trakt namespace and canonical media identities`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(
            prefs,
            profilePrefsName(TraktLibrarySnapshotStore.BASE_PREFS_NAME, 7)
        )
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 7
        val store = TraktLibrarySnapshotStore(context, metadataStore)
        val watchlist = store.buildRailMemberships(sampleSnapshot(), profileId = 7)
            .first { it.rail.paramsHash == "watchlist" }
        assertEquals("profile:7:library:trakt:watchlist", watchlist.rail.railKey)
        assertEquals("movie:imdb:tt1234567", watchlist.items.single().mediaKey)
        assertTrue(watchlist.externalIds.any { it.provider == "IMDB" && it.externalId == "tt1234567" })
        assertTrue(watchlist.externalIds.any { it.provider == "TRAKT" && it.externalId == "10" })
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
