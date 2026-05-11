package com.nexio.tv.data.local

import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResolvedDisplaySnapshotStoreTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private fun storeFor(profileId: Int = 1, lang: String = "en"): ResolvedDisplaySnapshotStore =
        ResolvedDisplaySnapshotStore.forTesting(
            rootDir = tempFolder.newFolder("resolved-display-v1"),
            activeProfileId = { profileId },
            currentLanguageTag = { lang },
        )

    private fun sampleItem(itemKey: String, title: String): ResolvedDisplayItem = ResolvedDisplayItem(
        itemKey = itemKey,
        contentId = itemKey.substringAfterLast(':'),
        parentId = itemKey.substringAfterLast(':'),
        itemType = ContentType.MOVIE,
        mediaKind = MetadataMediaKind.MOVIE,
        canonicalProvider = null,
        canonicalId = null,
        imdbId = null,
        stableIds = ProviderIds(),
        display = ResolvedDisplayFields(
            title = title, originalTitle = null, year = null, releaseDate = null,
            overview = null, genres = emptyList(), runtimeText = null
        ),
        artwork = ArtworkBundle(),
        rating = null,
        trailer = TrailerDisplayState(),
        hydrationState = HydrationState.PREVIEW_ONLY,
        sourceTrace = emptyList(),
        updatedAtMs = 0L,
        slots = null
    )

    @Test
    fun `round-trip empty map`() {
        val store = storeFor()
        store.write(emptyMap())
        val read = store.read()
        assertEquals(emptyMap<String, ResolvedDisplayItem>(), read)
    }

    @Test
    fun `round-trip single item`() {
        val store = storeFor()
        val item = sampleItem("movie:tt1234567", "Test Movie")
        store.write(mapOf(item.itemKey to item))
        val read = store.read()
        assertEquals(1, read.size)
        assertEquals("Test Movie", read[item.itemKey]?.display?.title)
    }

    @Test
    fun `round-trip multiple items preserves keys and values`() {
        val store = storeFor()
        val items = (1..5).associate { i ->
            "movie:tt$i" to sampleItem("movie:tt$i", "Movie $i")
        }
        store.write(items)
        val read = store.read()
        assertEquals(5, read.size)
        items.forEach { (key, expected) ->
            assertEquals(expected.display.title, read[key]?.display?.title)
        }
    }

    @Test
    fun `read returns empty map when file missing`() {
        val store = storeFor()
        val read = store.read()
        assertTrue(read.isEmpty())
    }
}
