package com.nexio.tv.data.local

import android.content.Context
import com.google.gson.JsonObject
import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingSnapshotStoreTest {

    @Test
    fun `read restores persisted display metadata for current language epoch`() {
        val prefs = InMemorySharedPreferences()
        var epoch = 3
        val context = mockContext(prefs, "continue_watching_snapshot")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } answers { epoch }
        val store = ContinueWatchingSnapshotStore(context, metadataStore)

        val snapshot = ContinueWatchingSnapshot(
            displayMetadataByItemKey = mapOf(
                "movie:tt123" to HomeDisplayMetadata(
                    title = "Localized Movie",
                    description = "Overview"
                )
            ),
            updatedAtMs = 100L
        )

        store.write(snapshot)

        assertEquals(snapshot.displayMetadataByItemKey, store.read()?.displayMetadataByItemKey)

        epoch = 4
        assertNull(store.read())
    }

    @Test
    fun `write persists generic resumes and next up activity timestamps`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "continue_watching_snapshot")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 1
        val store = ContinueWatchingSnapshotStore(context, metadataStore)

        val snapshot = ContinueWatchingSnapshot(
            resumeItems = listOf(
                WatchProgress(
                    contentId = "show-a",
                    contentType = "series",
                    name = "Show A",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "show-a:1:2",
                    season = 1,
                    episode = 2,
                    episodeTitle = "Episode 2",
                    position = 50L,
                    duration = 100L,
                    lastWatched = 1_000L,
                    progressPercent = 50f
                )
            ),
            nextUpItems = listOf(
                TraktProgressService.NextUpEntry(
                    contentId = "show-b",
                    name = "Show B",
                    season = 2,
                    episode = 3,
                    episodeTitle = "Episode 3",
                    videoId = "show-b:2:3",
                    firstAired = "2026-03-23T00:00:00.000Z",
                    firstAiredMs = 900L,
                    activityAtMs = 1_500L
                )
            ),
            traktUpNextItems = listOf(
                TraktProgressService.NextUpEntry(
                    contentId = "show-b",
                    name = "Show B",
                    season = 2,
                    episode = 3,
                    episodeTitle = "Episode 3",
                    videoId = "show-b:2:3",
                    firstAired = "2026-03-23T00:00:00.000Z",
                    firstAiredMs = 900L,
                    activityAtMs = 1_500L
                )
            ),
            updatedAtMs = 2_000L
        )

        store.write(snapshot)

        val restored = store.read()
        assertEquals(snapshot.resumeItems, restored?.resumeItems)
        assertEquals(1_500L, restored?.nextUpItems?.singleOrNull()?.activityAtMs)
        assertEquals(1_500L, restored?.traktUpNextItems?.singleOrNull()?.activityAtMs)
    }

    @Test
    fun `read decodes legacy movieProgressItems field into resumeItems`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs, "continue_watching_snapshot")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 1
        val store = ContinueWatchingSnapshotStore(context, metadataStore)

        val legacyPayload = JsonObject().apply {
            addProperty("schemaVersion", 2)
            addProperty("languageEpoch", 1)
            add(
                "movieProgressItems",
                com.google.gson.Gson().toJsonTree(
                    listOf(
                        WatchProgress(
                            contentId = "movie-a",
                            contentType = "movie",
                            name = "Movie A",
                            poster = null,
                            backdrop = null,
                            logo = null,
                            videoId = "movie-a",
                            season = null,
                            episode = null,
                            episodeTitle = null,
                            position = 20L,
                            duration = 100L,
                            lastWatched = 1_000L,
                            progressPercent = 20f
                        )
                    )
                )
            )
            add("nextUpItems", com.google.gson.JsonArray())
            add("displayMetadataByItemKey", JsonObject())
            addProperty("updatedAtMs", 1_000L)
        }

        prefs.edit().putString("snapshot", legacyPayload.toString()).apply()

        val restored = store.read()
        assertEquals(listOf("movie-a"), restored?.resumeItems?.map { it.contentId })
        assertTrue(restored?.nextUpItems?.isEmpty() == true)
    }

    private fun mockContext(prefs: InMemorySharedPreferences, expectedName: String): Context {
        return mockk {
            every { getSharedPreferences(expectedName, Context.MODE_PRIVATE) } returns prefs
        }
    }
}
