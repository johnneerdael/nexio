package com.nexio.tv.core.profile

import com.nexio.tv.core.integration.ProfileBoundaryEnforcer
import com.nexio.tv.core.integration.ProfileBoundaryException
import com.nexio.tv.core.integration.ProfileBoundaryViolation
import com.nexio.tv.core.integration.RecordingTraceSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-F-02 pin: ProfileBoundaryEnforcer.assertCanSwitchProfile MUST emit a profile.boundary_check
 * event with verdict=FAIL and violation=PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK before
 * throwing ProfileBoundaryException.
 *
 * The full ProfileManager-level integration test would require ~10 collaborators (Postgrest,
 * DataStore, Context, etc.) — this test pins the enforcer's contract directly. ProfileManager
 * wiring is verified by source-grep below.
 */
class ProfileManagerSwitchBoundaryCheckTraceTest {

    @Test
    fun `assertCanSwitchProfile rejection emits FAIL boundary_check before throwing`() {
        val sink = RecordingTraceSink()
        ProfileBoundaryEnforcer.installTraceSink(sink = sink, sessionId = { "s1" })

        val ex = runCatching {
            ProfileBoundaryEnforcer.assertCanSwitchProfile(
                activeProfileId = 1,
                targetProfileId = 2,
                hasActivePlaybackOwner = true
            )
        }.exceptionOrNull()

        assertTrue(
            "expected ProfileBoundaryException, got $ex",
            ex is ProfileBoundaryException &&
                ex.violation == ProfileBoundaryViolation.PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK
        )

        val boundaryEvents = sink.events.filter { it.eventType == "profile.boundary_check" }
        assertEquals(
            "expected exactly one boundary_check event, got ${boundaryEvents.map { it.payload }}",
            1,
            boundaryEvents.size
        )
        val payload = boundaryEvents.first().payload as Map<*, *>
        assertEquals("FAIL", payload["verdict"])
        assertEquals("PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK", payload["violation"])
    }

    @Test
    fun `assertCanSwitchProfile pass emits PASS boundary_check`() {
        val sink = RecordingTraceSink()
        ProfileBoundaryEnforcer.installTraceSink(sink = sink, sessionId = { "s1" })

        ProfileBoundaryEnforcer.assertCanSwitchProfile(
            activeProfileId = 1,
            targetProfileId = 2,
            hasActivePlaybackOwner = false
        ) // no playback owner — should pass

        val boundaryEvents = sink.events.filter { it.eventType == "profile.boundary_check" }
        assertEquals(1, boundaryEvents.size)
        val payload = boundaryEvents.first().payload as Map<*, *>
        assertEquals("PASS", payload["verdict"])
    }

    /**
     * F2-F-03: assertCanWriteProfileState must emit profile.boundary_check with verdict=FAIL
     * before throwing ProfileBoundaryException for a stale-session mismatch.
     */
    @Test
    fun `assertCanWriteProfileState emits boundary_check FAIL event before throwing`() {
        val sink = RecordingTraceSink()
        ProfileBoundaryEnforcer.installTraceSink(sink = sink, sessionId = { "test-session" })

        val ex = runCatching {
            ProfileBoundaryEnforcer.assertCanWriteProfileState(
                resultProfileId = 1,
                resultSessionId = "s1",
                activeProfileId = 2,
                activeSessionId = "s2"
            )
        }.exceptionOrNull()

        assertTrue(
            "expected ProfileBoundaryException(STALE_SESSION_WRITE_REJECTED), got $ex",
            ex is ProfileBoundaryException &&
                ex.violation == ProfileBoundaryViolation.STALE_SESSION_WRITE_REJECTED
        )

        val failEvents = sink.events.filter {
            it.eventType == "profile.boundary_check" &&
                (it.payload as? Map<*, *>)?.get("verdict") == "FAIL"
        }
        assertTrue(
            "expected at least one profile.boundary_check FAIL event, got ${sink.events.map { it.payload }}",
            failEvents.isNotEmpty()
        )
        val payload = failEvents.first().payload as Map<*, *>
        assertEquals("STALE_SESSION_WRITE_REJECTED", payload["violation"])
    }

    @Test
    fun `ProfileManager source routes through enforcer (source-grep pin)`() {
        val candidates = listOf(
            java.io.File("src/main/java/com/nexio/tv/core/profile/ProfileManager.kt"),
            java.io.File("app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt"),
            java.io.File("../app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt")
        )
        val src = candidates.firstOrNull { it.exists() }
        require(src != null) {
            "ProfileManager.kt not found; tried: " +
                candidates.joinToString { it.absolutePath }
        }
        val text = src.readText()

        val callsEnforcer =
            Regex("ProfileBoundaryEnforcer\\.assertCanSwitchProfile\\(").containsMatchIn(text)
        assertTrue(
            "F-F-02: ProfileManager.setActiveProfile must invoke " +
                "ProfileBoundaryEnforcer.assertCanSwitchProfile (so profile.boundary_check " +
                "trace event fires before throwing)",
            callsEnforcer
        )
    }
}
