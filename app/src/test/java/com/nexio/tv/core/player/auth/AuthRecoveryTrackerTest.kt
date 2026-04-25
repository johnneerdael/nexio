package com.nexio.tv.core.player.auth

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AuthRecoveryTrackerTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        AuthRecoveryTracker.resetForTesting()
    }

    @After
    fun tearDown() {
        AuthRecoveryTracker.resetForTesting()
        unmockkStatic(Log::class)
    }

    @Test
    fun `records attempts in insertion order capped at ring size`() {
        repeat(20) { i ->
            AuthRecoveryTracker.record(
                proxyUrl = "https://example/p$i",
                statusCode = 401,
                outcome = AuthRecoveryTracker.Outcome.RECOVERED
            )
        }
        val snapshot = AuthRecoveryTracker.snapshot()
        assertEquals(16, snapshot.size)
        assertEquals("https://example/p4", snapshot.first().proxyUrl)
        assertEquals("https://example/p19", snapshot.last().proxyUrl)
    }

    @Test
    fun `count returns total attempts since reset`() {
        AuthRecoveryTracker.record("u", 401, AuthRecoveryTracker.Outcome.RECOVERED)
        AuthRecoveryTracker.record("u", 401, AuthRecoveryTracker.Outcome.GAVE_UP)
        assertEquals(2, AuthRecoveryTracker.totalAttempts())
        assertEquals(1, AuthRecoveryTracker.recoveredCount())
    }
}
