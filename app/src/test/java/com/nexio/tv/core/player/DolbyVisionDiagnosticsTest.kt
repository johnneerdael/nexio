package com.nexio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionDiagnosticsTest {

    private interface DolbyVisionCodecStringHandler {
        fun onDolbyVisionCodecString(codecs: String?, dolbyVisionConfigBytes: ByteArray?): String?
    }

    private interface MatroskaStreamingHandler {
        fun shouldTransformHevcSampleNalByNal(
            sampleTimeUs: Long,
            nalUnitLengthFieldLength: Int,
            blockAdditionalData: ByteArray?,
            dolbyVisionConfigBytes: ByteArray?
        ): Boolean

        fun transformDolbyVisionRpuNal(
            rpuNalPayload: ByteArray,
            sampleTimeUs: Long,
            blockAdditionalData: ByteArray?,
            dolbyVisionConfigBytes: ByteArray?
        ): ByteArray?
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

    @Test
    fun `matroska streaming hook converts only rpu nal and publishes selected mode`() {
        MatroskaDolbyVisionHookInstaller.resetRuntimeCounters()
        val invocationHandler = createHookInvocationHandler(
            conversionEnabled = true,
            allowDv5Conversion = false,
            preserveMappingEnabled = true,
            enableRpuTap = false
        )

        val shouldStream = invocationHandler.invoke(
            Any(),
            MatroskaStreamingMethods.shouldTransform,
            arrayOf(123L, 4, null, dvConfig(profile = 7))
        ) as Boolean

        val rpu = byteArrayOf(0x7D.toByte(), 0xF9.toByte(), 0x01)
        val converted = invocationHandler.invoke(
            Any(),
            MatroskaStreamingMethods.transformRpu,
            arrayOf(rpu, 123L, null, dvConfig(profile = 7))
        ) as ByteArray?

        assertTrue(shouldStream)
        assertEquals(
            DolbyVisionConversionModeSelector.MODE_PROFILE_8_1_PRESERVE_MAPPING,
            MatroskaDolbyVisionHookInstaller.getLastSelectedConversionMode()
        )
        if (DoviBridge.isAvailable()) {
            assertTrue(converted == null || converted.isNotEmpty())
        }
    }

    private fun createHookInvocationHandler(
        conversionEnabled: Boolean,
        allowDv5Conversion: Boolean,
        preserveMappingEnabled: Boolean,
        enableRpuTap: Boolean
    ): java.lang.reflect.InvocationHandler {
        val createInvocationHandler = MatroskaDolbyVisionHookInstaller::class.java.getDeclaredMethod(
            "createInvocationHandler",
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        createInvocationHandler.isAccessible = true
        return createInvocationHandler.invoke(
            MatroskaDolbyVisionHookInstaller,
            "https://example.com/movie.mkv",
            conversionEnabled,
            allowDv5Conversion,
            preserveMappingEnabled,
            enableRpuTap
        ) as java.lang.reflect.InvocationHandler
    }

    private object MatroskaStreamingMethods {
        val shouldTransform: java.lang.reflect.Method =
            MatroskaStreamingHandler::class.java.getMethod(
                "shouldTransformHevcSampleNalByNal",
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
                ByteArray::class.java
            )
        val transformRpu: java.lang.reflect.Method =
            MatroskaStreamingHandler::class.java.getMethod(
                "transformDolbyVisionRpuNal",
                ByteArray::class.java,
                Long::class.javaPrimitiveType,
                ByteArray::class.java,
                ByteArray::class.java
            )
    }

    private fun dvConfig(profile: Int): ByteArray {
        return byteArrayOf(
            0x01,
            0x00,
            ((profile and 0x7F) shl 1).toByte(),
            0x00,
        )
    }
}
