package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that [TransportPolicyController.onSeek] selects the SEEK preset, which uses the
 * full envelope urgent chunk (not the 2 MiB STARTUP clamp). This ensures post-seek throughput
 * recovers immediately when Media3 reopens ParallelRangeDataSource after a non-contiguous seek.
 */
class TransportPolicyControllerSeekPolicyTest {

    // RD locked envelope: urgentChunk = 33_554_432 (32 MiB)
    private val rdEnvelope = CapabilityEnvelope.LOCKED_REAL_DEBRID

    // PM locked envelope: urgentChunk = 16_777_216 (16 MiB)
    private val pmEnvelope = CapabilityEnvelope.LOCKED_PREMIUMIZE

    @Test
    fun `cold start uses STARTUP state with clamped 2 MiB urgent chunk (RD envelope)`() {
        val controller = TransportPolicyController(rdEnvelope, provider = "real_debrid")

        assertEquals(TransportState.STARTUP, controller.state)
        assertEquals(STARTUP_URGENT_CHUNK_BYTES, controller.currentPolicy.urgentChunkBytes)
        assertEquals(0, controller.currentPolicy.prefetchWorkers)
    }

    @Test
    fun `onFirstFrame from STARTUP transitions to STABILIZING (RD envelope)`() {
        val controller = TransportPolicyController(rdEnvelope, provider = "real_debrid")
        controller.onFirstFrame()

        assertEquals(TransportState.STABILIZING, controller.state)
    }

    @Test
    fun `onSteady from STABILIZING transitions to STEADY (RD envelope)`() {
        val controller = TransportPolicyController(rdEnvelope, provider = "real_debrid")
        controller.onFirstFrame()
        controller.onSteady(20_000)

        assertEquals(TransportState.STEADY, controller.state)
    }

    @Test
    fun `onSeek selects SEEK state with full envelope urgent chunk - RD`() {
        val controller = TransportPolicyController(rdEnvelope, provider = "real_debrid")
        controller.onFirstFrame()
        controller.onSteady(20_000)
        assertEquals(TransportState.STEADY, controller.state)

        controller.onSeek()

        assertEquals(TransportState.SEEK, controller.state)
        // Must use full envelope chunk, NOT the 2 MiB STARTUP clamp
        assertEquals(33_554_432L, controller.currentPolicy.urgentChunkBytes)
        // Prefetch suppressed until onFirstFrame() transitions back to STABILIZING
        assertEquals(0, controller.currentPolicy.prefetchWorkers)
    }

    @Test
    fun `onSeek selects SEEK state with full envelope urgent chunk - PM`() {
        val controller = TransportPolicyController(pmEnvelope, provider = "premiumize")
        controller.onFirstFrame()
        controller.onSteady(20_000)
        assertEquals(TransportState.STEADY, controller.state)

        controller.onSeek()

        assertEquals(TransportState.SEEK, controller.state)
        // Must use full envelope chunk, NOT the 2 MiB STARTUP clamp
        assertEquals(16_777_216L, controller.currentPolicy.urgentChunkBytes)
        assertEquals(0, controller.currentPolicy.prefetchWorkers)
    }

    @Test
    fun `SEEK urgent chunk is strictly larger than STARTUP urgent chunk (RD envelope)`() {
        val controller = TransportPolicyController(rdEnvelope, provider = "real_debrid")

        val startupChunk = controller.currentPolicy.urgentChunkBytes
        assertEquals(STARTUP_URGENT_CHUNK_BYTES, startupChunk)

        controller.onSeek()
        val seekChunk = controller.currentPolicy.urgentChunkBytes

        assert(seekChunk > startupChunk) {
            "SEEK urgentChunkBytes ($seekChunk) must be > STARTUP urgentChunkBytes ($startupChunk)"
        }
    }

    @Test
    fun `STARTUP state is still used for cold playback start - not overwritten by seek`() {
        val controller = TransportPolicyController(rdEnvelope, provider = "real_debrid")
        // Verify STARTUP is the initial state (cold start path unchanged)
        assertEquals(TransportState.STARTUP, controller.state)

        // Seek from STARTUP (e.g. user seeks before first frame)
        controller.onSeek()
        assertEquals(TransportState.SEEK, controller.state)

        // A new controller (fresh session) is still STARTUP
        val fresh = TransportPolicyController(rdEnvelope, provider = "real_debrid")
        assertEquals(TransportState.STARTUP, fresh.state)
    }

    @Test
    fun `SEEK urgent workers matches envelope maxSafeUrgentWorkers (RD envelope)`() {
        val controller = TransportPolicyController(rdEnvelope, provider = "real_debrid")
        controller.onSeek()

        assertEquals(rdEnvelope.maxSafeUrgentWorkers, controller.currentPolicy.urgentWorkers)
    }

    @Test
    fun `SEEK urgent workers matches envelope maxSafeUrgentWorkers (PM envelope)`() {
        val controller = TransportPolicyController(pmEnvelope, provider = "premiumize")
        controller.onSeek()

        assertEquals(pmEnvelope.maxSafeUrgentWorkers, controller.currentPolicy.urgentWorkers)
    }
}
