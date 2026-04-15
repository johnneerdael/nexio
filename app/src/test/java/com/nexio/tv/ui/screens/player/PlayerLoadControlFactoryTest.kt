package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.data.local.BufferSettings
import com.nexio.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerLoadControlFactoryTest {
    @Test
    fun targetBufferBytes_usesConfiguredTargetWhenItFitsMemoryBudget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = PlayerSettings(
            bufferSettings = BufferSettings(targetBufferSizeMb = 96)
        )

        val targetBytes = PlayerLoadControlFactory.resolveTargetBufferBytes(context, settings)

        assertEquals(96L * 1024L * 1024L, targetBytes.toLong())
    }

    @Test
    fun targetBufferBytes_clampsOversizedConfiguredTargetToPlaybackCeiling() {
        val settings = PlayerSettings(
            bufferSettings = BufferSettings(targetBufferSizeMb = 512)
        )

        val targetBytes = PlayerLoadControlFactory.resolveTargetBufferBytes(
            settings = settings,
            effectiveSampleQueueBytes = 350L * 1024L * 1024L
        )

        assertEquals(96L * 1024L * 1024L, targetBytes.toLong())
    }

    @Test
    fun targetBufferBytes_usesPlaybackCeilingWhenSettingIsAuto() {
        val settings = PlayerSettings(
            bufferSettings = BufferSettings(targetBufferSizeMb = 0)
        )

        val targetBytes = PlayerLoadControlFactory.resolveTargetBufferBytes(
            settings = settings,
            effectiveSampleQueueBytes = 350L * 1024L * 1024L
        )

        assertEquals(96L * 1024L * 1024L, targetBytes.toLong())
    }

    @Test
    fun targetBufferBytes_usesLowMemoryBudgetBelowMinimumWhenSettingIsAuto() {
        val settings = PlayerSettings(
            bufferSettings = BufferSettings(targetBufferSizeMb = 0)
        )

        val targetBytes = PlayerLoadControlFactory.resolveTargetBufferBytes(
            settings = settings,
            effectiveSampleQueueBytes = 10L * 1024L * 1024L
        )

        assertEquals(10L * 1024L * 1024L, targetBytes.toLong())
    }

    @Test
    fun targetBufferBytes_clampsConfiguredTargetToLowMemoryBudgetBelowMinimum() {
        val settings = PlayerSettings(
            bufferSettings = BufferSettings(targetBufferSizeMb = 512)
        )

        val targetBytes = PlayerLoadControlFactory.resolveTargetBufferBytes(
            settings = settings,
            effectiveSampleQueueBytes = 10L * 1024L * 1024L
        )

        assertEquals(10L * 1024L * 1024L, targetBytes.toLong())
    }
}
