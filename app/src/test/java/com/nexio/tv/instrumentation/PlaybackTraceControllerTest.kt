package com.nexio.tv.instrumentation

import android.content.ContentValues
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackTraceControllerTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0

        mockkStatic(FileProvider::class)
        every {
            FileProvider.getUriForFile(any(), any(), any())
        } returns Uri.parse("content://tests/shared-export")
    }

    @After
    fun tearDown() {
        PlaybackTracer.enabled = false
        PlaybackTracer.currentInternal()?.let { PlaybackTracer.endSession(it.sessionId) }
        PlaybackTracer.installWriterFactory(null)
        unmockkStatic(Log::class)
        unmockkStatic(FileProvider::class)
    }

    @Test
    fun `copyAllToDestination writes zip containing every retained trace including rolled files`() = runTest {
        val appFilesDir = createTempDirectory("playback-trace-controller-files").toFile()
        val tracesDir = File(appFilesDir, "playback-traces").apply { mkdirs() }
        File(tracesDir, "session-a.jsonl").writeText("{\"type\":\"started\"}\n")
        File(tracesDir, "session-a-1.jsonl").writeText("{\"type\":\"rotated\"}\n")
        File(tracesDir, "session-b.jsonl").writeText("{\"type\":\"ended\"}\n")

        val destinationUri = Uri.parse("content://tests/playback-traces.zip")
        val destinationBytes = ByteArrayOutputStream()
        val contentResolver = mockk<ContentResolver>()
        every { contentResolver.openOutputStream(destinationUri) } returns destinationBytes

        val controller = buildController(
            filesDir = appFilesDir,
            cacheDir = createTempDirectory("playback-trace-controller-cache").toFile(),
            contentResolver = contentResolver,
        )

        val written = controller.copyAllToDestination(destinationUri)

        assertTrue(written > 0L)
        ZipInputStream(ByteArrayInputStream(destinationBytes.toByteArray())).use { zip ->
            val names = mutableListOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
            }
            assertEquals(
                setOf("session-a.jsonl", "session-a-1.jsonl", "session-b.jsonl"),
                names.toSet(),
            )
        }
    }

    @Test
    fun `copyAllToDestination returns zero when no traces exist`() = runTest {
        val destinationUri = Uri.parse("content://tests/playback-traces.zip")
        val contentResolver = mockk<ContentResolver>(relaxed = true)
        val controller = buildController(
            filesDir = createTempDirectory("playback-trace-controller-empty").toFile(),
            cacheDir = createTempDirectory("playback-trace-controller-empty-cache").toFile(),
            contentResolver = contentResolver,
        )

        val written = controller.copyAllToDestination(destinationUri)

        assertEquals(0L, written)
        verify(exactly = 0) { contentResolver.openOutputStream(any()) }
    }

    @Test
    fun `exportAll still returns ACTION_SEND zip intent`() = runTest {
        val appFilesDir = createTempDirectory("playback-trace-controller-share").toFile()
        File(appFilesDir, "playback-traces").apply {
            mkdirs()
            resolve("session-a.jsonl").writeText("{\"type\":\"started\"}\n")
        }

        val controller = buildController(
            filesDir = appFilesDir,
            cacheDir = createTempDirectory("playback-trace-controller-share-cache").toFile(),
            contentResolver = mockk(relaxed = true),
        )

        val intent = controller.exportAll()

        assertEquals(Intent.ACTION_SEND, intent?.action)
        assertEquals("application/zip", intent?.type)
    }

    @Test
    fun `copyAllToDownloads writes zip to downloads and returns adb path`() = runTest {
        val appFilesDir = createTempDirectory("playback-trace-controller-downloads").toFile()
        val tracesDir = File(appFilesDir, "playback-traces").apply { mkdirs() }
        File(tracesDir, "session-a.jsonl").writeText("{\"type\":\"started\"}\n")
        File(tracesDir, "session-a-1.jsonl").writeText("{\"type\":\"rotated\"}\n")

        val insertedUri = Uri.parse("content://tests/downloads/playback-traces.zip")
        val destinationBytes = ByteArrayOutputStream()
        val contentResolver = mockk<ContentResolver>()
        val valuesSlot = slot<ContentValues>()
        every {
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, capture(valuesSlot))
        } returns insertedUri
        every { contentResolver.openOutputStream(insertedUri) } returns destinationBytes

        val controller = buildController(
            filesDir = appFilesDir,
            cacheDir = createTempDirectory("playback-trace-controller-downloads-cache").toFile(),
            contentResolver = contentResolver,
        )

        val adbPath = controller.copyAllToDownloads(nowMs = 1_700_000_000_000L)

        assertEquals("/sdcard/Download/playback-traces-1700000000000.zip", adbPath)
        assertEquals("application/zip", valuesSlot.captured.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals(
            "playback-traces-1700000000000.zip",
            valuesSlot.captured.getAsString(MediaStore.MediaColumns.DISPLAY_NAME),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(
                Environment.DIRECTORY_DOWNLOADS,
                valuesSlot.captured.getAsString(MediaStore.MediaColumns.RELATIVE_PATH),
            )
        }
        verify(exactly = 1) {
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, any<ContentValues>())
        }
        verify(exactly = 1) { contentResolver.openOutputStream(insertedUri) }
        ZipInputStream(ByteArrayInputStream(destinationBytes.toByteArray())).use { zip ->
            val names = mutableListOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
            }
            assertEquals(
                setOf("session-a.jsonl", "session-a-1.jsonl"),
                names.toSet(),
            )
        }
    }

    @Test
    fun `copyAllToDownloads returns null when downloads output stream is unavailable`() = runTest {
        val appFilesDir = createTempDirectory("playback-trace-controller-downloads-null").toFile()
        val tracesDir = File(appFilesDir, "playback-traces").apply { mkdirs() }
        File(tracesDir, "session-a.jsonl").writeText("{\"type\":\"started\"}\n")

        val insertedUri = Uri.parse("content://tests/downloads/playback-traces.zip")
        val contentResolver = mockk<ContentResolver>()
        every {
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, any<ContentValues>())
        } returns insertedUri
        every { contentResolver.openOutputStream(insertedUri) } returns null
        every { contentResolver.delete(insertedUri, null, null) } returns 1

        val controller = buildController(
            filesDir = appFilesDir,
            cacheDir = createTempDirectory("playback-trace-controller-downloads-null-cache").toFile(),
            contentResolver = contentResolver,
        )

        val adbPath = controller.copyAllToDownloads()

        assertEquals(null, adbPath)
        verify(exactly = 1) {
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, any<ContentValues>())
        }
        verify(exactly = 1) { contentResolver.openOutputStream(insertedUri) }
        verify(exactly = 1) { contentResolver.delete(insertedUri, null, null) }
    }

    @Test
    fun `copyAllToDownloads returns null and cleans up when copy fails`() = runTest {
        val appFilesDir = createTempDirectory("playback-trace-controller-downloads-copy-fail").toFile()
        val tracesDir = File(appFilesDir, "playback-traces").apply { mkdirs() }
        File(tracesDir, "session-a.jsonl").writeText("{\"type\":\"started\"}\n")

        val insertedUri = Uri.parse("content://tests/downloads/playback-traces.zip")
        val contentResolver = mockk<ContentResolver>()
        every {
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, any<ContentValues>())
        } returns insertedUri
        every { contentResolver.openOutputStream(insertedUri) } returns object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("disk full")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("disk full")
            }
        }
        every { contentResolver.delete(insertedUri, null, null) } returns 1

        val controller = buildController(
            filesDir = appFilesDir,
            cacheDir = createTempDirectory("playback-trace-controller-downloads-copy-fail-cache").toFile(),
            contentResolver = contentResolver,
        )

        val adbPath = controller.copyAllToDownloads()

        assertEquals(null, adbPath)
        verify(exactly = 1) {
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, any<ContentValues>())
        }
        verify(exactly = 1) { contentResolver.openOutputStream(insertedUri) }
        verify(exactly = 1) { contentResolver.delete(insertedUri, null, null) }
    }

    @Test
    fun `copyAllToDownloads skips insert when no traces exist`() = runTest {
        val contentResolver = mockk<ContentResolver>(relaxed = true)
        val controller = buildController(
            filesDir = createTempDirectory("playback-trace-controller-downloads-empty").toFile(),
            cacheDir = createTempDirectory("playback-trace-controller-downloads-empty-cache").toFile(),
            contentResolver = contentResolver,
        )

        val adbPath = controller.copyAllToDownloads()

        assertEquals(null, adbPath)
        verify(exactly = 0) {
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, any<ContentValues>())
        }
        verify(exactly = 0) { contentResolver.openOutputStream(any()) }
    }

    @Test
    fun `clearAll ends active session before deleting retained traces`() = runTest {
        val appFilesDir = createTempDirectory("trace-clear-files").toFile()
        val cacheDir = createTempDirectory("trace-clear-cache").toFile()
        val tracesDir = File(appFilesDir, "playback-traces").apply { mkdirs() }
        File(cacheDir, "playback-trace-exports").apply {
            mkdirs()
            resolve("stale.zip").writeText("zip")
        }
        File(cacheDir, "playback-trace-export-snapshots").apply {
            mkdirs()
            resolve("snapshot.jsonl").writeText("snapshot")
        }
        val contentResolver = mockk<ContentResolver>(relaxed = true)
        val controller = buildController(appFilesDir, cacheDir, contentResolver)
        controller.installFilesDirOnce()
        PlaybackTracer.enabled = true
        PlaybackTracer.beginSession(SessionHeaderFixture.build(sessionId = "active-rd"))
        PlaybackTracer.emit(EventFamily.RANGE, "range_start") { putLong("chunkIndex", 0L) }
        File(tracesDir, "stale-pm.jsonl").writeText("{\"sid\":\"stale-pm\"}\n")

        val deleted = controller.clearAll()

        assertTrue(deleted >= 1)
        assertTrue(controller.listTraces().isEmpty())
        assertEquals(null, PlaybackTracer.currentInternal())
        assertFalse(File(cacheDir, "playback-trace-exports").exists())
        assertFalse(File(cacheDir, "playback-trace-export-snapshots").exists())
    }

    @Test
    fun `copyLatestSessionToDownloads exports only the newest session family`() = runTest {
        val appFilesDir = createTempDirectory("trace-latest-files").toFile()
        val cacheDir = createTempDirectory("trace-latest-cache").toFile()
        val tracesDir = File(appFilesDir, "playback-traces").apply { mkdirs() }
        val pmOld = File(tracesDir, "pm-old.jsonl").apply { writeText("{\"sid\":\"pm-old\"}\n") }
        val rdLive = File(tracesDir, "rd-live.jsonl").apply { writeText("{\"sid\":\"rd-live\"}\n") }
        val rdLiveRotated = File(tracesDir, "rd-live-1.jsonl").apply { writeText("{\"sid\":\"rd-live\"}\n") }
        pmOld.setLastModified(1L)
        rdLive.setLastModified(2L)
        rdLiveRotated.setLastModified(3L)

        val insertedUri = Uri.parse("content://tests/downloads/latest.zip")
        val destinationBytes = ByteArrayOutputStream()
        val contentResolver = mockk<ContentResolver>()
        every { contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, any<ContentValues>()) } returns insertedUri
        every { contentResolver.openOutputStream(insertedUri) } returns destinationBytes

        val controller = buildController(appFilesDir, cacheDir, contentResolver)
        val adbPath = controller.copyLatestSessionToDownloads(nowMs = 42L)

        assertEquals("/sdcard/Download/playback-trace-latest-42.zip", adbPath)
        ZipInputStream(ByteArrayInputStream(destinationBytes.toByteArray())).use { zip ->
            val names = mutableSetOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
            }
            assertEquals(setOf("rd-live.jsonl", "rd-live-1.jsonl"), names)
        }
    }

    @Test
    fun `copyAllToDestination snapshots the active trace file before zipping`() = runTest {
        val appFilesDir = createTempDirectory("playback-trace-controller-live-files").toFile()
        val cacheDir = createTempDirectory("playback-trace-controller-live-cache").toFile()
        val destinationUri = Uri.parse("content://tests/live-playback-traces.zip")
        val destinationBytes = ByteArrayOutputStream()
        val contentResolver = mockk<ContentResolver>()
        every { contentResolver.openOutputStream(destinationUri) } returns destinationBytes

        val controller = buildController(
            filesDir = appFilesDir,
            cacheDir = cacheDir,
            contentResolver = contentResolver,
        )
        controller.installFilesDirOnce()
        PlaybackTracer.enabled = true
        val sessionId = PlaybackTracer.beginSession(fakeHeader("live-session"))
        PlaybackTracer.emit(EventFamily.RANGE, "range_http_body") {
            putInt("bytesRead", 8192)
            putInt("totalRead", 8192)
        }
        Thread.sleep(100L)

        try {
            val written = controller.copyAllToDestination(destinationUri)
            assertTrue(written > 0L)
        } finally {
            PlaybackTracer.endSession(sessionId)
        }

        ZipInputStream(ByteArrayInputStream(destinationBytes.toByteArray())).use { zip ->
            val entry = zip.nextEntry
            assertEquals("live-session.jsonl", entry?.name)
            val payload = zip.readBytes().decodeToString()
            payload.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { JSONObject(it) }
        }
    }

    @Test
    fun `copyLatestSessionToDownloads snapshots active rotated traces without partial lines`() = runTest {
        val appFilesDir = createTempDirectory("playback-trace-controller-live-latest-files").toFile()
        val cacheDir = createTempDirectory("playback-trace-controller-live-latest-cache").toFile()
        val insertedUri = Uri.parse("content://tests/downloads/live-latest.zip")
        val destinationBytes = ByteArrayOutputStream()
        val contentResolver = mockk<ContentResolver>()
        every { contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, any<ContentValues>()) } returns insertedUri
        every { contentResolver.openOutputStream(insertedUri) } returns destinationBytes

        val controller = buildController(appFilesDir, cacheDir, contentResolver)
        controller.installFilesDirOnce()
        PlaybackTracer.enabled = true
        val sessionId = PlaybackTracer.beginSession(fakeHeader("live-latest"))
        repeat(1500) { index ->
            PlaybackTracer.emit(EventFamily.RANGE, "range_http_body_progress") {
                putLong("chunkIndex", 1L)
                putString("lane", "urgent")
                putInt("bytesRead", 8192)
                putInt("totalRead", index + 1)
            }
        }
        Thread.sleep(150L)

        try {
            val adbPath = controller.copyLatestSessionToDownloads(nowMs = 99L)
            assertEquals("/sdcard/Download/playback-trace-latest-99.zip", adbPath)
        } finally {
            PlaybackTracer.endSession(sessionId)
        }

        ZipInputStream(ByteArrayInputStream(destinationBytes.toByteArray())).use { zip ->
            val entries = mutableListOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name
                zip.readBytes().decodeToString()
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { JSONObject(it) }
            }
            assertTrue(entries.all { it.startsWith("live-latest") })
        }
    }

    private fun buildController(
        filesDir: File,
        cacheDir: File,
        contentResolver: ContentResolver,
    ): PlaybackTraceController {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(baseContext) {
            override fun getFilesDir(): File = filesDir
            override fun getCacheDir(): File = cacheDir
            override fun getContentResolver(): ContentResolver = contentResolver
            override fun getPackageName(): String = "com.nexio.tv"
        }
        val toggle = mockk<PlaybackTraceToggle>(relaxed = true)
        every { toggle.enabledFlow } returns emptyFlow()
        return PlaybackTraceController(
            appContext = context,
            toggle = toggle,
        )
    }

    private fun fakeHeader(sessionId: String): SessionHeader {
        return SessionHeader(
            sessionId = sessionId,
            startedAtNanos = 1L,
            assetKeyHash = "deadbeef0000",
            serviceKey = "RD",
            provider = "real_debrid",
            benchmarkResultId = null,
            benchmarkSource = "locked_default",
            envelopePresent = true,
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
            factoryArgs = FactoryArgs(
                activeChunkBytes = 32L * 1024L * 1024L,
                parallelConnections = 1,
                keepBehindBytes = 64L * 1024L * 1024L,
                bootstrapBytes = 32L * 1024L * 1024L,
            ),
            initialPolicy = PolicySnapshot(
                urgentWorkers = 1,
                prefetchWorkers = 0,
                urgentChunkBytes = 32L * 1024L * 1024L,
                prefetchChunkBytes = 0L,
                source = "locked_default",
            ),
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
                connectTimeoutMs = 10_000L,
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
                largeMemoryClass = 256,
                isLowRamDevice = false,
                networkType = "wifi",
                networkTransportHash = null,
            ),
        )
    }
}
