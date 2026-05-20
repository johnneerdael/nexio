package com.nexio.tv.core.recommendations

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTvChannelPublisherProfileGateContractTest {
    private val source = listOf(
        File("app/src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelPublisher.kt"),
        File("src/main/java/com/nexio/tv/core/recommendations/AndroidTvChannelPublisher.kt")
    ).first { it.isFile }.readText()

    @Test
    fun `publisher does not subscribe profile-bound flows from constructor`() {
        assertEquals(
            "Android TV channel publisher must not start profile-bound observers in init before profile selection",
            -1,
            source.indexOf("init {")
        )
    }

    @Test
    fun `profile-bound continue watching observer starts only after profile gate`() {
        val startFunction = source.substringAfter("fun startProfileBoundSyncAfterProfileGate")
            .substringBefore("\n    fun requestSync")

        assertTrue(startFunction.contains("profileGateResolved = true"))
        assertTrue(startFunction.contains("continueWatchingSnapshotService.observeProfileSnapshot"))
        assertTrue(startFunction.contains("requestSync(reason)"))
    }

    @Test
    fun `manual channel sync requests are ignored before profile gate`() {
        val requestSync = source.substringAfter("fun requestSync")
            .substringBefore("\n    suspend fun syncNow")

        assertTrue(requestSync.contains("if (!profileGateResolved)"))
        assertTrue(requestSync.contains("return"))
    }
}
