package com.nexio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionDiagnosticsTest {

    private interface DolbyVisionCodecStringHandler {
        fun onDolbyVisionCodecString(codecs: String?, dolbyVisionConfigBytes: ByteArray?): String?
    }

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
        assertTrue(hook.rewriteCopyBytes == 0L)
        assertTrue(hook.sourceCopyBytes == 0L)
        assertTrue(hook.rpuInputBytes == 0L)
        assertTrue(hook.rpuOutputBytes == 0L)
        assertTrue(hook.lengthFieldBytes == 0L)
        assertTrue(hook.droppedBytes == 0L)
        assertTrue(tap.copiedBytes == 0L)
    }

    @Test
    fun `codec string flow records selected conversion mode during allow checks`() {
        MatroskaDolbyVisionHookInstaller.resetRuntimeCounters()

        val handlerMethod = DolbyVisionCodecStringHandler::class.java.getMethod(
            "onDolbyVisionCodecString",
            String::class.java,
            ByteArray::class.java
        )
        val createInvocationHandler = MatroskaDolbyVisionHookInstaller::class.java.getDeclaredMethod(
            "createInvocationHandler",
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        createInvocationHandler.isAccessible = true

        val invocationHandler = createInvocationHandler.invoke(
            MatroskaDolbyVisionHookInstaller,
            "https://example.com/movie.mkv",
            true,
            false,
            false,
            false
        ) as java.lang.reflect.InvocationHandler

        val normalized = invocationHandler.invoke(
            Any(),
            handlerMethod,
            arrayOf("dvhe.07", null)
        ) as String?

        assertEquals("dvhe.08", normalized)
        assertEquals(
            DolbyVisionConversionModeSelector.MODE_PROFILE_8_1,
            MatroskaDolbyVisionHookInstaller.getLastSelectedConversionMode()
        )
    }
}
