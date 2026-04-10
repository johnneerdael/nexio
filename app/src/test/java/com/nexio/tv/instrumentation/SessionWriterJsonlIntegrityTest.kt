package com.nexio.tv.instrumentation

import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionWriterJsonlIntegrityTest {

    @After
    fun tearDown() {
        PlaybackTracer.enabled = false
        PlaybackTracer.resetCrashIsolationForTest()
    }

    @Test(timeout = 20_000L)
    fun `concurrent prds range records always produce parseable jsonl lines`() {
        val sink = StringWriter()
        val writer = SessionWriter(
            header = SessionHeaderFixture.build(sessionId = "prds-jsonl"),
            baseFile = null,
            capacity = 8192,
            testSink = sink,
            rotationBytes = Long.MAX_VALUE,
            parkNanos = 1_000_000L,
            overflowReportIntervalNanos = Long.MAX_VALUE,
        )
        val workerCount = 4
        val iterationsPerWorker = 200
        val startLatch = CountDownLatch(1)
        val threads = mutableListOf<Thread>()

        repeat(workerCount) { index ->
            val thread = Thread({
                startLatch.await(5, TimeUnit.SECONDS)
                repeat(iterationsPerWorker) { iteration ->
                    writer.enqueue(EventFamily.RANGE, "range_http_body_progress") {
                        putLong("chunkIndex", index.toLong())
                        putString("lane", "urgent")
                        putInt("bytesRead", 1024)
                        putInt("totalRead", iteration + 1)
                    }
                }
            }, "prds-jsonl-worker-$index")
            threads += thread
            thread.start()
        }

        startLatch.countDown()
        threads.forEach { it.join(5_000L) }
        assertTrue(threads.none { it.isAlive })
        writer.shutdown()

        sink.toString()
            .lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                JSONObject(line)
            }
    }

    @Test
    fun `crash isolation profile disables atrace markers`() {
        PlaybackTracer.applyCrashIsolationProfile()
        assertTrue(!PlaybackTracer.atraceMarkersEnabled)
    }
}
