package com.nexio.tv.debug.passthrough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportValidationSinkWriteEventDetailTest {

    @Test
    fun `parses remainder retry detail payload`() {
        val parsed =
            parseTransportValidationSinkWriteEventDetail(
                "requestedBytes=61440 pendingRemainderId=17 packetId=17 ownership=startup " +
                    "firstOffsetBytes=4096 offsetBytes=49536 bytesRemaining=11904 " +
                    "lastWriteBytes=49536 pendingRemainderLastProgressUs=1234567 " +
                    "sinceLastSuccessfulWriteMs=27 " +
                    "playbackHeadDeltaFrames=2560 bufferFitDeltaFrames=4096 " +
                    "pendingRemainderRetryCount=3 retryCount=3 " +
                    "retryEligibleReason=playback_head_advanced retryReason=playback_head_advanced " +
                    "startupActive=true startupCompleted=false nativeOutputStarted=true " +
                    "nativeStartupReady=true nativeRemainderOwnership=steady_state " +
                    "meaningfulWriteCount=4 handoffEligible=true handoffTriggered=false " +
                    "selectedPath=startup_path",
            )

        assertEquals(61440, parsed.requestedBytes)
        assertEquals(17L, parsed.pendingRemainderId)
        assertEquals(17L, parsed.packetId)
        assertEquals("startup", parsed.ownership)
        assertEquals(4096, parsed.firstOffsetBytes)
        assertEquals(49536, parsed.offsetBytes)
        assertEquals(11904, parsed.bytesRemaining)
        assertEquals(49536, parsed.lastWriteBytes)
        assertEquals(1234567L, parsed.pendingRemainderLastProgressUs)
        assertEquals(27L, parsed.sinceLastSuccessfulWriteMs)
        assertEquals(2560L, parsed.playbackHeadDeltaFrames)
        assertEquals(4096L, parsed.bufferFitDeltaFrames)
        assertEquals(3, parsed.pendingRemainderRetryCount)
        assertEquals(3, parsed.retryCount)
        assertEquals("playback_head_advanced", parsed.retryEligibleReason)
        assertEquals("playback_head_advanced", parsed.retryReason)
        assertEquals(true, parsed.startupActive)
        assertEquals(false, parsed.startupCompleted)
        assertEquals(true, parsed.nativeOutputStarted)
        assertEquals(true, parsed.nativeStartupReady)
        assertEquals("steady_state", parsed.nativeRemainderOwnership)
        assertEquals(4L, parsed.meaningfulWriteCount)
        assertEquals(true, parsed.handoffEligible)
        assertEquals(false, parsed.handoffTriggered)
        assertEquals("startup_path", parsed.selectedPath)
    }

    @Test
    fun `returns defaults for empty or unknown detail`() {
        val parsed = parseTransportValidationSinkWriteEventDetail("requestedBytes=3876 junk=x")

        assertEquals(3876, parsed.requestedBytes)
        assertNull(parsed.packetId)
        assertNull(parsed.retryEligibleReason)
        assertNull(parsed.retryReason)
        assertFalse(parsed.hasRetryInstrumentation())
    }

    @Test
    fun `detects retry instrumentation when new fields are present`() {
        val parsed =
            parseTransportValidationSinkWriteEventDetail(
                "requestedBytes=61440 pendingRemainderId=17 ownership=startup " +
                    "pendingRemainderRetryEligibleReason=not_eligible startupCompleted=true selectedPath=steady_state_path",
            )

        assertTrue(parsed.hasRetryInstrumentation())
    }
}
