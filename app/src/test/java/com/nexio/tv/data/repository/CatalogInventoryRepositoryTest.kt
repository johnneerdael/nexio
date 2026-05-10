package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogInventoryRepositoryTest {

    private fun row(addonId: String = "a", apiType: String = "movie", catalogId: String = "c", items: List<MetaPreview> = emptyList()): CatalogRow =
        CatalogRow(
            addonId = addonId,
            addonName = addonId,
            addonBaseUrl = "",
            catalogId = catalogId,
            catalogName = catalogId,
            type = ContentType.fromString(apiType),
            rawType = apiType,
            items = items
        )

    private fun preview(id: String, apiType: String = "movie"): MetaPreview =
        MetaPreview(
            id = id,
            type = ContentType.fromString(apiType),
            rawType = apiType,
            name = id,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        )

    @Test
    fun `publish then snapshot returns map keyed by addonId_apiType_catalogId`() {
        val repo = CatalogInventoryRepository()
        val r1 = row(addonId = "addonX", apiType = "movie", catalogId = "popular")
        repo.publish(listOf(r1))
        assertSame(r1, repo.snapshot()["addonX_movie_popular"])
    }

    @Test
    fun `publish preserves insertion order via LinkedHashMap`() {
        val repo = CatalogInventoryRepository()
        val r1 = row(addonId = "z", catalogId = "1")
        val r2 = row(addonId = "a", catalogId = "2")
        val r3 = row(addonId = "m", catalogId = "3")
        repo.publish(listOf(r1, r2, r3))
        assertEquals(listOf("z_movie_1", "a_movie_2", "m_movie_3"), repo.snapshot().keys.toList())
    }

    @Test
    fun `publish overwrites prior entry for same triple key`() {
        val repo = CatalogInventoryRepository()
        val r1 = row(addonId = "a", catalogId = "c", items = listOf(preview("x")))
        val r2 = row(addonId = "a", catalogId = "c", items = listOf(preview("y")))
        repo.publish(listOf(r1, r2))
        assertSame(r2, repo.snapshot()["a_movie_c"])
        assertEquals(1, repo.snapshot().size)
    }

    @Test
    fun `publish skips rows with blank addonId apiType or catalogId`() {
        val repo = CatalogInventoryRepository()
        val good = row(addonId = "ok", apiType = "movie", catalogId = "c")
        val blankAddon = row(addonId = "", apiType = "movie", catalogId = "c")
        val blankApi = row(addonId = "ok", apiType = "", catalogId = "c")
        val blankCatalog = row(addonId = "ok", apiType = "movie", catalogId = "")
        repo.publish(listOf(good, blankAddon, blankApi, blankCatalog))
        assertEquals(setOf("ok_movie_c"), repo.snapshot().keys)
    }

    @Test
    fun `isEmpty true on init and after clear`() {
        val repo = CatalogInventoryRepository()
        assertTrue(repo.isEmpty())
        repo.publish(listOf(row()))
        repo.clear()
        assertTrue(repo.isEmpty())
    }

    @Test
    fun `isEmpty false after non-empty publish`() {
        val repo = CatalogInventoryRepository()
        repo.publish(listOf(row()))
        assertFalse(repo.isEmpty())
    }

    @Test
    fun `observeRail emits initial null then rail when published`() = runTest {
        val repo = CatalogInventoryRepository()
        val emissions = mutableListOf<CatalogRow?>()
        val job = launch {
            repo.observeRail("a_movie_c").collect { emissions += it }
        }
        kotlinx.coroutines.yield()
        repo.publish(listOf(row(addonId = "a", catalogId = "c")))
        kotlinx.coroutines.yield()
        job.cancel()
        assertNull(emissions.first())
        org.junit.Assert.assertNotNull(emissions.last())
    }

    @Test
    fun `observeRail filters distinct — same content yields one emission`() = runTest {
        val repo = CatalogInventoryRepository()
        val r1 = row(addonId = "a", catalogId = "c")
        val emissions = mutableListOf<CatalogRow?>()
        val job = launch {
            repo.observeRail("a_movie_c").collect { emissions += it }
        }
        kotlinx.coroutines.yield()
        repo.publish(listOf(r1))
        kotlinx.coroutines.yield()
        repo.publish(listOf(r1))  // same content, same reference
        kotlinx.coroutines.yield()
        job.cancel()
        // initial null + one rail emission = 2 total; the second publish does not emit
        assertEquals(2, emissions.size)
    }

    @Test
    fun `observeRail returns null when rail removed from publish`() = runTest {
        val repo = CatalogInventoryRepository()
        val r1 = row(addonId = "a", catalogId = "c")
        repo.publish(listOf(r1))
        val emissions = mutableListOf<CatalogRow?>()
        val job = launch {
            repo.observeRail("a_movie_c").collect { emissions += it }
        }
        kotlinx.coroutines.yield()
        repo.publish(emptyList())  // rail disappears
        kotlinx.coroutines.yield()
        job.cancel()
        assertEquals(r1, emissions.first())
        assertNull(emissions.last())
    }

    @Test
    fun `clear empties the inventory`() {
        val repo = CatalogInventoryRepository()
        repo.publish(listOf(row()))
        repo.clear()
        assertTrue(repo.snapshot().isEmpty())
    }

    @Test
    fun `activeItemKeys aggregates apiType colon id across all rails`() {
        val repo = CatalogInventoryRepository()
        val r1 = row(addonId = "a", apiType = "movie", catalogId = "c1", items = listOf(preview("x", "movie"), preview("y", "movie")))
        val r2 = row(addonId = "b", apiType = "series", catalogId = "c2", items = listOf(preview("z", "series")))
        repo.publish(listOf(r1, r2))
        assertEquals(setOf("movie:x", "movie:y", "series:z"), repo.activeItemKeys())
    }

    @Test
    fun `concurrent publish and snapshot does not tear`() = runTest {
        val repo = CatalogInventoryRepository()
        val rowsA = (1..50).map { row(catalogId = "a$it") }
        val rowsB = (1..50).map { row(catalogId = "b$it") }
        coroutineScope {
            val publish = async {
                repeat(20) {
                    repo.publish(if (it % 2 == 0) rowsA else rowsB)
                }
            }
            val read = async {
                repeat(20) {
                    val snap = repo.snapshot()
                    // every snapshot must be coherent: all keys belong to one batch
                    val first = snap.keys.firstOrNull()?.split("_")?.last()?.first()
                    if (first != null) {
                        assertTrue(snap.keys.all { it.split("_").last().first() == first })
                    }
                }
            }
            publish.await(); read.await()
        }
    }
}
