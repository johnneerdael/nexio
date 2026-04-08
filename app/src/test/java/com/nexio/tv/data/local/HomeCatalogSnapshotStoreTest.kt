package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeCatalogSnapshotStoreTest {

    @Test
    fun `read restores persisted home snapshot for matching language and epoch`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        var epoch = 7
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } answers { epoch }
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore
        )

        val snapshot = sampleSnapshot()
        store.write(snapshot)

        assertEquals(snapshot, store.read())
    }

    @Test
    fun `read rejects persisted home snapshot when app language changes`() {
        val snapshotPrefs = InMemorySharedPreferences()
        val localePrefs = localePrefs("en")
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.currentLanguageEpoch() } returns 7
        val store = HomeCatalogSnapshotStore(
            context = mockContext(snapshotPrefs, "home_catalog_snapshot", localePrefs),
            metadataDiskCacheStore = metadataStore
        )

        store.write(sampleSnapshot())
        localePrefs.edit().putString("locale_tag", "nl").apply()

        assertNull(store.read())
    }

    private fun sampleSnapshot(): HomeCatalogSnapshotStore.Snapshot {
        val row = sampleRow("addon", "movies")
        return HomeCatalogSnapshotStore.Snapshot(
            catalogRows = listOf(row),
            fullCatalogRows = listOf(row),
            heroItems = row.items,
            orderedGroupKeys = listOf("addon_movie_movies")
        )
    }

    private fun sampleRow(addonId: String, catalogId: String): CatalogRow {
        return CatalogRow(
            addonId = addonId,
            addonName = addonId,
            addonBaseUrl = "https://example.com/$addonId",
            catalogId = catalogId,
            catalogName = "Catalog $catalogId",
            type = ContentType.MOVIE,
            items = listOf(
                MetaPreview(
                    id = "tt123",
                    type = ContentType.MOVIE,
                    rawType = "movie",
                    name = "Sample",
                    poster = "poster",
                    posterShape = PosterShape.POSTER,
                    background = "background",
                    logo = "logo",
                    description = "description",
                    releaseInfo = "2025",
                    imdbRating = 8.1f,
                    genres = listOf("Drama")
                )
            )
        )
    }

    private fun localePrefs(tag: String): InMemorySharedPreferences {
        return InMemorySharedPreferences().also { prefs ->
            prefs.edit().putString("locale_tag", tag).apply()
        }
    }

    private fun mockContext(
        snapshotPrefs: InMemorySharedPreferences,
        expectedName: String,
        localePrefs: InMemorySharedPreferences
    ): Context {
        return mockk {
            every { getSharedPreferences(any(), Context.MODE_PRIVATE) } answers {
                when (firstArg<String>()) {
                    expectedName -> snapshotPrefs
                    "app_locale" -> localePrefs
                    else -> throw IllegalArgumentException("Unexpected prefs ${firstArg<String>()}")
                }
            }
        }
    }
}
