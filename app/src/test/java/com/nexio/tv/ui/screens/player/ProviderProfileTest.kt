package com.nexio.tv.ui.screens.player

import org.junit.Assert.fail
import org.junit.Test

class ProviderProfileTest {
    @Test
    fun `rejects low water above horizon`() {
        try {
            ProviderProfile(
                fillHorizonBytes = 64L * 1024L * 1024L,
                lowWaterBytes = 128L * 1024L * 1024L
            )
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `rejects retain behind above horizon`() {
        try {
            ProviderProfile(
                fillHorizonBytes = 64L * 1024L * 1024L,
                retainBehindBytes = 128L * 1024L * 1024L
            )
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
