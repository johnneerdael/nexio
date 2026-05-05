package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class SyntheticGroupContentMapsTest {
    @Suppress("UNUSED_PARAMETER")
    private fun row(label: String): CatalogRow = mockk(relaxed = true)

    @Test
    fun `live synthetic content wins over persisted synthetic content for same orderKey`() {
        val keyA = "tmdb_popular_movies"
        val liveRowA = row("live-a")
        val persistedRowA = row("persisted-a")

        val maps = buildSyntheticGroupContentMaps(
            persistedSyntheticGroupsByKey = mapOf(keyA to listOf(persistedRowA)),
            liveSyntheticGroupsByKey = mapOf(keyA to listOf(liveRowA)),
            rawRowsByOrderKey = emptyMap(),
            homeCatalogGlobalKey = { "global-${it.hashCode()}" },
        )

        // Live wins.
        assertEquals(listOf(liveRowA), maps.syntheticRowsByKey[keyA])
        // existingRowsByOrderKey gets the first live row (since live wins in syntheticRowsByKey).
        assertEquals(liveRowA, maps.existingRowsByOrderKey[keyA])
    }

    @Test
    fun `raw rows win over synthetic content in existingRowsByOrderKey only when key is exclusively raw`() {
        val keyA = "tmdb_popular_movies"
        val keyB = "raw_only_key"
        val rawRowB = row("raw-b")
        val liveRowA = row("live-a")

        val maps = buildSyntheticGroupContentMaps(
            persistedSyntheticGroupsByKey = emptyMap(),
            liveSyntheticGroupsByKey = mapOf(keyA to listOf(liveRowA)),
            rawRowsByOrderKey = mapOf(keyB to rawRowB),
            homeCatalogGlobalKey = { "global-${it.hashCode()}" },
        )

        assertEquals(rawRowB, maps.existingRowsByOrderKey[keyB])
        assertEquals(liveRowA, maps.existingRowsByOrderKey[keyA])
    }

    @Test
    fun `rowOrderKeyByGlobalKey maps each synthetic row's global key to its orderKey`() {
        val keyA = "tmdb_popular_movies"
        val row1 = row("g1")
        val row2 = row("g2")

        val maps = buildSyntheticGroupContentMaps(
            persistedSyntheticGroupsByKey = emptyMap(),
            liveSyntheticGroupsByKey = mapOf(keyA to listOf(row1, row2)),
            rawRowsByOrderKey = emptyMap(),
            homeCatalogGlobalKey = { "g-${it.hashCode()}" },
        )

        assertEquals(keyA, maps.rowOrderKeyByGlobalKey["g-${row1.hashCode()}"])
        assertEquals(keyA, maps.rowOrderKeyByGlobalKey["g-${row2.hashCode()}"])
    }
}
