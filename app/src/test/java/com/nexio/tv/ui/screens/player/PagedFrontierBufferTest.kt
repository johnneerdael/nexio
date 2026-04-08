package com.nexio.tv.ui.screens.player

import com.nexio.tv.instrumentation.ClientIdentitySnapshot
import com.nexio.tv.instrumentation.DeviceProvenance
import com.nexio.tv.instrumentation.FactoryArgs
import com.nexio.tv.instrumentation.PlaybackTracer
import com.nexio.tv.instrumentation.PolicySnapshot
import com.nexio.tv.instrumentation.SessionHeader
import com.nexio.tv.instrumentation.SessionWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.StringWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
class PagedFrontierBufferTest {

    private val PAGE_SIZE = PagedFrontierBuffer.PAGE_SIZE

    @After
    fun tearDown() {
        PlaybackTracer.enabled = false
        PlaybackTracer.currentInternal()?.let { PlaybackTracer.endSession(it.sessionId) }
        PlaybackTracer.installWriterFactory(null)
    }

    // -------------------------------------------------------------------------
    // Helper: write a full page worth of bytes at the given page index
    // -------------------------------------------------------------------------
    private fun PagedFrontierBuffer.writePage(pageIndex: Int, fillByte: Byte = pageIndex.toByte()) {
        val data = ByteArray(PAGE_SIZE) { fillByte }
        onBytesWritten(pageIndex.toLong() * PAGE_SIZE, data, 0, PAGE_SIZE)
    }

    // -------------------------------------------------------------------------
    // 1. Non-contiguous pages: frontier only advances through filled gaps
    // -------------------------------------------------------------------------
    @Test
    fun `frontier does not advance past gap in completed pages`() {
        val buffer = PagedFrontierBuffer()
        buffer.setTotalLength(4L * PAGE_SIZE)

        // Write pages 0, 1, 3 — skip page 2
        buffer.writePage(0, 0x00)
        buffer.writePage(1, 0x01)
        buffer.writePage(3, 0x03)

        // Frontier should stop at boundary of page 2 (= 2 * PAGE_SIZE)
        assertEquals(2L * PAGE_SIZE, buffer.frontier)

        // Fill the gap
        buffer.writePage(2, 0x02)

        // Now frontier should advance past all four complete pages
        assertEquals(4L * PAGE_SIZE, buffer.frontier)
    }

    // -------------------------------------------------------------------------
    // 2. read() returns data from contiguous region; returns 0 past frontier
    // -------------------------------------------------------------------------
    @Test
    fun `read returns data within contiguous region and zero past frontier`() {
        val buffer = PagedFrontierBuffer()
        buffer.setTotalLength(3L * PAGE_SIZE)

        val expected = ByteArray(PAGE_SIZE) { 0xAB.toByte() }
        buffer.onBytesWritten(0L, expected, 0, PAGE_SIZE)
        buffer.writePage(1, 0x00)
        buffer.writePage(2, 0x00)

        // Read first page
        val dest = ByteArray(PAGE_SIZE)
        val read = buffer.read(0L, dest, 0, PAGE_SIZE)
        assertEquals(PAGE_SIZE, read)
        assertArrayEquals(expected, dest)

        // Reading past the frontier returns 0
        val beyond = buffer.read(3L * PAGE_SIZE, dest, 0, PAGE_SIZE)
        assertEquals(0, beyond)
    }

    // -------------------------------------------------------------------------
    // 3. evictBefore() returns buffers to pool and removes page entries
    // -------------------------------------------------------------------------
    @Test
    fun `evictBefore removes pages before position and returns them to pool`() {
        val buffer = PagedFrontierBuffer()
        buffer.setTotalLength(4L * PAGE_SIZE)

        buffer.writePage(0)
        buffer.writePage(1)
        buffer.writePage(2)
        buffer.writePage(3)

        assertEquals(4L * PAGE_SIZE, buffer.frontier)

        // Evict everything before page 2
        buffer.evictBefore(2L * PAGE_SIZE)

        // Pages 0 and 1 should be gone; reading them returns 0
        val dest = ByteArray(PAGE_SIZE)
        val fromPage0 = buffer.read(0L, dest, 0, PAGE_SIZE)
        assertEquals(0, fromPage0)

        // Pages 2 and 3 are still readable (frontier unchanged)
        val fromPage2 = buffer.read(2L * PAGE_SIZE, dest, 0, PAGE_SIZE)
        assertEquals(PAGE_SIZE, fromPage2)
    }

