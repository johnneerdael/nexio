package com.nexio.tv.debug.passthrough

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransportValidationRuntimeCollectorTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 10_000L
    }

    @After
    fun tearDown() {
        unmockkStatic(SystemClock::class)
        Dispatchers.resetMain()
    }

    @Test
    fun `position stall ignores late end of run noise`() {
        val thresholds =
            TransportValidationRuntimeDefaults.thresholds.copy(
                positionStallWindowMs = 4_000L,
                minimumPositionAdvanceMs = 250L,
                lateNoiseExclusionWindowMs = 2_000L,
            )
        val samples =
            listOf(
                positionSample(0L, 0L),
                positionSample(4_000L, 4_000L),
                positionSample(8_000L, 8_000L),
                positionSample(12_000L, 12_000L),
                positionSample(16_000L, 16_000L),
                // Final 2s are teardown noise and should not trigger a stall.
                positionSample(18_500L, 16_000L),
                positionSample(20_000L, 16_000L),
            )

        assertFalse(
            invokeIsPositionStalled(
                samples = samples,
                thresholds = thresholds,
                nowElapsedRealtimeMs = 20_000L,
            )
        )
    }

    @Test
    fun `position stall still detects real steady state lack of progress`() {
        val thresholds =
            TransportValidationRuntimeDefaults.thresholds.copy(
                positionStallWindowMs = 4_000L,
                minimumPositionAdvanceMs = 250L,
                lateNoiseExclusionWindowMs = 2_000L,
            )
        val samples =
            listOf(
                positionSample(0L, 0L),
                positionSample(4_000L, 100L),
                positionSample(8_000L, 150L),
                positionSample(12_000L, 200L),
                positionSample(16_000L, 225L),
                positionSample(18_000L, 240L),
            )

        assertTrue(
            invokeIsPositionStalled(
                samples = samples,
                thresholds = thresholds,
                nowElapsedRealtimeMs = 21_000L,
            )
        )
    }

    @Test
    fun `route health exports reopen candidate diagnostics`() {
        val routeSamples =
            listOf(
                TransportValidationTimedRouteSnapshot(
                    elapsedRealtimeMs = 1_000L,
                    snapshot =
                        TransportValidationRouteSnapshot(
                            deviceName = "",
                            encoding = "IEC61937",
                            sampleRate = 192000,
                            channelMask = "7.1",
                            directPlaybackSupported = true,
                            audioTrackState = 1,
                        ),
                ),
            )
        val routeHealth =
            invokeToRouteHealthSummary(
                samples = routeSamples,
                stableStartAtElapsedRealtimeMs = 1_500L,
                routeScoringStartAtElapsedRealtimeMs = 2_500L,
                analyticsEvents =
                    listOf(
                        TransportValidationRuntimeAnalyticsEvent(
                            elapsedRealtimeMs = 3_000L,
                            type = "route_reopen_candidate",
                            value = 1L,
                            detail =
                                "reason=audio_track_state_changed " +
                                    "oldRouteTuple=IEC61937|192000|7.1 " +
                                    "newRouteTuple=IEC61937|192000|7.1 " +
                                    "stableStartAlreadyReached=true " +
                                    "externalRouteChangeDetected=false " +
                                    "outputStillConfigured=true " +
                                    "outputStillStarted=true",
                        )
                    ),
            )

        assertEquals(1, routeHealth.routeReopenCandidates.size)
        val candidate = routeHealth.routeReopenCandidates.single()
        assertEquals(3_000L, candidate.elapsedRealtimeMs)
        assertEquals("audio_track_state_changed", candidate.reason)
        assertEquals("IEC61937|192000|7.1", candidate.oldRouteTuple)
        assertEquals("IEC61937|192000|7.1", candidate.newRouteTuple)
        assertTrue(candidate.stableStartAlreadyReached)
        assertFalse(candidate.externalRouteChangeDetected)
        assertTrue(candidate.outputStillConfigured)
        assertTrue(candidate.outputStillStarted)
    }

    @Test
    fun `route health keeps zero counters explicit`() {
        val routeSamples =
            listOf(
                TransportValidationTimedRouteSnapshot(
                    elapsedRealtimeMs = 1_000L,
                    snapshot =
                        TransportValidationRouteSnapshot(
                            deviceName = "",
                            encoding = "IEC61937",
                            sampleRate = 192000,
                            channelMask = "7.1",
                            directPlaybackSupported = true,
                            audioTrackState = 1,
                        ),
                ),
                TransportValidationTimedRouteSnapshot(
                    elapsedRealtimeMs = 2_000L,
                    snapshot =
                        TransportValidationRouteSnapshot(
                            deviceName = "",
                            encoding = "IEC61937",
                            sampleRate = 192000,
                            channelMask = "7.1",
                            directPlaybackSupported = true,
                            audioTrackState = 1,
                        ),
                ),
            )
        val routeHealth =
            invokeToRouteHealthSummary(
                samples = routeSamples,
                stableStartAtElapsedRealtimeMs = 1_500L,
                routeScoringStartAtElapsedRealtimeMs = 2_500L,
                analyticsEvents = emptyList(),
            )

        assertEquals(0, routeHealth.routeChangeCount)
        assertEquals(0, routeHealth.routeChangeCountAfterStableStart)
        assertEquals(0, routeHealth.routeTupleChangeCountAfterStableStart)
        assertEquals(0, routeHealth.routeReopenCountAfterStart)
        assertTrue(routeHealth.routeReopenCandidates.isEmpty())
    }

    @Test
    fun `snapshot keeps last playback stats after listener is cleared`() {
        val collector = TransportValidationRuntimeCollector()
        collector.beginSession(
            sample = sample(),
            settings = TransportValidationSettings(runtimeValidationEnabled = true),
        )
        val session = activeSession(collector)
        val playbackStats = PlaybackStats.EMPTY
        val playbackStatsListener = mockk<PlaybackStatsListener>()
        every { playbackStatsListener.getCombinedPlaybackStats() } returns playbackStats
        setPrivateField(session, "playbackStatsListener", playbackStatsListener)

        val initialSnapshot = collector.snapshot()
        val expectedStats =
            TransportValidationRuntimeVerdictCalculator.playbackStatsSummary(playbackStats)
        assertNotNull(initialSnapshot?.playbackStats)
        assertEquals(expectedStats, initialSnapshot?.playbackStats)

        setPrivateField(session, "playbackStatsListener", null)

        val retainedSnapshot = collector.snapshot()
        assertNotNull(retainedSnapshot?.playbackStats)
        assertEquals(expectedStats, retainedSnapshot?.playbackStats)
    }

    @Test
    fun `transport specialization events are exported in analytics snapshot`() {
        val collector = TransportValidationRuntimeCollector()
        collector.beginSession(
            sample = sample(),
            settings = TransportValidationSettings(runtimeValidationEnabled = true),
        )

        collector.onTransportSpecializationEvent(
            type = "transport_specialization_confirmed",
            detail = "streamServiceKey=RD hintServiceKey=RD reason=confirmed"
        )
        collector.onTransportSpecializationEvent(
            type = "transport_specialization_revoked",
            detail = "streamServiceKey=RD hintServiceKey=RD reason=confirmation_lost"
        )

        val snapshot = collector.snapshot()

        assertNotNull(snapshot)
        assertEquals(
            listOf("transport_specialization_confirmed", "transport_specialization_revoked"),
            snapshot?.analyticsEvents?.takeLast(2)?.map { it.type }
        )
        assertTrue(snapshot?.analyticsEvents?.last()?.detail?.contains("reason=confirmation_lost") == true)
    }

    private fun positionSample(
        elapsedRealtimeMs: Long,
        positionMs: Long,
        isPlaying: Boolean = true,
        playbackState: Int = Player.STATE_READY,
    ) = PositionSample(
        elapsedRealtimeMs = elapsedRealtimeMs,
        positionMs = positionMs,
        isPlaying = isPlaying,
        playbackState = playbackState,
    )

    private fun sample(): TransportValidationSample = TransportValidationSample(
        id = "truehd",
        displayName = "Dolby TrueHD",
        codecFamily = TransportValidationCodecFamily.TRUEHD,
        sourceAssetPath = "truehd.mkv",
        elementaryAssetPath = "truehd.thd",
        referenceAssetPath = "truehd.spdif",
        expectedPc = 0x16,
        pdRule = TransportValidationPdRule.TRUEHD_MAT,
        expectedBurstModel = TransportValidationBurstModel.MAT,
        expectedRouteTuple = TransportValidationRouteTuple(
            encoding = "IEC61937",
            sampleRate = 192000,
            channelMask = "7.1",
        ),
        assetChecksums = emptyMap(),
    )

    private fun activeSession(collector: TransportValidationRuntimeCollector): Any {
        val field = TransportValidationRuntimeCollector::class.java.getDeclaredField("activeSession")
        field.isAccessible = true
        return requireNotNull(field.get(collector))
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeToRouteHealthSummary(
        samples: List<TransportValidationTimedRouteSnapshot>,
        stableStartAtElapsedRealtimeMs: Long?,
        routeScoringStartAtElapsedRealtimeMs: Long?,
        analyticsEvents: List<TransportValidationRuntimeAnalyticsEvent>,
    ): TransportValidationRouteHealthSummary {
        val method =
            Class.forName("com.nexio.tv.debug.passthrough.TransportValidationRuntimeCollectorKt")
                .getDeclaredMethod(
                    "toRouteHealthSummary",
                    List::class.java,
                    java.lang.Long::class.java,
                    java.lang.Long::class.java,
                    List::class.java,
                )
        return method.invoke(
            null,
            samples,
            stableStartAtElapsedRealtimeMs,
            routeScoringStartAtElapsedRealtimeMs,
            analyticsEvents,
        ) as TransportValidationRouteHealthSummary
    }

    private fun invokeIsPositionStalled(
        samples: List<PositionSample>,
        thresholds: TransportValidationRuntimeThresholds,
        nowElapsedRealtimeMs: Long,
    ): Boolean {
        val method =
            Class.forName("com.nexio.tv.debug.passthrough.TransportValidationRuntimeCollectorKt")
                .getDeclaredMethod(
                    "isPositionStalled",
                    List::class.java,
                    TransportValidationRuntimeThresholds::class.java,
                    Long::class.javaPrimitiveType,
                )
        return method.invoke(null, samples, thresholds, nowElapsedRealtimeMs) as Boolean
    }
}
