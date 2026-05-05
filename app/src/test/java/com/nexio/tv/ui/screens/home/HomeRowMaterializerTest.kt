package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.ui.screens.home.order.EffectiveHomeRailOrder
import com.nexio.tv.ui.screens.home.order.HomeRailKey
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRowMaterializerTest {
    private fun row(label: String): CatalogRow = mockk(relaxed = true) {
        // CatalogRow is a domain type; use a relaxed mock as a stand-in for the
        // tests below since identity equality (referential) is sufficient.
        // The real CatalogRow has many fields; we don't need them here.
    }

    @Test
    fun `live synthetic groups win over persisted synthetic groups`() {
        val keyA = HomeRailKey("a")
        val liveRowA = row("liveA")
        val persistedRowA = row("persistedA")
        val effective = EffectiveHomeRailOrder.Empty.copy(visibleKeys = listOf(keyA))

        val result = materializeHomeRows(
            effectiveOrder = effective,
            liveSyntheticGroupsByKey = mapOf(keyA to listOf(liveRowA)),
            persistedSyntheticGroupsByKey = mapOf(keyA to listOf(persistedRowA)),
            rawRowsByKey = emptyMap(),
            pendingRowsByKey = emptyMap(),
        )

        assertEquals(listOf(liveRowA), result)
    }

    @Test
    fun `persisted synthetic groups serve as fallback when no live group`() {
        val keyA = HomeRailKey("a")
        val persistedRowA = row("persistedA")
        val effective = EffectiveHomeRailOrder.Empty.copy(visibleKeys = listOf(keyA))

        val result = materializeHomeRows(
            effectiveOrder = effective,
            liveSyntheticGroupsByKey = emptyMap(),
            persistedSyntheticGroupsByKey = mapOf(keyA to listOf(persistedRowA)),
            rawRowsByKey = emptyMap(),
            pendingRowsByKey = emptyMap(),
        )

        assertEquals(listOf(persistedRowA), result)
    }

    @Test
    fun `output order matches effective visible keys order`() {
        val keyA = HomeRailKey("a")
        val keyB = HomeRailKey("b")
        val keyC = HomeRailKey("c")
        val rowA = row("rA")
        val rowB = row("rB")
        val rowC = row("rC")
        val effective = EffectiveHomeRailOrder.Empty.copy(
            visibleKeys = listOf(keyB, keyA, keyC),
        )

        val result = materializeHomeRows(
            effectiveOrder = effective,
            liveSyntheticGroupsByKey = mapOf(keyA to listOf(rowA), keyB to listOf(rowB)),
            persistedSyntheticGroupsByKey = emptyMap(),
            rawRowsByKey = mapOf(keyC to rowC),
            pendingRowsByKey = emptyMap(),
        )

        // Effective order says B, A, C — so output is B, A, C (not the input maps' order).
        assertEquals(listOf(rowB, rowA, rowC), result)
    }

    @Test
    fun `priority is live then raw then persisted then pending`() {
        val keyA = HomeRailKey("a")
        val liveRow = row("live")
        val rawRow = row("raw")
        val persistedRow = row("persisted")
        val pendingRow = row("pending")

        // All four sources have content for keyA — assert live wins.
        val withLive = materializeHomeRows(
            effectiveOrder = EffectiveHomeRailOrder.Empty.copy(visibleKeys = listOf(keyA)),
            liveSyntheticGroupsByKey = mapOf(keyA to listOf(liveRow)),
            persistedSyntheticGroupsByKey = mapOf(keyA to listOf(persistedRow)),
            rawRowsByKey = mapOf(keyA to rawRow),
            pendingRowsByKey = mapOf(keyA to pendingRow),
        )
        assertEquals(listOf(liveRow), withLive)

        // No live — raw wins next.
        val withRaw = materializeHomeRows(
            effectiveOrder = EffectiveHomeRailOrder.Empty.copy(visibleKeys = listOf(keyA)),
            liveSyntheticGroupsByKey = emptyMap(),
            persistedSyntheticGroupsByKey = mapOf(keyA to listOf(persistedRow)),
            rawRowsByKey = mapOf(keyA to rawRow),
            pendingRowsByKey = mapOf(keyA to pendingRow),
        )
        assertEquals(listOf(rawRow), withRaw)

        // No live, no raw — persisted wins next.
        val withPersisted = materializeHomeRows(
            effectiveOrder = EffectiveHomeRailOrder.Empty.copy(visibleKeys = listOf(keyA)),
            liveSyntheticGroupsByKey = emptyMap(),
            persistedSyntheticGroupsByKey = mapOf(keyA to listOf(persistedRow)),
            rawRowsByKey = emptyMap(),
            pendingRowsByKey = mapOf(keyA to pendingRow),
        )
        assertEquals(listOf(persistedRow), withPersisted)

        // Only pending — pending wins.
        val withPending = materializeHomeRows(
            effectiveOrder = EffectiveHomeRailOrder.Empty.copy(visibleKeys = listOf(keyA)),
            liveSyntheticGroupsByKey = emptyMap(),
            persistedSyntheticGroupsByKey = emptyMap(),
            rawRowsByKey = emptyMap(),
            pendingRowsByKey = mapOf(keyA to pendingRow),
        )
        assertEquals(listOf(pendingRow), withPending)
    }

    @Test
    fun `key with no content in any map produces no row`() {
        val keyA = HomeRailKey("a")
        val keyB = HomeRailKey("b")
        val rowB = row("rB")
        val effective = EffectiveHomeRailOrder.Empty.copy(visibleKeys = listOf(keyA, keyB))

        val result = materializeHomeRows(
            effectiveOrder = effective,
            liveSyntheticGroupsByKey = emptyMap(),
            persistedSyntheticGroupsByKey = emptyMap(),
            rawRowsByKey = mapOf(keyB to rowB),
            pendingRowsByKey = emptyMap(),
        )

        assertEquals(listOf(rowB), result)
    }
}