    // -------------------------------------------------------------------------
    // 4. Partial page writes: page becomes complete only when all bytes arrive
    // -------------------------------------------------------------------------
    @Test
    fun `partial writes complete a page only after all bytes are written`() {
        val buffer = PagedFrontierBuffer()
        buffer.setTotalLength(PAGE_SIZE.toLong())

        val half = PAGE_SIZE / 2
        val firstHalf = ByteArray(half) { 0x11 }
        val secondHalf = ByteArray(half) { 0x22 }

        // Write first half — page should NOT be complete yet
        buffer.onBytesWritten(0L, firstHalf, 0, half)
        assertEquals(0L, buffer.frontier)

        // Write second half — page is now complete
        buffer.onBytesWritten(half.toLong(), secondHalf, 0, half)
        assertEquals(PAGE_SIZE.toLong(), buffer.frontier)

        // Verify the full page content is correct
        val dest = ByteArray(PAGE_SIZE)
        val read = buffer.read(0L, dest, 0, PAGE_SIZE)
        assertEquals(PAGE_SIZE, read)
        for (i in 0 until half) assertEquals(0x11.toByte(), dest[i])
        for (i in half until PAGE_SIZE) assertEquals(0x22.toByte(), dest[i])
    }

    // -------------------------------------------------------------------------
    // 5. reset() clears all state
    // -------------------------------------------------------------------------
    @Test
    fun `reset clears all pages and resets frontier to zero`() {
        val buffer = PagedFrontierBuffer()
        buffer.setTotalLength(2L * PAGE_SIZE)

        buffer.writePage(0)
        buffer.writePage(1)
        assertEquals(2L * PAGE_SIZE, buffer.frontier)

        buffer.reset()

        assertEquals(0L, buffer.frontier)

        // No data should be readable after reset
        val dest = ByteArray(PAGE_SIZE)
        assertEquals(0, buffer.read(0L, dest, 0, PAGE_SIZE))

        // Buffer should be functional after reset
        buffer.setTotalLength(PAGE_SIZE.toLong())
        buffer.writePage(0, 0x55)
        assertEquals(PAGE_SIZE.toLong(), buffer.frontier)

        val dest2 = ByteArray(PAGE_SIZE)
        val read = buffer.read(0L, dest2, 0, PAGE_SIZE)
        assertEquals(PAGE_SIZE, read)
        assertTrue(dest2.all { it == 0x55.toByte() })
    }

    // -------------------------------------------------------------------------
    // 6. readableContiguousBytesFrom returns correct count
    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // P1-7 regression pins for Phase 3 buffer fixes
    // -------------------------------------------------------------------------

    @Test
    fun `setBasePosition seeds first page lowWater for non-page-aligned base`() {
        val buffer = PagedFrontierBuffer()
        // basePosition lands halfway through page 5
        val base = 5L * PAGE_SIZE + (PAGE_SIZE / 2)
        buffer.setTotalLength(8L * PAGE_SIZE)
        buffer.setBasePosition(base)

        // Frontier starts at the basePosition
        assertEquals(base, buffer.frontier)

        // Write the second half of page 5 starting exactly at basePosition.
        val tail = ByteArray(PAGE_SIZE / 2) { 0x5A }
        buffer.onBytesWritten(base, tail, 0, tail.size)

        // The first page completes and the frontier snaps to the next absolute boundary.
        val expectedNextBoundary = 6L * PAGE_SIZE
        assertEquals(expectedNextBoundary, buffer.frontier)
    }

    @Test
    fun `advanceFrontier snaps to absolute page boundary not additive pageSize`() {
        val buffer = PagedFrontierBuffer()
        val base = 5L * PAGE_SIZE + 1024L  // non-zero, non-page-aligned
        buffer.setTotalLength(8L * PAGE_SIZE)
        buffer.setBasePosition(base)

        val tailLength = PAGE_SIZE - 1024
        val tail = ByteArray(tailLength) { 0x33 }
        buffer.onBytesWritten(base, tail, 0, tailLength)

        val absoluteNextBoundary = ((base / PAGE_SIZE) + 1L) * PAGE_SIZE
        assertEquals(absoluteNextBoundary, buffer.frontier)
        // Sanity: this MUST NOT be base + pageSize (the additive bug).
        org.junit.Assert.assertNotEquals(base + PAGE_SIZE, buffer.frontier)
    }

