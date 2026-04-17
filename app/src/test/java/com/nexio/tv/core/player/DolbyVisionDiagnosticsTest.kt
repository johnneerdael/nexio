package com.nexio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionDiagnosticsTest {

    @Test
    fun `dolby vision verbose logging flag is tracked in kotlin`() {
        DoviBridge.setVerboseLoggingEnabled(false)
        assertFalse(DoviBridge.isVerboseLoggingEnabled())

        DoviBridge.setVerboseLoggingEnabled(true)
        assertTrue(DoviBridge.isVerboseLoggingEnabled())

        DoviBridge.setVerboseLoggingEnabled(false)
    }

    @Test
    fun `dolby diagnostics snapshots are zero when disabled and reset`() {
        DoviBridge.setVerboseLoggingEnabled(false)
        DoviBridge.resetRuntimeCounters()
        MatroskaDolbyVisionHookInstaller.setDiagnosticsEnabled(false)
        MatroskaDolbyVisionHookInstaller.resetRuntimeCounters()
        Dv5HardwareToneMapRpuTap.setDiagnosticsEnabled(false)
        Dv5HardwareToneMapRpuTap.setEnabledForPlayback(false, "https://example.com/movie.mkv")

        val bridge = DoviBridge.runtimeDiagnosticsSnapshot()
        val hook = MatroskaDolbyVisionHookInstaller.runtimeAllocationSnapshot()
        val tap = Dv5HardwareToneMapRpuTap.runtimeSnapshot()

        assertFalse(bridge.enabled)
        assertFalse(hook.enabled)
        assertFalse(tap.enabled)
        assertTrue(bridge.inputBytes == 0L)
        assertTrue(hook.nalCopyBytes == 0L)
        assertTrue(tap.copiedBytes == 0L)
    }
}
