package com.nexio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import com.nexio.tv.core.player.BurnInProtectionState
import com.nexio.tv.data.local.SubtitleStyleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerVideoSurfaceStateTest {

    @Test
    fun `addon overlay cues override built in ai cues`() {
        val addonCue = Cue.Builder().setText("addon").build()
        val aiCue = Cue.Builder().setText("ai").build()

        val result = resolveOverlayCues(
            useAiOverlay = true,
            translatedBuiltInCues = listOf(aiCue),
            addonOverlayCues = listOf(addonCue)
        )

        assertEquals(listOf(addonCue), result)
    }

    @Test
    fun `ai overlay suppresses native subtitles while translated cues are pending`() {
        val state = PlayerSurfaceRenderState(
            resizeMode = 1,
            subtitleStyle = SubtitleStyleSettings(),
            burnInProtection = BurnInProtectionState.DISABLED,
            overlayCues = emptyList(),
            suppressNativeSubtitles = true
        )

        assertTrue(state.suppressNativeSubtitles)
    }

    @Test
    fun `mutation plan skips work when surface state is unchanged`() {
        val state = PlayerSurfaceRenderState(
            resizeMode = 1,
            subtitleStyle = SubtitleStyleSettings(),
            burnInProtection = BurnInProtectionState.DISABLED,
            overlayCues = emptyList(),
            suppressNativeSubtitles = false
        )

        val plan = buildPlayerViewMutationPlan(previous = state, current = state)

        assertFalse(plan.updateResizeMode)
        assertFalse(plan.updateSubtitleStyle)
        assertFalse(plan.updateOverlay)
        assertFalse(plan.updateKeepScreenOn)
    }

    @Test
    fun `mutation plan updates overlay only when cues change`() {
        val previous = PlayerSurfaceRenderState(
            resizeMode = 1,
            subtitleStyle = SubtitleStyleSettings(),
            burnInProtection = BurnInProtectionState.DISABLED,
            overlayCues = emptyList(),
            suppressNativeSubtitles = false
        )
        val current = previous.copy(
            overlayCues = listOf(Cue.Builder().setText("Hello").build())
        )

        val plan = buildPlayerViewMutationPlan(previous = previous, current = current)

        assertFalse(plan.updateResizeMode)
        assertFalse(plan.updateSubtitleStyle)
        assertTrue(plan.updateOverlay)
        assertFalse(plan.updateKeepScreenOn)
    }

    @Test
    fun `mutation plan updates overlay when native subtitle suppression changes`() {
        val previous = PlayerSurfaceRenderState(
            resizeMode = 1,
            subtitleStyle = SubtitleStyleSettings(),
            burnInProtection = BurnInProtectionState.DISABLED,
            overlayCues = emptyList(),
            suppressNativeSubtitles = false
        )
        val current = previous.copy(suppressNativeSubtitles = true)

        val plan = buildPlayerViewMutationPlan(previous = previous, current = current)

        assertFalse(plan.updateResizeMode)
        assertFalse(plan.updateSubtitleStyle)
        assertTrue(plan.updateOverlay)
        assertFalse(plan.updateKeepScreenOn)
    }

    @Test
    fun `mutation plan updates keep screen on only when playback wake state changes`() {
        val previous = PlayerSurfaceRenderState(
            resizeMode = 1,
            subtitleStyle = SubtitleStyleSettings(),
            burnInProtection = BurnInProtectionState.DISABLED,
            overlayCues = emptyList(),
            suppressNativeSubtitles = false,
            keepScreenOn = false
        )
        val current = previous.copy(keepScreenOn = true)

        val plan = buildPlayerViewMutationPlan(previous = previous, current = current)

        assertFalse(plan.updateResizeMode)
        assertFalse(plan.updateSubtitleStyle)
        assertFalse(plan.updateOverlay)
        assertTrue(plan.updateKeepScreenOn)
    }

    @Test
    fun `compose surface sync workaround helper invokes optional media3 method`() {
        val target = FakeComposeSurfaceSyncTarget()

        val applied = enableComposeSurfaceSyncWorkaroundIfAvailable(target)

        assertTrue(applied)
        assertTrue(target.enabled)
    }

    @Test
    fun `compose surface sync workaround helper ignores missing method`() {
        val applied = enableComposeSurfaceSyncWorkaroundIfAvailable(Any())

        assertFalse(applied)
    }

    @Test
    fun `mutation plan invalidates subtitle style when burn-in protection changes`() {
        val baseSubtitleStyle = SubtitleStyleSettings()
        val previous = PlayerSurfaceRenderState(
            resizeMode = 1,
            subtitleStyle = baseSubtitleStyle,
            burnInProtection = BurnInProtectionState.DISABLED,
            overlayCues = emptyList(),
            suppressNativeSubtitles = false
        )
        val current = previous.copy(
            burnInProtection = BurnInProtectionState(
                enabled = true,
                verticalDeltaPercent = 1.5f,
                horizontalOffsetPx = 3f
            )
        )

        val plan = buildPlayerViewMutationPlan(previous = previous, current = current)

        assertTrue(plan.updateSubtitleStyle)
    }

    private class FakeComposeSurfaceSyncTarget {
        var enabled = false

        @Suppress("unused")
        fun setEnableComposeSurfaceSyncWorkaround(enabled: Boolean) {
            this.enabled = enabled
        }
    }
}