    @Test
    fun `readableContiguousBytesFrom reflects frontier relative to position`() {
        val buffer = PagedFrontierBuffer()
        buffer.setTotalLength(2L * PAGE_SIZE)

        buffer.writePage(0)
        buffer.writePage(1)

        assertEquals(2L * PAGE_SIZE, buffer.readableContiguousBytesFrom(0L))
        assertEquals(PAGE_SIZE.toLong(), buffer.readableContiguousBytesFrom(PAGE_SIZE.toLong()))
        assertEquals(0L, buffer.readableContiguousBytesFrom(2L * PAGE_SIZE))
    }

    @Test(timeout = 5_000L)
    fun `trace emits frontier advance histograms and no progress band`() {
        val sink = StringWriter()
        PlaybackTracer.installWriterFactory { header ->
            SessionWriter(
                header = header,
                baseFile = null,
                capacity = 4096,
                testSink = sink,
                rotationBytes = Long.MAX_VALUE,
                parkNanos = 1_000_000L,
                overflowReportIntervalNanos = Long.MAX_VALUE
            )
        }
        PlaybackTracer.enabled = true
        val sid = PlaybackTracer.beginSession(fakeHeader())

        val buffer = PagedFrontierBuffer()
        buffer.setTotalLength(PAGE_SIZE.toLong())
        buffer.writePage(0, 0x44)
        val dest = ByteArray(16)
        assertEquals(16, buffer.read(0L, dest, 0, dest.size))
        buffer.setAtomicLongField("histogramLastDrainNanos", -2_000_000_000L)
        buffer.setAtomicLongField("lastNoProgressEmitNanos", -2_000_000_000L)
        buffer.setAtomicLongField("lastFrontierAdvanceNanos", -2_000_000_000L)
        buffer.setAtomicLongField("lastObservedFrontier", buffer.frontier)
        assertEquals(16, buffer.read(0L, dest, 0, dest.size))
        PlaybackTracer.endSession(sid)

        val out = sink.toString()
        assertTrue(out.contains("\"ev\":\"frontier_advance\""))
        assertTrue(out.contains("\"ev\":\"store_lock_wait_ms\""))
        assertTrue(out.contains("\"ev\":\"store_write_ms\""))
        assertTrue(out.contains("\"ev\":\"store_read_ms\""))
        assertTrue(out.contains("\"ev\":\"frontier_no_progress_band\""))
    }

    private fun PagedFrontierBuffer.setAtomicLongField(name: String, value: Long) {
        val field = PagedFrontierBuffer::class.java.getDeclaredField(name)
        field.isAccessible = true
        (field.get(this) as AtomicLong).set(value)
    }

    private fun fakeHeader(sid: String = UUID.randomUUID().toString()): SessionHeader {
        return SessionHeader(
            sessionId = sid,
            startedAtNanos = 1L,
            assetKeyHash = "deadbeef0000",
            serviceKey = "real-debrid",
            provider = "addon-x",
            benchmarkResultId = null,
            benchmarkSource = null,
            envelopePresent = false,
            runtimeHintsPresent = false,
            specializationState = "baseline",
            hintServiceKey = null,
            hintHostScope = null,
            hintTransportClass = null,
            hintAgeMs = null,
            hintFreshnessBand = null,
            specializationMismatchReason = null,
            observedHostScope = null,
            observedTransportClass = null,
            branch = "prds",
            cacheActive = false,
            warmAheadFactory = null,
            factoryArgs = FactoryArgs(8L * 1024 * 1024, 4, 32L * 1024 * 1024, 4L * 1024 * 1024),
            initialPolicy = PolicySnapshot(2, 16, 4L * 1024 * 1024, 16L * 1024 * 1024, "fallback"),
            clientIdentity = ClientIdentitySnapshot(
                playbackClientHash = "abc123",
                dispatcherMaxRequests = 64,
                dispatcherMaxRequestsPerHost = 12,
                dispatcherQueuedCalls = 0,
                dispatcherRunningCalls = 0,
                connectionPoolIdleCount = 0,
                connectionPoolTotalCount = 0,
                callTimeoutMs = 0L,
                readTimeoutMs = 30_000L,
                writeTimeoutMs = 30_000L,
                connectTimeoutMs = 10_000L
            ),
            device = DeviceProvenance(
                deviceModel = "TEST",
                deviceManufacturer = "TEST",
                androidRelease = "14",
                androidSdkInt = 34,
                appVersionName = "test",
                appVersionCode = 1L,
                gitSha = null,
                memoryClass = 256,
                largeMemoryClass = 512,
                isLowRamDevice = false,
                networkType = "wifi",
                networkTransportHash = null
            )
        )
    }
}
