package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.ContinueWatchingSnapshotStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.domain.repository.MetaRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-G-02 pin: continue_watching.snapshot_read event MUST fire when a subscriber attaches
 * to the CW snapshot flow. Pins the envelope shape (eventType + key payload fields).
 */
class ContinueWatchingSnapshotReadTraceTest {

    @After
    fun tearDown() {
        ContinueWatchingSnapshotService.installTraceSink(NoopRuntimeTraceSink) { null }
    }

    @Test
    fun `subscription emits one snapshot_read event with profileId and recordCount payload`() = runTest {
        val sink = RecordingTraceSink()
        ContinueWatchingSnapshotService.installTraceSink(sink) { "s1" }

        val service = buildService()

        // Subscribe to snapshot flow — triggers snapshot_read emission in onStart
        service.observeSnapshot().first()

        // Verify exactly one snapshot_read event was emitted
        val readEvents = sink.events.filter { it.eventType == "continue_watching.snapshot_read" }
        assertTrue(
            "expected at least one snapshot_read event, got ${sink.events.map { it.eventType }}",
            readEvents.isNotEmpty()
        )

        // Verify the event payload contains expected key fields
        val payload = readEvents.first().payload as Map<*, *>
        assertTrue("payload must include profileId", payload.containsKey("profileId"))
        assertTrue("payload must include recordCount", payload.containsKey("recordCount"))

        // Verify payload types and values
        val profileId = payload["profileId"] as? Int
        val recordCount = payload["recordCount"] as? Int
        assertTrue("profileId should be a positive integer", profileId != null && profileId > 0)
        assertTrue("recordCount should be a non-negative integer", recordCount != null && recordCount >= 0)

        // Verify envelope fields are present
        assertEquals("s1", readEvents.first().traceSessionId)
        assertTrue("sequence should be positive", readEvents.first().sequence > 0)
        assertTrue("wallClockMs should be set", readEvents.first().wallClockMs > 0)
    }

    private fun buildService(): ContinueWatchingSnapshotService {
        val activeProfileId = MutableStateFlow(1)
        val profileManager = mockk<ProfileManager> {
            every { this@mockk.activeProfileId } returns activeProfileId
            every { this@mockk.profileSwitched } returns MutableSharedFlow(extraBufferCapacity = 1)
        }

        return ContinueWatchingSnapshotService(
            watchProgressRepository = mockk {
                every { allProgress } returns MutableStateFlow(emptyList())
            },
            trackingProgressService = mockk {
                every { observeRemoteSnapshotLoaded() } returns MutableStateFlow(false)
                every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
                every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
            },
            trackingProviderStateService = mockk {
                every { state } returns MutableStateFlow(
                    EffectiveTrackingProviderState(traktAuthenticated = true)
                )
            },
            traktSettingsDataStore = mockk {
                every { dismissedNextUpKeys } returns flowOf(emptySet())
            },
            metaRepository = mockk<MetaRepository>(relaxed = true),
            metadataDiskCacheStore = mockk(relaxed = true),
            snapshotStore = mockk(relaxed = true),
            profileManager = profileManager
        )
    }
}
