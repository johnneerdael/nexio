package com.nexio.tv.core.sync

import com.nexio.tv.data.remote.supabase.V10PushResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V10StaleBaseRejectionTest {

    @Test
    fun `applied envelope maps to Applied with revision`() {
        val outcome = mapV10PushResult(
            V10PushResult(
                applied = true,
                currentUpdatedAtMs = 1_800_000L,
                syncRevision = 42L
            )
        )
        assertTrue(outcome is V10PushOutcome.Applied)
        val applied = outcome as V10PushOutcome.Applied
        assertEquals(1_800_000L, applied.currentUpdatedAtMs)
        assertEquals(42L, applied.syncRevision)
    }

    @Test
    fun `stale_base envelope maps to StaleBase`() {
        val outcome = mapV10PushResult(
            V10PushResult(
                applied = false,
                currentUpdatedAtMs = 1_700_000L,
                reason = "stale_base"
            )
        )
        assertTrue(outcome is V10PushOutcome.StaleBase)
        assertEquals(1_700_000L, (outcome as V10PushOutcome.StaleBase).currentUpdatedAtMs)
    }

    @Test
    fun `field_conflict envelope maps to FieldConflict with paths`() {
        val outcome = mapV10PushResult(
            V10PushResult(
                applied = false,
                currentUpdatedAtMs = 1_750_000L,
                reason = "field_conflict",
                conflictPaths = listOf("integrations.tmdb", "integrations.subtitleTranslation"),
                syncRevision = 99L
            )
        )
        assertTrue(outcome is V10PushOutcome.FieldConflict)
        val conflict = outcome as V10PushOutcome.FieldConflict
        assertEquals(1_750_000L, conflict.currentUpdatedAtMs)
        assertEquals(listOf("integrations.tmdb", "integrations.subtitleTranslation"), conflict.conflictPaths)
        assertEquals(99L, conflict.syncRevision)
    }

    @Test
    fun `unknown reason maps to Failed`() {
        val outcome = mapV10PushResult(
            V10PushResult(
                applied = false,
                currentUpdatedAtMs = 0L,
                reason = "ghost_in_the_machine"
            )
        )
        assertTrue(outcome is V10PushOutcome.Failed)
    }

    @Test
    fun `runV10Push wraps thrown exceptions as Failed`() = runTest {
        val outcome = runV10Push { throw IllegalStateException("network down") }
        assertTrue(outcome is V10PushOutcome.Failed)
        assertEquals("network down", (outcome as V10PushOutcome.Failed).cause.message)
    }

    @Test
    fun `runV10Push propagates a successful applied envelope`() = runTest {
        val outcome = runV10Push {
            V10PushResult(applied = true, currentUpdatedAtMs = 5_000L, syncRevision = 1L)
        }
        assertTrue(outcome is V10PushOutcome.Applied)
        assertEquals(5_000L, (outcome as V10PushOutcome.Applied).currentUpdatedAtMs)
    }
}
