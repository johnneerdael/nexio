package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun mockContext(prefs: InMemorySharedPreferences, expectedName: String): Context {
        return mockk {
            every { getSharedPreferences(expectedName, Context.MODE_PRIVATE) } returns prefs
        }
    }
}
