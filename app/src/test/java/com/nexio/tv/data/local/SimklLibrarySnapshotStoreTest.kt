package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimklLibrarySnapshotStoreTest {

    @Test
    fun `read restores persisted simkl library snapshot without epoch invalidation`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 0
        val store = SimklLibrarySnapshotStore(context, metadataStore)

        val snapshot = SimklLibrarySnapshotStore.Snapshot(
            listTabs = listOf(
                LibraryListTab(
                    key = "simkl:plantowatch",
                    title = "SIMKL Watchlist",
                    type = LibraryListTab.Type.WATCHLIST
                )
            ),
            entriesByList = mapOf(
                "simkl:plantowatch" to listOf(sampleEntry())
            ),
            updatedAtMs = 100L
        )

        store.write(snapshot)
        assertEquals(snapshot, store.read())

        assertEquals(snapshot, store.read())
    }

    private fun sampleEntry(): LibraryEntry {
        return LibraryEntry(
            id = "tt1375666",
            type = ContentType.MOVIE.toApiString(),
            name = "Inception",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf("simkl:plantowatch"),
            listedAt = 1L
        )
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        return mockk {
            every { getSharedPreferences("simkl_library_snapshot", Context.MODE_PRIVATE) } returns prefs
        }
    }
}
