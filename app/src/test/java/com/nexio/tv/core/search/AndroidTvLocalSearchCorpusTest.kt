package com.nexio.tv.core.search

import android.content.Context
import com.nexio.tv.data.local.HomeCatalogSnapshotStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTvLocalSearchCorpusTest {

    @Test
    fun `corpus returns routeable candidates from home snapshot rows`() = runTest {
        val snapshotStore = snapshotStore(
            HomeCatalogSnapshotStore.Snapshot(
                catalogRows = listOf(row(ContentType.MOVIE, "movie-1", "The Matrix")),
                fullCatalogRows = listOf(row(ContentType.SERIES, "series-1", "Daredevil: Born Again")),
                heroItems = emptyList()
            )
        )

        val candidates = corpus(snapshotStore).candidates()

        assertEquals(listOf("The Matrix", "Daredevil: Born Again"), candidates.map { it.title })
        assertEquals(setOf(AndroidTvSearchCandidateSource.LOCAL_HOME), candidates.map { it.source }.toSet())
        assertEquals(setOf("https://example.com"), candidates.map { it.addonBaseUrl }.toSet())
    }

    @Test
    fun `hero item dedupes with routeable row item and keeps richer metadata`() = runTest {
        val rowItem = preview(ContentType.MOVIE, "movie-1", "The Matrix", runtime = null)
        val heroItem = rowItem.copy(runtime = "136 min", description = "Rich")
        val snapshotStore = snapshotStore(
            HomeCatalogSnapshotStore.Snapshot(
                catalogRows = listOf(row(ContentType.MOVIE, rowItem)),
                fullCatalogRows = emptyList(),
                heroItems = listOf(heroItem)
            )
        )

        val candidate = corpus(snapshotStore).candidates().single()

        assertEquals("136 min", candidate.runtime)
        assertEquals("https://example.com", candidate.addonBaseUrl)
    }

    @Test
    fun `snapshot absence returns empty candidates`() = runTest {
        val snapshotStore = mockk<HomeCatalogSnapshotStore>()
        coEvery { snapshotStore.currentPosterProviderToken() } returns "native"
        every { snapshotStore.readActiveProfile("native") } returns null

        assertTrue(corpus(snapshotStore).candidates().isEmpty())
    }

    private fun corpus(snapshotStore: HomeCatalogSnapshotStore): AndroidTvLocalSearchCorpus {
        val metadataStore = mockk<MetadataDiskCacheStore>()
        every { metadataStore.readCurrentMetaForItem(any(), any()) } returns null
        val localePrefs = InMemorySharedPreferences().also {
            it.edit().putString("locale_tag", "en").apply()
        }
        val context = mockk<Context> {
            every { getSharedPreferences("app_locale", Context.MODE_PRIVATE) } returns localePrefs
        }
        return AndroidTvLocalSearchCorpus(
            appContext = context,
            homeCatalogSnapshotStore = snapshotStore,
            metadataDiskCacheStore = metadataStore
        )
    }

    private fun snapshotStore(snapshot: HomeCatalogSnapshotStore.Snapshot): HomeCatalogSnapshotStore {
        return mockk {
            coEvery { currentPosterProviderToken() } returns "native"
            every { readActiveProfile("native") } returns snapshot
        }
    }

    private fun row(type: ContentType, id: String, title: String): CatalogRow {
        return row(type, preview(type, id, title))
    }

    private fun row(type: ContentType, item: MetaPreview): CatalogRow {
        return CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://example.com",
            catalogId = "catalog",
            catalogName = "Catalog",
            type = type,
            items = listOf(item)
        )
    }

    private fun preview(
        type: ContentType,
        id: String,
        title: String,
        runtime: String? = "120 min"
    ): MetaPreview {
        return MetaPreview(
            id = id,
            type = type,
            name = title,
            poster = "poster",
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = "description",
            releaseInfo = "1999",
            runtime = runtime,
            imdbRating = null,
            genres = emptyList()
        )
    }
}
