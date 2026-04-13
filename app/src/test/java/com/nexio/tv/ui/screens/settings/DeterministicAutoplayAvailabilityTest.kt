package com.nexio.tv.ui.screens.settings

import com.nexio.tv.data.local.AutoplayBandwidthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicAutoplayAvailabilityTest {

    @Test
    fun `manual mode makes deterministic autoplay available without benchmark`() {
        assertEquals(
            AutoplayBandwidthMode.MANUAL,
            AutoplayBandwidthMode.MANUAL.effectiveAutoplayBandwidthMode(
                autoplayBenchmarkAvailable = false
            )
        )

        assertTrue(
            AutoplayBandwidthMode.MANUAL.isDeterministicAutoplayAvailable(
                autoplayBenchmarkAvailable = false
            )
        )
    }

    @Test
    fun `auto mode falls back to manual when benchmark is unavailable`() {
        assertEquals(
            AutoplayBandwidthMode.MANUAL,
            AutoplayBandwidthMode.AUTO.effectiveAutoplayBandwidthMode(
                autoplayBenchmarkAvailable = false
            )
        )

        assertTrue(
            AutoplayBandwidthMode.AUTO.isDeterministicAutoplayAvailable(
                autoplayBenchmarkAvailable = false
            )
        )
    }

    @Test
    fun `auto mode remains auto when benchmark is available`() {
        assertEquals(
            AutoplayBandwidthMode.AUTO,
            AutoplayBandwidthMode.AUTO.effectiveAutoplayBandwidthMode(
                autoplayBenchmarkAvailable = true
            )
        )

        assertTrue(
            AutoplayBandwidthMode.AUTO.isDeterministicAutoplayAvailable(
                autoplayBenchmarkAvailable = true
            )
        )
    }
}
