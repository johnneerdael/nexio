package com.nexio.tv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StartupProfileGateContractTest {
    private val applicationSource = File("app/src/main/java/com/nexio/tv/NexioApplication.kt")
    private val mainActivitySource = File("app/src/main/java/com/nexio/tv/MainActivity.kt")
    private val startupSyncServiceSource = File("app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt")

    @Test
    fun `startup account sync has no constructor side effects`() {
        val source = startupSyncServiceSource.readText()

        assertFalse(
            "StartupSyncService must not start profile-bound collectors from init before profile selection",
            Regex("""\binit\s*\{""").containsMatchIn(source)
        )
        assertTrue(
            "StartupSyncService should expose an explicit profile-gated starter",
            source.contains("fun startAfterProfileGate(")
        )
        assertTrue(
            "requestSyncNow before profile selection should defer instead of scheduling network sync",
            source.contains("Deferring startup sync request until profile gate resolves")
        )
    }

    @Test
    fun `application startup does not construct or run profile-bound sync`() {
        val source = applicationSource.readText()

        assertFalse(
            "NexioApplication field injection must not eagerly construct StartupSyncService",
            source.contains("StartupSyncService")
        )
        assertFalse(
            "TVDB startup catch-up is profile-bound and must not run from Application.onCreate",
            source.contains("catchUpUpdates(TvdbUpdateTrigger.STARTUP)")
        )
    }

    @Test
    fun `main activity starts account sync and tvdb catch-up after profile gate`() {
        val source = mainActivitySource.readText()

        assertTrue(
            "MainActivity should start account sync observers only from the profile-gated path",
            source.contains("startupSyncService.startAfterProfileGate(reason)")
        )
        assertTrue(
            "Deferred startup work should also assert the profile-gated starter before requesting sync",
            source.contains("startupSyncService.startAfterProfileGate(\"deferred_startup\")")
        )
        assertTrue(
            "TVDB startup catch-up should run from deferred profile-gated startup work",
            source.contains("tvdbUpdateCoordinator.catchUpUpdates(TvdbUpdateTrigger.STARTUP)")
        )
    }
}
