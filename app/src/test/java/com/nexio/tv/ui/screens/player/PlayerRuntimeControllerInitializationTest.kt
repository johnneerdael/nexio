package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.media3.exoplayer.ExoPlayer
import com.nexio.tv.data.repository.benchmark.CapabilityEnvelope
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSummary
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTerminationReason
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkProfileMetadata
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkProfileResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkSessionSummary
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkStatus
import com.google.gson.JsonParser
import com.nexio.tv.data.local.AddonSubtitleStartupMode
import com.nexio.tv.data.local.AudioLanguageOption
import com.nexio.tv.data.local.FrameRateMatchingMode
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.SubtitleStyleSettings
import com.nexio.tv.instrumentation.PlaybackTracer
import com.nexio.tv.instrumentation.SessionHeaderKeys
import com.nexio.tv.instrumentation.SessionWriter
import com.nexio.tv.domain.model.Stream
import io.mockk.every
import io.mockk.mockk
import java.io.StringWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the fail-fast shape-drift guards wired in Phase 5.
 *
 * Guards live ONLY in constructors / one-shot init sites — never on DataSource.open/read paths.
 * These tests exercise the [TransportPolicyController] constructor guard directly, mirroring
 * what [PlayerRuntimeControllerInitialization] does when building the controller for RD/PM.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRuntimeControllerInitializationTest {

    // ── RD ─────────────────────────────────────────────────────────────────────

    @Test
    fun `RD session builds TransportPolicyController with RD locked shape`() {
        val rdEnvelope = CapabilityEnvelope.LOCKED_REAL_DEBRID
        // Should not throw — shape matches
        val controller = TransportPolicyController(rdEnvelope, provider = "real_debrid")
        // Verify it uses the RD shape (urgent=1)
        assertEquals(TransportState.STARTUP, controller.state)
        val policy = controller.currentPolicy
        assertEquals(rdEnvelope.maxSafeUrgentWorkers, policy.urgentWorkers)
    }

    @Test
    fun `RD session with measured envelope that preserves locked shape does not throw`() {
        // Simulates what toCapabilityEnvelope returns: locked shape + measured throughput fields
        val measured = CapabilityEnvelope.LOCKED_REAL_DEBRID.copy(
            sustainedThroughputMbps = 125.0,
            stabilityPenalty = 0.1,
            measuredAtMs = 1_700_000_000_000L
        )
        // Shape fields are identical to LOCKED_REAL_DEBRID — must not throw
        val controller = TransportPolicyController(measured, provider = "real_debrid")
        assertEquals(TransportState.STARTUP, controller.state)
    }

    @Test
    fun `RD path never falls back to CapabilityEnvelope DEFAULT`() {
        val rdEnvelope = CapabilityEnvelope.LOCKED_REAL_DEBRID
        val controller = TransportPolicyController(rdEnvelope, provider = "real_debrid")
        // DEFAULT has maxSafeUrgentWorkers=2; LOCKED_REAL_DEBRID has 1 — they differ
        val policy = controller.currentPolicy
        // If DEFAULT had been used the urgent workers would be 2, not 1
        assertEquals(1, policy.urgentWorkers)
    }

    // ── PM ─────────────────────────────────────────────────────────────────────

    @Test
    fun `PM session builds TransportPolicyController with PM locked shape`() {
        val pmEnvelope = CapabilityEnvelope.LOCKED_PREMIUMIZE
        val controller = TransportPolicyController(pmEnvelope, provider = "premiumize")
        assertEquals(TransportState.STARTUP, controller.state)
        val policy = controller.currentPolicy
        assertEquals(pmEnvelope.maxSafeUrgentWorkers, policy.urgentWorkers)
    }

    @Test
    fun `PM session with measured envelope that preserves locked shape does not throw`() {
        val measured = CapabilityEnvelope.LOCKED_PREMIUMIZE.copy(
            sustainedThroughputMbps = 200.0,
            stabilityPenalty = 0.05,
            measuredAtMs = 1_700_000_000_000L
        )
        val controller = TransportPolicyController(measured, provider = "premiumize")
        assertEquals(TransportState.STARTUP, controller.state)
    }

    // ── Shape drift → throw ────────────────────────────────────────────────────

    @Test
    fun `divergent envelope for RD throws IllegalStateException with shape drift message`() {
        val divergent = CapabilityEnvelope.LOCKED_REAL_DEBRID.copy(
            maxSafeUrgentChunkBytes = 8L * 1024L * 1024L  // wrong — RD locked is 32 MiB
        )
        try {
            TransportPolicyController(divergent, provider = "real_debrid")
            fail("Expected IllegalStateException for shape drift")
        } catch (e: IllegalStateException) {
            assert(e.message?.contains("shape drift") == true) {
                "Expected 'shape drift' in message but got: ${e.message}"
            }
            assert(e.message?.contains("real_debrid") == true) {
                "Expected provider key in message but got: ${e.message}"
            }
        }
    }

    @Test
    fun `divergent envelope for PM throws IllegalStateException with shape drift message`() {
        val divergent = CapabilityEnvelope.LOCKED_PREMIUMIZE.copy(
            maxSafeUrgentWorkers = 4  // wrong — PM locked is 2
        )
        try {
            TransportPolicyController(divergent, provider = "premiumize")
            fail("Expected IllegalStateException for shape drift")
        } catch (e: IllegalStateException) {
            assert(e.message?.contains("shape drift") == true) {
                "Expected 'shape drift' in message but got: ${e.message}"
            }
            assert(e.message?.contains("premiumize") == true) {
                "Expected provider key in message but got: ${e.message}"
            }
        }
    }

    // ── Non-locked providers (TorBox, EasyDebrid) ──────────────────────────────

    @Test
    fun `TorBox path is unaffected — no guard fires for non-locked provider`() {
        // torbox has no locked shape, so any envelope is accepted
        val anyEnvelope = CapabilityEnvelope(
            maxSafeUrgentWorkers = 5,
            maxSafePrefetchWorkers = 3,
            maxSafeUrgentChunkBytes = 4L * 1024L * 1024L,
            maxSafePrefetchChunkBytes = 8L * 1024L * 1024L,
            sustainedThroughputMbps = 80.0,
            measuredAtMs = 1000L
        )
        // Must not throw
        val controller = TransportPolicyController(anyEnvelope, provider = "torbox")
        assertEquals(TransportState.STARTUP, controller.state)
    }

    @Test
    fun `EasyDebrid path is unaffected — no guard fires for non-locked provider`() {
        val anyEnvelope = CapabilityEnvelope(
            maxSafeUrgentWorkers = 3,
            maxSafePrefetchWorkers = 2,
            maxSafeUrgentChunkBytes = 16L * 1024L * 1024L,
            maxSafePrefetchChunkBytes = 32L * 1024L * 1024L,
            sustainedThroughputMbps = 60.0,
            measuredAtMs = 2000L
        )
        val controller = TransportPolicyController(anyEnvelope, provider = "easy_debrid")
        assertEquals(TransportState.STARTUP, controller.state)
    }

    @Test
    fun `null provider path is unaffected — no guard fires when provider is null`() {
        val controller = TransportPolicyController()
        assertEquals(TransportState.STARTUP, controller.state)
    }

    @Test
    fun `initializePlayer threads config benchmark provenance into trace header`() {
        val controllerScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val controller = buildController(
                serviceKey = "RD",
                scope = controllerScope,
                configResults = mapOf(
                    DebridBenchmarkProvider.REAL_DEBRID to configResult(
                        provider = DebridBenchmarkProvider.REAL_DEBRID,
                        measuredAtMs = 1_700_000_000_000L
                    )
                )
            )

            controller.initializePlayer(url = "https://example.com/video.mkv", headers = emptyMap())

            assertEquals("RD", controller.mediaSourceFactory.playbackTraceServiceKey)
            assertEquals("real_debrid", controller.mediaSourceFactory.playbackTraceProviderStorageKey)
            assertEquals(
                "config_benchmark",
                controller.mediaSourceFactory.playbackTraceBenchmarkSource
            )
            assertEquals(
                "real_debrid:1700000000000",
                controller.mediaSourceFactory.playbackTraceBenchmarkResultId
            )
            assertNotNull(controller.mediaSourceFactory.capabilityEnvelope)

            assertTraceHeaderFromFactory(
                controller = controller,
                url = "https://example.com/video.mkv",
                cacheActive = false,
                expectedServiceKey = "RD",
                expectedProvider = "real_debrid",
                expectedBenchmarkSource = "config_benchmark",
                expectedBenchmarkResultId = "real_debrid:1700000000000"
            )
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun `initializePlayer threads fallback benchmark provenance into trace header`() {
        val controllerScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val controller = buildController(
                serviceKey = "RD",
                scope = controllerScope,
                fallbackResults = mapOf(
                    DebridBenchmarkProvider.REAL_DEBRID to fallbackResult(
                        provider = DebridBenchmarkProvider.REAL_DEBRID,
                        measuredAtMs = 1_700_000_000_001L
                    )
                )
            )

            controller.initializePlayer(url = "https://example.com/video.mkv", headers = emptyMap())

            assertEquals("RD", controller.mediaSourceFactory.playbackTraceServiceKey)
            assertEquals("real_debrid", controller.mediaSourceFactory.playbackTraceProviderStorageKey)
            assertEquals(
                "fallback_benchmark",
                controller.mediaSourceFactory.playbackTraceBenchmarkSource
            )
            assertEquals(
                "real_debrid:1700000000001",
                controller.mediaSourceFactory.playbackTraceBenchmarkResultId
            )
            assertNotNull(controller.mediaSourceFactory.capabilityEnvelope)

            assertTraceHeaderFromFactory(
                controller = controller,
                url = "https://example.com/video.mkv",
                cacheActive = false,
                expectedServiceKey = "RD",
                expectedProvider = "real_debrid",
                expectedBenchmarkSource = "fallback_benchmark",
                expectedBenchmarkResultId = "real_debrid:1700000000001"
            )
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun `initializePlayer threads locked default provenance into trace header`() {
        val controllerScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val controller = buildController(
                serviceKey = "PM",
                scope = controllerScope
            )

            controller.initializePlayer(url = "https://example.com/video.mkv", headers = emptyMap())

            assertEquals("PM", controller.mediaSourceFactory.playbackTraceServiceKey)
            assertEquals("premiumize", controller.mediaSourceFactory.playbackTraceProviderStorageKey)
            assertEquals(
                "locked_default",
                controller.mediaSourceFactory.playbackTraceBenchmarkSource
            )
            assertNull(controller.mediaSourceFactory.playbackTraceBenchmarkResultId)
            assertEquals(
                CapabilityEnvelope.LOCKED_PREMIUMIZE,
                controller.mediaSourceFactory.capabilityEnvelope
            )

            assertTraceHeaderFromFactory(
                controller = controller,
                url = "https://example.com/video.mkv",
                cacheActive = false,
                expectedServiceKey = "PM",
                expectedProvider = "premiumize",
                expectedBenchmarkSource = "locked_default",
                expectedBenchmarkResultId = null
            )
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun `initializePlayer leaves benchmark source null when no benchmark or locked default exists`() {
        val controllerScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val controller = buildController(
                serviceKey = "TB",
                scope = controllerScope
            )

            controller.initializePlayer(url = "https://example.com/video.mkv", headers = emptyMap())

            assertEquals("TB", controller.mediaSourceFactory.playbackTraceServiceKey)
            assertEquals("torbox", controller.mediaSourceFactory.playbackTraceProviderStorageKey)
            assertNull(controller.mediaSourceFactory.playbackTraceBenchmarkSource)
            assertNull(controller.mediaSourceFactory.playbackTraceBenchmarkResultId)
            assertNull(controller.mediaSourceFactory.capabilityEnvelope)

            assertTraceHeaderFromFactory(
                controller = controller,
                url = "https://example.com/video.mkv",
                cacheActive = false,
                expectedServiceKey = "TB",
                expectedProvider = "torbox",
                expectedBenchmarkSource = null,
                expectedBenchmarkResultId = null
            )
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun `switchToSourceStream refreshes stale playback trace provenance`() {
        val controllerScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val controller = buildController(
                serviceKey = "TB",
                scope = controllerScope,
                fallbackResults = mapOf(
                    DebridBenchmarkProvider.REAL_DEBRID to fallbackResult(
                        provider = DebridBenchmarkProvider.REAL_DEBRID,
                        measuredAtMs = 1_700_000_000_001L
                    )
                )
            )
            controller.mediaSourceFactory.playbackTraceServiceKey = "TB"
            controller.mediaSourceFactory.playbackTraceProviderStorageKey = "torbox"
            controller.mediaSourceFactory.playbackTraceBenchmarkSource = null
            controller.mediaSourceFactory.playbackTraceBenchmarkResultId = null
            controller._exoPlayer = mockk<ExoPlayer>(relaxed = true)

            controller.switchToSourceStream(
                Stream(
                    name = "RD stream",
                    title = null,
                    description = null,
                    url = "https://example.com/rd-video.mkv",
                    ytId = null,
                    infoHash = null,
                    fileIdx = null,
                    externalUrl = null,
                    behaviorHints = null,
                    addonName = "Addon",
                    addonLogo = null,
                    wrappedProviderId = "RD"
                )
            )

            assertEquals("RD", controller.mediaSourceFactory.playbackTraceServiceKey)
            assertEquals("real_debrid", controller.mediaSourceFactory.playbackTraceProviderStorageKey)
            assertEquals("fallback_benchmark", controller.mediaSourceFactory.playbackTraceBenchmarkSource)
            assertEquals("real_debrid:1700000000001", controller.mediaSourceFactory.playbackTraceBenchmarkResultId)
        } finally {
            controllerScope.cancel()
        }
    }

    @After
    fun tearDownPlaybackTracer() {
        PlaybackTracer.enabled = false
        PlaybackTracer.currentInternal()?.let { PlaybackTracer.endSession(it.sessionId) }
        PlaybackTracer.installWriterFactory(null)
    }

    private fun buildController(
        serviceKey: String?,
        scope: CoroutineScope,
        configResults: Map<DebridBenchmarkProvider, DebridConfigBenchmarkResult?> = emptyMap(),
        fallbackResults: Map<DebridBenchmarkProvider, DebridBenchmarkResult?> = emptyMap()
    ): PlayerRuntimeController {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        every { playerSettingsDataStore.playerSettings } returns flowOf(testPlayerSettings())

        val debridConfigBenchmarkStore = mockk<com.nexio.tv.data.local.DebridConfigBenchmarkStore>()
        every { debridConfigBenchmarkStore.latestResult(any()) } answers {
            flowOf(configResults[firstArg()])
        }

        val debridBenchmarkStore = mockk<com.nexio.tv.data.local.DebridBenchmarkStore>()
        every { debridBenchmarkStore.latestResult(any()) } answers {
            flowOf(fallbackResults[firstArg()])
        }

        return PlayerRuntimeController(
            context = context,
            watchProgressRepository = mockk(relaxed = true),
            metaRepository = mockk(relaxed = true),
            streamRepository = mockk(relaxed = true),
            addonRepository = mockk(relaxed = true),
            subtitleRepository = mockk(relaxed = true),
            trackingScrobbleService = mockk(relaxed = true),
            skipIntroRepository = mockk(relaxed = true),
            playerSettingsDataStore = playerSettingsDataStore,
            debugSettingsDataStore = mockk(relaxed = true),
            geminiSettingsDataStore = mockk(relaxed = true),
            theIntroDbSettingsDataStore = mockk(relaxed = true),
            streamLinkCacheDataStore = mockk(relaxed = true),
            layoutPreferenceDataStore = mockk(relaxed = true),
            geminiSubtitleTranslationService = mockk(relaxed = true),
            playbackIdleGateState = mockk(relaxed = true),
            transportValidationRuntimeCollector = mockk(relaxed = true),
            debridConfigBenchmarkStore = debridConfigBenchmarkStore,
            debridBenchmarkStore = debridBenchmarkStore,
            playbackOkHttpClient = okhttp3.OkHttpClient(),
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "streamUrl" to "https://example.com/video.mkv",
                    "title" to "Test title",
                    "serviceKey" to (serviceKey ?: "")
                )
            ),
            scope = scope
        )
    }

    private fun testPlayerSettings(): PlayerSettings {
        return PlayerSettings(
            useLibass = false,
            subtitleStyle = SubtitleStyleSettings(
                preferredLanguage = AudioLanguageOption.ORIGINAL,
                secondaryPreferredLanguage = null
            ),
            frameRateMatchingMode = FrameRateMatchingMode.OFF,
            resolutionMatchingEnabled = false,
            experimentalDv7ToDv81Enabled = false,
            experimentalDv5ToneMapToSdrEnabled = false,
            experimentalDv5HardwareToneMapToSdrEnabled = false,
            addonSubtitleStartupMode = AddonSubtitleStartupMode.FAST_STARTUP
        )
    }

    private fun configResult(
        provider: DebridBenchmarkProvider,
        measuredAtMs: Long
    ): DebridConfigBenchmarkResult {
        val profile = DebridConfigBenchmarkProfileResult(
            profile = DebridConfigBenchmarkProfileMetadata(
                parallelConnectionCount = 1,
                chunkSizeMb = 32
            ),
            status = DebridConfigBenchmarkStatus.SUCCESS,
            averageThroughputMbps = 123.4
        )
        val envelope = CapabilityEnvelope.lockedFor(provider.storageKey)?.copy(
            sustainedThroughputMbps = 123.4,
            measuredAtMs = measuredAtMs
        ) ?: CapabilityEnvelope.DEFAULT.copy(measuredAtMs = measuredAtMs)
        return DebridConfigBenchmarkResult(
            provider = provider,
            measuredAtMs = measuredAtMs,
            summary = DebridConfigBenchmarkSessionSummary(
                totalProfileCount = 1,
                successfulProfileCount = 1,
                failedProfileCount = 0,
                unsupportedProfileCount = 0,
                bestProfile = profile,
                capabilityEnvelope = envelope
            ),
            profiles = listOf(profile)
        )
    }

    private fun fallbackResult(
        provider: DebridBenchmarkProvider,
        measuredAtMs: Long
    ): DebridBenchmarkResult {
        val envelope = CapabilityEnvelope.lockedFor(provider.storageKey)?.copy(
            sustainedThroughputMbps = 234.5,
            measuredAtMs = measuredAtMs
        ) ?: CapabilityEnvelope.DEFAULT.copy(
            sustainedThroughputMbps = 234.5,
            measuredAtMs = measuredAtMs
        )
        return DebridBenchmarkResult(
            provider = provider,
            measuredAtMs = measuredAtMs,
            summary = DebridBenchmarkSummary(
                startupTimeMs = 90L,
                sustainedThroughputMbps = 234.5,
                transferredBytes = 10_000_000L,
                elapsedMs = 1_000L
            ),
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
            capabilityEnvelope = envelope
        )
    }

    private fun assertTraceHeaderFromFactory(
        controller: PlayerRuntimeController,
        url: String,
        cacheActive: Boolean,
        expectedServiceKey: String?,
        expectedProvider: String?,
        expectedBenchmarkSource: String?,
        expectedBenchmarkResultId: String?
    ) {
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
        try {
            controller.mediaSourceFactory.callOpenPlaybackTraceSession(
                url = url,
                branch = "prds",
                envelope = controller.mediaSourceFactory.capabilityEnvelope,
                cacheActive = cacheActive
            )
            val writer = PlaybackTracer.currentInternal()
            assertNotNull(writer)
            PlaybackTracer.endSession(writer!!.sessionId)

            val line = sink.toString().lineSequence().first { it.isNotBlank() }
            val obj = JsonParser.parseString(line).asJsonObject
            assertEquals(expectedServiceKey, obj.get(SessionHeaderKeys.SERVICE_KEY).takeIf { !it.isJsonNull }?.asString)
            assertEquals(expectedProvider, obj.get(SessionHeaderKeys.PROVIDER).takeIf { !it.isJsonNull }?.asString)
            assertEquals(expectedBenchmarkSource, obj.get(SessionHeaderKeys.BENCHMARK_SOURCE).takeIf { !it.isJsonNull }?.asString)
            assertEquals(expectedBenchmarkResultId, obj.get(SessionHeaderKeys.BENCHMARK_RESULT_ID).takeIf { !it.isJsonNull }?.asString)
        } finally {
            PlaybackTracer.enabled = false
            PlaybackTracer.installWriterFactory(null)
            PlaybackTracer.currentInternal()?.let { PlaybackTracer.endSession(it.sessionId) }
        }
    }

    private fun PlayerMediaSourceFactory.callOpenPlaybackTraceSession(
        url: String,
        branch: String,
        envelope: CapabilityEnvelope?,
        cacheActive: Boolean
    ) {
        val method = PlayerMediaSourceFactory::class.java.getDeclaredMethod(
            "openPlaybackTraceSession",
            String::class.java,
            String::class.java,
            CapabilityEnvelope::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(this, url, branch, envelope, cacheActive)
    }
}
