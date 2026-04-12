package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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

    @Test
    fun forMemoryBudget_usesEffectiveFillHorizon() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val budget = MemoryBudget(context)

        val profile = ProviderProfile.forMemoryBudget(budget)

        assertEquals(budget.effectiveFillHorizonBytes, profile.fillHorizonBytes)
        assertEquals(budget.effectiveFillHorizonBytes / 2L, profile.lowWaterBytes)
    }
}
