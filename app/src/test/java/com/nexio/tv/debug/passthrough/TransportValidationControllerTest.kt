package com.nexio.tv.debug.passthrough

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportValidationControllerTest {

    @Test
    fun `controller toggles validation enabled state`() = runTest {
        val store = FakeTransportValidationSettingsStore()
        val controller = TransportValidationController(store)

        controller.setEnabled(true)

        assertTrue(controller.state.first { it.settings.enabled }.settings.enabled)
    }

    @Test
    fun `controller selects bundled sample and capture options`() = runTest {
        val store = FakeTransportValidationSettingsStore()
        val controller = TransportValidationController(store)

        controller.selectSample("truehd")
        controller.setComparisonMode(TransportValidationComparisonMode.PREAMBLE_ONLY)
        controller.setCaptureMode(TransportValidationCaptureMode.UNTIL_FAILURE)
        controller.setCaptureBurstCount(16)
        controller.setBinaryDumpsEnabled(true)
        controller.setRuntimeValidationEnabled(false)
        controller.setRuntimeStartupTimeoutMs(8000)
        controller.setRuntimeObservationWindowMs(15000)

        val settings = controller.state.first { it.settings.selectedSampleId == "truehd" }.settings
        assertEquals("truehd", settings.selectedSampleId)
        assertEquals(TransportValidationComparisonMode.PREAMBLE_ONLY, settings.comparisonMode)
        assertEquals(TransportValidationCaptureMode.UNTIL_FAILURE, settings.captureMode)
        assertEquals(16, settings.captureBurstCount)
        assertTrue(settings.binaryDumpsEnabled)
        assertFalse(settings.runtimeValidationEnabled)
        assertEquals(8000, settings.runtimeStartupTimeoutMs)
        assertEquals(15000, settings.runtimeObservationWindowMs)
    }

    @Test
    fun `controller exposes current settings state for UI and adb surfaces`() = runTest {
        val store = FakeTransportValidationSettingsStore(
            TransportValidationSettings(
                enabled = true,
                selectedSampleId = "dtshd",
                comparisonMode = TransportValidationComparisonMode.FULL_BURST_COMPARE,
                captureMode = TransportValidationCaptureMode.FIRST_N_BURSTS,
                captureBurstCount = 8,
                binaryDumpsEnabled = false,
                runtimeValidationEnabled = true,
                runtimeStartupTimeoutMs = 5000,
                runtimeObservationWindowMs = 30000,
                exportRequestCount = 2,
            )
        )
        val controller = TransportValidationController(store)

        val settings = controller.state.first { it.settings.selectedSampleId == "dtshd" }.settings

        assertTrue(settings.enabled)
        assertEquals("dtshd", settings.selectedSampleId)
        assertEquals(8, settings.captureBurstCount)
        assertFalse(settings.binaryDumpsEnabled)
        assertTrue(settings.runtimeValidationEnabled)
        assertEquals(5000, settings.runtimeStartupTimeoutMs)
        assertEquals(30000, settings.runtimeObservationWindowMs)
        assertEquals(2, settings.exportRequestCount)
    }

    @Test
    fun `controller increments export request count`() = runTest {
        val store = FakeTransportValidationSettingsStore()
        val controller = TransportValidationController(store)

        controller.requestExport()
        controller.requestExport()

        assertEquals(
            2,
            controller.state.first { it.settings.exportRequestCount == 2 }.settings.exportRequestCount
        )
    }

    private class FakeTransportValidationSettingsStore(
        initialSettings: TransportValidationSettings = TransportValidationSettings(),
    ) : TransportValidationSettingsStore {
        private val state = MutableStateFlow(initialSettings)

        override val transportValidationSettings: Flow<TransportValidationSettings> = state

        override suspend fun setTransportValidationEnabled(enabled: Boolean) {
            state.value = state.value.copy(enabled = enabled)
        }

        override suspend fun setTransportValidationSelectedSampleId(sampleId: String?) {
            state.value = state.value.copy(selectedSampleId = sampleId)
        }

        override suspend fun setTransportValidationComparisonMode(mode: TransportValidationComparisonMode) {
            state.value = state.value.copy(comparisonMode = mode)
        }

        override suspend fun setTransportValidationCaptureMode(mode: TransportValidationCaptureMode) {
            state.value = state.value.copy(captureMode = mode)
        }

        override suspend fun setTransportValidationCaptureBurstCount(count: Int) {
            state.value = state.value.copy(captureBurstCount = count)
        }

        override suspend fun setTransportValidationBinaryDumpsEnabled(enabled: Boolean) {
            state.value = state.value.copy(binaryDumpsEnabled = enabled)
        }

        override suspend fun setTransportValidationRuntimeValidationEnabled(enabled: Boolean) {
            state.value = state.value.copy(runtimeValidationEnabled = enabled)
        }

        override suspend fun setTransportValidationRuntimeStartupTimeoutMs(timeoutMs: Int) {
            state.value = state.value.copy(runtimeStartupTimeoutMs = timeoutMs)
        }

        override suspend fun setTransportValidationRuntimeObservationWindowMs(
            observationWindowMs: Int
        ) {
            state.value = state.value.copy(runtimeObservationWindowMs = observationWindowMs)
        }

        override suspend fun incrementTransportValidationExportRequestCount() {
            state.value = state.value.copy(exportRequestCount = state.value.exportRequestCount + 1)
        }
    }
}
