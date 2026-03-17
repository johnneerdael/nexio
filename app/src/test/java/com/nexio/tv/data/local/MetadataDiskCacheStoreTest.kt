package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataDiskCacheStoreTest {

    @Test
    fun `removeHomeUnreferencedMetaEntries evicts dropped feed metadata`() {
        val store = MetadataDiskCacheStore(
            context = mockContext(InMemorySharedPreferences())
        )

        store.writeMeta("movie:tt1", "en", "native", meta("tt1"))
        store.writeMeta("movie:tt2", "en", "native", meta("tt2"))
        store.replaceHomeFeedReferences("home_catalog_snapshot", setOf("movie:tt1"))

        store.removeHomeUnreferencedMetaEntries()

        assertNotNull(store.readMeta("movie:tt1", "en", "native"))
        assertNull(store.readMeta("movie:tt2", "en", "native"))
    }

    @Test
    fun `removeEntriesFromStaleEpochs evicts metadata after locale epoch change`() {
        val store = MetadataDiskCacheStore(
            context = mockContext(InMemorySharedPreferences())
        )

        store.writeMeta("movie:tt1", "en", "native", meta("tt1"))
        assertNotNull(store.readMeta("movie:tt1", "en", "native"))

        store.bumpLanguageEpoch()
        store.removeEntriesFromStaleEpochs()

        assertNull(store.readMeta("movie:tt1", "en", "native"))
    }

    private fun meta(id: String): Meta {
        return Meta(
            id = id,
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Movie $id",
            poster = "poster$id",
            posterShape = PosterShape.POSTER,
            background = "background$id",
            logo = "logo$id",
            description = "description$id",
            releaseInfo = "2024",
            imdbRating = 8.0f,
            genres = listOf("Drama"),
            runtime = "120m",
            director = emptyList(),
            cast = emptyList(),
            videos = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        return mockk {
            every { getSharedPreferences("metadata_disk_cache_v1", Context.MODE_PRIVATE) } returns prefs
        }
    }
}
