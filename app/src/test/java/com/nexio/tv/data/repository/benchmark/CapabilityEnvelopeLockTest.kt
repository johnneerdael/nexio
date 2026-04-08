package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityEnvelopeLockTest {

    @Test
    fun `lockedFor real_debrid returns LOCKED_REAL_DEBRID with correct shape fields`() {
        val result = CapabilityEnvelope.lockedFor("real_debrid")
        assertNotNull(result)
        assertEquals(1, result!!.maxSafeUrgentWorkers)
        assertEquals(1, result.maxSafePrefetchWorkers)
        assertEquals(33_554_432L, result.maxSafeUrgentChunkBytes)
        assertEquals(67_108_864L, result.maxSafePrefetchChunkBytes)
        assertTrue(result.supportsRangeRequests)
    }

    @Test
    fun `lockedFor premiumize returns LOCKED_PREMIUMIZE with correct shape fields`() {
        val result = CapabilityEnvelope.lockedFor("premiumize")
        assertNotNull(result)
        assertEquals(2, result!!.maxSafeUrgentWorkers)
        assertEquals(1, result.maxSafePrefetchWorkers)
        assertEquals(16_777_216L, result.maxSafeUrgentChunkBytes)
        assertEquals(16_777_216L, result.maxSafePrefetchChunkBytes)
        assertTrue(result.supportsRangeRequests)
    }

    @Test
    fun `lockedFor torbox returns null`() {
        assertNull(CapabilityEnvelope.lockedFor("torbox"))
    }

    @Test
    fun `lockedFor easy_debrid returns null`() {
        assertNull(CapabilityEnvelope.lockedFor("easy_debrid"))
    }

    @Test
    fun `JSON round-trip preserves locked shape for real_debrid`() {
        val locked = CapabilityEnvelope.LOCKED_REAL_DEBRID
        val json = locked.toJson()
        val restored = CapabilityEnvelope.fromJson(json)
        assertNotNull(restored)
        assertEquals(locked.maxSafeUrgentWorkers, restored!!.maxSafeUrgentWorkers)
        assertEquals(locked.maxSafePrefetchWorkers, restored.maxSafePrefetchWorkers)
        assertEquals(locked.maxSafeUrgentChunkBytes, restored.maxSafeUrgentChunkBytes)
        assertEquals(locked.maxSafePrefetchChunkBytes, restored.maxSafePrefetchChunkBytes)
        assertEquals(locked.supportsRangeRequests, restored.supportsRangeRequests)
    }

    @Test
    fun `matchesLockedShape rejects mutated chunk size`() {
        val locked = CapabilityEnvelope.LOCKED_REAL_DEBRID
        val mutated = locked.copy(maxSafeUrgentChunkBytes = 8_388_608L) // 8 MiB instead of 32 MiB
        assertFalse(locked.matchesLockedShape(mutated))
    }

    @Test
    fun `matchesLockedShape accepts measurement-only differences`() {
        val locked = CapabilityEnvelope.LOCKED_REAL_DEBRID
        val withMeasurement = locked.copy(sustainedThroughputMbps = 748.0, measuredAtMs = 1_700_000_000_000L)
        assertTrue(locked.matchesLockedShape(withMeasurement))
    }
}
